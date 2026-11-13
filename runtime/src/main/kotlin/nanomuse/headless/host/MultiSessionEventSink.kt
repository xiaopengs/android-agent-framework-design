package nanomuse.headless.host

import nanomuse.headless.core.EventSink
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * 按 session 分流的事件出口：一个 runtime 实例共享一个 sink，
 * 每个会话单独落一份 NDJSON，无 session 字段的事件落到 _global.ndjson。
 * 上层要转 SSE / WebSocket 时按 session 订阅对应文件即可。
 */
class MultiSessionEventSink(
    private val dir: Path,
    private val echo: Boolean = false,
) : EventSink {
    private val sinks = ConcurrentHashMap<String, EventSink>()
    private val global = JsonlEventSink(dir.resolve("_global.ndjson"), echo)

    init { Files.createDirectories(dir) }

    override fun emit(type: String, payload: JSONObject) {
        val sid = payload.optString("session").ifBlank { payload.optString("session_id") }
        val target = if (sid.isBlank()) global else sinks.computeIfAbsent(safe(sid)) {
            JsonlEventSink(dir.resolve(safe(sid) + ".ndjson"), echo)
        }
        target.emit(type, payload)
    }

    fun fileFor(sessionId: String): Path = dir.resolve(safe(sessionId) + ".ndjson")

    private fun safe(s: String): String = s.replace(Regex("[^A-Za-z0-9_-]"), "_")
}
