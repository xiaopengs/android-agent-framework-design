package nanomuse.headless.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * 回合循环 —— 对应 Android 端 ui/chat/ChatViewModel.kt 里的 runAgentLoop()。
 *
 * 这里只保留了循环本身需要的语义，把 UI、Compose、Context、数据库实现全部剥掉：
 *  · 上限 MAX_TURNS（Android 端 MAX_AGENT_TURNS = 200）
 *  · 每轮：请求 → 有 tool_call 就执行并回灌 → 没有就收尾
 *  · 同一 (工具名 + 参数) 重复三次判定为打转，直接停（对应 ToolLoopDetector）
 *  · 撞上限不是异常，而是"可继续"状态（对应 finalizeAtTurnLimit）
 */
class AgentLoop(
    private val provider: ModelProvider,
    private val tools: List<Tool>,
    private val sink: EventSink,
    private val store: SessionStore,
    private val systemPrompt: String,
    private val maxTurns: Int = 200,
    private val loopRepeatLimit: Int = 3,
) {
    private val byName = tools.associateBy { it.spec.name }

    data class Outcome(
        val text: String,
        val turns: Int,
        val stoppedBy: String,      // done / turn_limit / loop_detected / cancelled
        val usage: Usage,
    )

    fun run(sessionId: String, userText: String, ctx: ToolContext): Outcome {
        val history = store.history(sessionId).toMutableList()
        val user = Message(role = "user", content = userText)
        history += user
        store.append(sessionId, user)
        sink.emit("turn_start", JSONObject().put("session", sessionId).put("text", userText))

        var accumulated = StringBuilder()
        var totalPrompt = 0
        var totalCompletion = 0
        var stopped = "done"
        var turn = 0
        val seen = HashMap<String, Int>()

        while (turn < maxTurns) {
            turn++
            sink.emit("turn", JSONObject().put("turn", turn))

            var streamed = false
            val reply = provider.complete(
                system = systemPrompt,
                messages = history,
                tools = tools.map { it.spec },
            ) { delta ->
                streamed = true
                accumulated.append(delta)
                sink.emit("text", JSONObject().put("delta", delta))
            }

            totalPrompt += reply.usage.promptTokens
            totalCompletion += reply.usage.completionTokens
            if (!streamed) reply.text?.takeIf { it.isNotBlank() }?.let {
                accumulated.append(it)
                sink.emit("text", JSONObject().put("delta", it))
            }

            val assistant = Message(
                role = "assistant",
                content = reply.text,
                toolCalls = reply.toolCalls,
            )
            history += assistant
            store.append(sessionId, assistant)

            if (reply.toolCalls.isEmpty()) {
                stopped = "done"
                break
            }

            for (call in reply.toolCalls) {
                val key = call.name + "|" + normalize(call.arguments)
                val n = (seen[key] ?: 0) + 1
                seen[key] = n
                if (n >= loopRepeatLimit) {
                    sink.emit("loop_detected", JSONObject().put("tool", call.name).put("repeat", n))
                    val msg = Message(
                        role = "tool",
                        content = "Error: the same call repeated $n times; stopping. Change approach or ask the user.",
                        toolCallId = call.id,
                        name = call.name,
                    )
                    history += msg
                    store.append(sessionId, msg)
                    stopped = "loop_detected"
                    return Outcome(accumulated.toString(), turn, stopped, Usage(totalPrompt, totalCompletion))
                }

                sink.emit(
                    "tool_start",
                    JSONObject().put("id", call.id).put("name", call.name).put("args", call.arguments),
                )
                val started = System.currentTimeMillis()
                val result = runCatching { executeOne(call, ctx) }
                    .getOrElse { e -> ToolResult("Error: ${e.javaClass.simpleName}: ${e.message}", isError = true) }
                val ms = System.currentTimeMillis() - started

                sink.emit(
                    "tool_result",
                    JSONObject().put("id", call.id).put("name", call.name)
                        .put("ms", ms).put("error", result.isError)
                        .put("preview", preview(result.content)),
                )
                val msg = Message(
                    role = "tool",
                    content = result.content,
                    toolCallId = call.id,
                    name = call.name,
                )
                history += msg
                store.append(sessionId, msg)
            }
        }

        if (turn >= maxTurns) stopped = "turn_limit"
        store.saveTurn(sessionId, turn, stopped)
        sink.emit(
            "done",
            JSONObject().put("session", sessionId).put("turns", turn)
                .put("stopped_by", stopped)
                .put("prompt_tokens", totalPrompt).put("completion_tokens", totalCompletion),
        )
        return Outcome(accumulated.toString(), turn, stopped, Usage(totalPrompt, totalCompletion))
    }

    private fun executeOne(call: ToolCall, ctx: ToolContext): ToolResult {
        val tool = byName[call.name] ?: return ToolResult("Error: unknown tool '${call.name}'.", isError = true)
        val args = runCatching { Json.obj(call.arguments) }.getOrElse {
            return ToolResult("Error: arguments are not valid JSON.", isError = true)
        }
        return tool.execute(args, ctx)
    }

    private fun preview(s: String, max: Int = 400): String =
        if (s.length <= max) s else s.take(max) + "…"

    private fun normalize(args: String): String =
        runCatching {
            val o = JSONObject(args)
            val keys = o.keys().asSequence().sorted().toList()
            keys.joinToString("&") { "$it=${o.opt(it)}" }
        }.getOrElse { args }
}
