package nanomuse.headless.host

import nanomuse.headless.core.*
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** 会话存储：JSONL 追加，一行一条消息，对应 Android 端 SQLite 的 messages 表。 */
class JsonlSessionStore(private val dir: Path) : SessionStore {
    init { Files.createDirectories(dir) }

    private fun file(sessionId: String): Path =
        dir.resolve(sessionId.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".jsonl")

    override fun append(sessionId: String, message: Message) {
        val line = message.toJson().toString()
        Files.writeString(
            file(sessionId), line + "\n",
            StandardOpenOption.CREATE, StandardOpenOption.APPEND,
        )
    }

    override fun history(sessionId: String): List<Message> {
        val f = file(sessionId)
        if (!Files.exists(f)) return emptyList()
        return Files.readAllLines(f).mapNotNull { line ->
            runCatching { parse(JSONObject(line)) }.getOrNull()
        }.filter { it.role != "meta" }
    }

    override fun saveTurn(sessionId: String, turn: Int, status: String) {
        val meta = JSONObject().put("role", "meta").put("turn", turn).put("status", status)
            .put("at", System.currentTimeMillis())
        Files.writeString(
            file(sessionId), meta.toString() + "\n",
            StandardOpenOption.CREATE, StandardOpenOption.APPEND,
        )
    }

    private fun parse(o: JSONObject): Message {
        val calls = o.optJSONArray("tool_calls")?.let { arr ->
            (0 until arr.length()).map { i ->
                val c = arr.getJSONObject(i).getJSONObject("function")
                ToolCall(
                    id = arr.getJSONObject(i).optString("id"),
                    name = c.optString("name"),
                    arguments = c.optString("arguments"),
                )
            }
        } ?: emptyList()
        return Message(
            role = o.optString("role"),
            content = if (o.has("content")) o.optString("content") else null,
            toolCalls = calls,
            toolCallId = o.optString("tool_call_id").ifBlank { null },
            name = o.optString("name").ifBlank { null },
        )
    }
}

/** 事件流：一行一个 JSON，既能 tail -f，也能被上层转成 SSE / WebSocket。 */
class JsonlEventSink(private val file: Path, private val echo: Boolean = true) : EventSink {
    init { Files.createDirectories(file.parent) }
    override fun emit(type: String, payload: JSONObject) {
        val o = JSONObject(payload.toString()).put("type", type).put("ts", System.currentTimeMillis())
        val line = o.toString()
        Files.writeString(
            file, line + "\n",
            StandardOpenOption.CREATE, StandardOpenOption.APPEND,
        )
        if (echo) println(line)
    }
}
