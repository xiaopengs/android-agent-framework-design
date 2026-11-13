package nanomuse.headless.core

import nanomuse.headless.context.ContextManager
import nanomuse.headless.tools.ToolDispatchCenter
import org.json.JSONArray
import org.json.JSONObject

/**
 * 回合循环 —— 对应 Android 端 ui/chat/ChatViewModel.kt 里的 runAgentLoop()。
 *
 * V1.2 在保持原有语义（200 轮上限、tool_call 回灌、打转检测、turn_limit 收尾）之上接入：
 *  - [ContextManager] 上下文裁剪（可选，不传则行为与 V1.1 完全一致）
 *  - [ToolDispatchCenter] 统一分发（不传则退回内核内按名路由）
 *  - 协作式取消 / 每轮 turn 状态回调 / 断点续跑
 * AgentLoop 依然是「不要改」的被验证内核：新增能力全部以可选构造参数注入。
 */
class AgentLoop(
    private val provider: ModelProvider,
    private val tools: List<Tool>,
    private val sink: EventSink,
    private val store: SessionStore,
    private val systemPrompt: String,
    private val maxTurns: Int = 200,
    private val loopRepeatLimit: Int = 3,
    private val contextManager: ContextManager? = null,
    private val dispatch: ToolDispatchCenter? = null,
    private val isCancelled: (String) -> Boolean = { false },
    private val onTurn: (Int) -> Unit = {},
    private val onState: (String) -> Unit = {},
) {
    private val byName = tools.associateBy { it.spec.name }

    data class Outcome(
        val text: String,
        val turns: Int,
        val stoppedBy: String,      // done / turn_limit / loop_detected / cancelled
        val usage: Usage,
    )

    /**
     * @param resume true 时复用已落盘的历史继续跑（断点续跑），不重复追加 user 消息。
     */
    fun run(sessionId: String, userText: String, ctx: ToolContext, resume: Boolean = false): Outcome {
        val history = store.history(sessionId).toMutableList()
        val resumed = resume && history.isNotEmpty()
        if (!resumed) {
            val user = Message(role = "user", content = userText)
            history += user
            store.append(sessionId, user)
        }
        sink.emit(
            "turn_start",
            JSONObject().put("session", sessionId).put("text", userText)
                .put("resume", resumed).put("trace_id", ctx.traceId),
        )

        var accumulated = StringBuilder()
        var totalPrompt = 0
        var totalCompletion = 0
        var stopped = "done"
        var turn = 0
        val seen = HashMap<String, Int>()

        while (turn < maxTurns) {
            if (isCancelled(sessionId)) {
                stopped = "cancelled"
                sink.emit("cancelled", JSONObject().put("session", sessionId).put("turn", turn))
                break
            }
            turn++
            onTurn(turn)
            sink.emit("turn", JSONObject().put("turn", turn).put("session", sessionId))

            val window = if (contextManager != null) {
                val compacted = contextManager.compact(history)
                if (compacted.dropped > 0 || compacted.truncated > 0) {
                    sink.emit("context_compact", compacted.toJson().put("session", sessionId))
                }
                compacted.messages
            } else history

            var streamed = false
            val reply = provider.complete(
                system = systemPrompt,
                messages = window,
                tools = tools.map { it.spec },
            ) { delta ->
                streamed = true
                accumulated.append(delta)
                sink.emit("text", JSONObject().put("delta", delta).put("session", sessionId))
            }

            totalPrompt += reply.usage.promptTokens
            totalCompletion += reply.usage.completionTokens
            if (!streamed) reply.text?.takeIf { it.isNotBlank() }?.let {
                accumulated.append(it)
                sink.emit("text", JSONObject().put("delta", it).put("session", sessionId))
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

            onState("TOOL_EXEC")
            for (call in reply.toolCalls) {
                val key = call.name + "|" + normalize(call.arguments)
                val n = (seen[key] ?: 0) + 1
                seen[key] = n
                if (n >= loopRepeatLimit) {
                    sink.emit(
                        "loop_detected",
                        JSONObject().put("tool", call.name).put("repeat", n).put("session", sessionId),
                    )
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
                    JSONObject().put("id", call.id).put("name", call.name)
                        .put("args", call.arguments).put("session", sessionId),
                )
                val started = System.currentTimeMillis()
                val result = runCatching { executeOne(call, ctx) }
                    .getOrElse { e -> ToolResult("Error: ${e.javaClass.simpleName}: ${e.message}", isError = true) }
                val ms = System.currentTimeMillis() - started

                sink.emit(
                    "tool_result",
                    JSONObject().put("id", call.id).put("name", call.name)
                        .put("ms", ms).put("error", result.isError)
                        .put("preview", preview(result.content)).put("session", sessionId),
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
            onState("RUNNING")
        }

        if (turn >= maxTurns && stopped != "cancelled") stopped = "turn_limit"
        store.saveTurn(sessionId, turn, stopped)
        sink.emit(
            "done",
            JSONObject().put("session", sessionId).put("turns", turn)
                .put("stopped_by", stopped).put("trace_id", ctx.traceId)
                .put("prompt_tokens", totalPrompt).put("completion_tokens", totalCompletion),
        )
        return Outcome(accumulated.toString(), turn, stopped, Usage(totalPrompt, totalCompletion))
    }

    private fun executeOne(call: ToolCall, ctx: ToolContext): ToolResult {
        if (dispatch != null) return dispatch.invoke(call, ctx)
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
