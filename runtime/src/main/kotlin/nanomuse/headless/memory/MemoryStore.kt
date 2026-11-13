package nanomuse.headless.memory

import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Layer5 —— MemoryStore 长期记忆持久化（DESIGN.md 7.7 / 7.8 / 9.1）。
 *
 * 本地实现用 JSONL 追加；Android 端替换为 SQLite + 向量检索即可，接口不变。
 * 风控：单条容量上限、总配额、expire_at 过期过滤、key 精确检索 + tags 交集检索。
 */
data class MemoryEntry(
    val key: String,
    val content: String,
    val tags: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val expireAt: Long? = null,
    val sessionId: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("key", key)
        .put("content", content)
        .put("tags", JSONArray().apply { tags.forEach { put(it) } })
        .put("created_at", createdAt)
        .put("expire_at", expireAt ?: JSONObject.NULL)
        .put("session_id", sessionId)

    fun expired(now: Long = System.currentTimeMillis()): Boolean =
        expireAt?.let { it <= now } ?: false

    companion object {
        fun fromJson(o: JSONObject): MemoryEntry = MemoryEntry(
            key = o.optString("key"),
            content = o.optString("content"),
            tags = o.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.optString(it) } }
                ?: emptyList(),
            createdAt = o.optLong("created_at", 0),
            expireAt = if (o.isNull("expire_at")) null else o.optLong("expire_at", 0).takeIf { it > 0 },
            sessionId = o.optString("session_id"),
        )
    }
}

data class MemoryResult(val ok: Boolean, val message: String)

interface MemoryStore {
    fun put(entry: MemoryEntry): MemoryResult
    fun get(key: String?, tags: List<String>, topK: Int): List<MemoryEntry>
    fun delete(key: String): Boolean
    fun stats(): JSONObject
}

class JsonlMemoryStore(
    private val file: Path,
    private val maxEntryChars: Int = 8_000,
    private val totalQuotaChars: Int = 512_000,
) : MemoryStore {
    init { file.parent?.let { Files.createDirectories(it) } }

    @Synchronized
    override fun put(entry: MemoryEntry): MemoryResult {
        if (entry.key.isBlank()) return MemoryResult(false, "key is required")
        if (entry.content.length > maxEntryChars)
            return MemoryResult(false, "entry too large: ${entry.content.length} > $maxEntryChars chars")
        val all = load(includeExpired = true)
        val used = all.sumOf { it.content.length }
        if (used + entry.content.length > totalQuotaChars)
            return MemoryResult(false, "memory quota exceeded: $used + ${entry.content.length} > $totalQuotaChars")
        Files.writeString(
            file, entry.toJson().toString() + "\n",
            StandardOpenOption.CREATE, StandardOpenOption.APPEND,
        )
        return MemoryResult(true, "saved ${entry.content.length} chars under key '${entry.key}'")
    }

    @Synchronized
    override fun get(key: String?, tags: List<String>, topK: Int): List<MemoryEntry> {
        val now = System.currentTimeMillis()
        return load(includeExpired = false)
            .filter { !it.expired(now) }
            .filter { e ->
                val keyHit = key.isNullOrBlank() || e.key == key
                val tagHit = tags.isEmpty() || e.tags.any { it in tags }
                keyHit && (tags.isEmpty() || tagHit)
            }
            .sortedByDescending { it.createdAt }
            .take(topK.coerceIn(1, 50))
    }

    @Synchronized
    override fun delete(key: String): Boolean {
        val all = load(includeExpired = true)
        val kept = all.filterNot { it.key == key }
        if (kept.size == all.size) return false
        Files.writeString(file, kept.joinToString("\n") { it.toJson().toString() } + "\n")
        return true
    }

    @Synchronized
    override fun stats(): JSONObject {
        val live = load(includeExpired = false)
        return JSONObject()
            .put("entries", live.size)
            .put("used_chars", live.sumOf { it.content.length })
            .put("quota_chars", totalQuotaChars)
            .put("max_entry_chars", maxEntryChars)
            .put("file", file.toString())
    }

    private fun load(includeExpired: Boolean): List<MemoryEntry> {
        if (!Files.exists(file)) return emptyList()
        return Files.readAllLines(file).mapNotNull { line ->
            runCatching { MemoryEntry.fromJson(JSONObject(line)) }.getOrNull()
                ?.takeIf { includeExpired || !it.expired() }
        }
    }
}
