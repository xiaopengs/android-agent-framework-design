package nanomuse.headless.capability

import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.ArrayDeque
import java.util.UUID

/**
 * Layer5 —— SystemCapability 系统能力抽象。
 *
 * 对齐 DESIGN.md 7.9 / 7.10 与「Android 移植提示」：平台差异隔离在接口之后。
 * 本地实现用文件模拟日历、用 NDJSON 记录通知；Android 端替换为
 * CalendarContract / NotificationManager 即可，工具层与 AgentLoop 不用改。
 */
interface SystemCapability {
    /** action: query | add | update | delete */
    fun calendar(args: JSONObject): JSONObject

    /** 推送本地通知，含频率限流 */
    fun notify(args: JSONObject): JSONObject
}

class LocalSystemCapability(
    private val root: Path,
    private val maxNotifyPerMinute: Int = 5,
) : SystemCapability {
    private val calendarFile = root.resolve("calendar.json")
    private val notifyFile = root.resolve("notifications.ndjson")
    private val recentNotifies = ArrayDeque<Long>()

    init { Files.createDirectories(root) }

    @Synchronized
    override fun calendar(args: JSONObject): JSONObject {
        val action = args.optString("action", "query").lowercase()
        val list = readCalendar().toMutableList()
        return when (action) {
            "query" -> {
                val from = args.optString("from").ifBlank { null }
                val to = args.optString("to").ifBlank { null }
                val filtered = list.filter { e ->
                    (from == null || e.optString("start") >= from) &&
                            (to == null || e.optString("start") <= to)
                }
                ok(JSONObject().put("count", filtered.size).put("events", JSONArray().apply {
                    filtered.forEach { put(it) }
                }))
            }
            "add" -> {
                val title = args.optString("title")
                if (title.isBlank()) return fail("title is required")
                val e = JSONObject()
                    .put("id", "evt-" + UUID.randomUUID().toString().take(8))
                    .put("title", title)
                    .put("start", args.optString("start"))
                    .put("end", args.optString("end"))
                    .put("description", args.optString("description"))
                list += e
                writeCalendar(list)
                ok(JSONObject().put("event", e))
            }
            "update" -> {
                val id = args.optString("id")
                val idx = list.indexOfFirst { it.optString("id") == id }
                if (idx < 0) return fail("no such event: $id")
                val updated = JSONObject(list[idx].toString())
                listOf("title", "start", "end", "description").forEach { k ->
                    if (args.has(k)) updated.put(k, args.optString(k))
                }
                list[idx] = updated
                writeCalendar(list)
                ok(JSONObject().put("event", updated))
            }
            "delete" -> {
                val id = args.optString("id")
                val removed = list.removeIf { it.optString("id") == id }
                if (!removed) return fail("no such event: $id")
                writeCalendar(list)
                ok(JSONObject().put("deleted", id))
            }
            else -> fail("unknown calendar action: $action (query/add/update/delete)")
        }
    }

    @Synchronized
    override fun notify(args: JSONObject): JSONObject {
        val now = System.currentTimeMillis()
        while (recentNotifies.isNotEmpty() && now - recentNotifies.first() > 60_000) recentNotifies.removeFirst()
        if (recentNotifies.size >= maxNotifyPerMinute) {
            return fail("通知频率超限：$maxNotifyPerMinute 条/分钟，请稍后再试")
        }
        val title = args.optString("title")
        val text = args.optString("text")
        if (title.isBlank() && text.isBlank()) return fail("title or text is required")
        val record = JSONObject()
            .put("id", "ntf-" + UUID.randomUUID().toString().take(8))
            .put("title", title).put("text", text)
            .put("priority", args.optString("priority", "normal"))
            .put("at", now)
        Files.writeString(
            notifyFile, record.toString() + "\n",
            StandardOpenOption.CREATE, StandardOpenOption.APPEND,
        )
        recentNotifies.addLast(now)
        return ok(record)
    }

    private fun ok(data: JSONObject): JSONObject = JSONObject().put("ok", true).put("data", data)
    private fun fail(msg: String): JSONObject = JSONObject().put("ok", false).put("error", msg)

    private fun readCalendar(): List<JSONObject> {
        if (!Files.exists(calendarFile)) return emptyList()
        return runCatching {
            val arr = JSONArray(Files.readString(calendarFile))
            (0 until arr.length()).map { arr.getJSONObject(it) }
        }.getOrDefault(emptyList())
    }

    private fun writeCalendar(list: List<JSONObject>) {
        Files.writeString(
            calendarFile,
            JSONArray().apply { list.forEach { put(it) } }.toString(2),
        )
    }
}
