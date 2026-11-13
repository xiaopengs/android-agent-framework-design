package nanomuse.headless.context

import nanomuse.headless.core.Message
import org.json.JSONObject

/**
 * Layer4 —— ContextManager 上下文裁剪。
 *
 * 对齐 DESIGN.md 2.1（Layer4 职责）与 runtime/COVERAGE.md 路线第 2 项：
 *  - 单条超长消息截断，工具结果用更紧的阈值（stdout 最容易撑爆上下文）；
 *  - 超预算时保留首条用户意图 + 最近 N 轮，中间折叠成一条摘要消息（摘要回收）；
 *  - 不丢 tool_call / tool_call_id 配对，避免 OpenAI 兼容端点报 400。
 */
data class CompactResult(
    val messages: List<Message>,
    val dropped: Int,
    val truncated: Int,
    val summaryAdded: Boolean,
    val originalChars: Int,
    val compactedChars: Int,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("dropped", dropped)
        .put("truncated", truncated)
        .put("summary_added", summaryAdded)
        .put("original_chars", originalChars)
        .put("compacted_chars", compactedChars)
        .put("kept", messages.size)
}

class ContextManager(
    private val maxChars: Int = 60_000,
    private val keepFirstUser: Int = 1,
    private val keepRecent: Int = 12,
    private val maxMessageChars: Int = 8_000,
    private val maxToolResultChars: Int = 4_000,
) {
    fun estimate(messages: List<Message>): Int = messages.sumOf { chars(it) }

    fun compact(messages: List<Message>): CompactResult {
        val original = estimate(messages)
        var truncatedCount = 0
        val clipped = messages.map { m ->
            val limit = if (m.role == "tool") maxToolResultChars else maxMessageChars
            val text = m.content ?: return@map m
            if (text.length <= limit) return@map m
            truncatedCount++
            val omitted = text.length - limit
            m.copy(content = text.take(limit) + "\n…[context truncated $omitted chars]")
        }

        if (estimate(clipped) <= maxChars) {
            return CompactResult(clipped, 0, truncatedCount, false, original, estimate(clipped))
        }

        // 保留头部用户意图与最近若干轮，中间折叠为摘要
        val headCount = minOf(keepFirstUser, clipped.size)
        val head = clipped.take(headCount)
        val tailStart = (clipped.size - keepRecent).coerceAtLeast(headCount)
        val middle = clipped.subList(headCount, tailStart)
        val tail = clipped.subList(tailStart, clipped.size)

        val toolNames = middle.flatMap { it.toolCalls.map { c -> c.name } }.distinct()
        val toolResults = middle.count { it.role == "tool" }
        val summary = buildString {
            append("[context summary] 已压缩中间 ").append(middle.size).append(" 条历史消息")
            if (toolResults > 0) append("，含 ").append(toolResults).append(" 条工具结果")
            if (toolNames.isNotEmpty()) append("，涉及工具：").append(toolNames.joinToString("、"))
            append("。以下为最近上下文，请据此继续。")
        }

        // 尾部若以孤儿 tool 消息开头，补一条占位 assistant，防止 tool_call_id 失配
        val safeTail = tail.toMutableList()
        if (safeTail.firstOrNull()?.role == "tool") {
            safeTail.add(0, Message(role = "assistant", content = "[context continued]"))
        }

        val result = head + Message(role = "user", content = summary) + safeTail
        return CompactResult(result, middle.size, truncatedCount, true, original, estimate(result))
    }

    private fun chars(m: Message): Int =
        (m.content?.length ?: 0) + m.toolCalls.sumOf { it.name.length + it.arguments.length + 32 }
}
