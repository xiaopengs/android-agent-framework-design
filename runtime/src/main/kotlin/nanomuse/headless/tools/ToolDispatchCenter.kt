package nanomuse.headless.tools

import nanomuse.headless.core.EventSink
import nanomuse.headless.core.Json
import nanomuse.headless.core.Tool
import nanomuse.headless.core.ToolCall
import nanomuse.headless.core.ToolContext
import nanomuse.headless.core.ToolResult
import nanomuse.headless.core.ToolSpec
import nanomuse.headless.guard.OffloadAction
import nanomuse.headless.guard.OffloadGate
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Layer5 —— ToolDispatchCenter 工具统一分发中台。
 *
 * 对齐 DESIGN.md 6.3 / 第 4 章：按名路由、统一入参校验（required + 类型）、
 * OffloadGate 卸载判定、未知工具/非法参数兜底、审计日志（traceId 贯穿）。
 * 本地触发与云端下发 TOOL_CALL 共用这一个入口，因此风控链路完全一致。
 */
class ToolDispatchCenter(
    private val tools: List<Tool>,
    private val offloadGate: OffloadGate,
    private val events: EventSink? = null,
) {
    private val byName = tools.associateBy { it.spec.name }

    fun specs(): List<ToolSpec> = tools.map { it.spec }
    fun names(): Set<String> = byName.keys.toSet()
    fun has(name: String): Boolean = byName.containsKey(name)

    fun invoke(call: ToolCall, ctx: ToolContext): ToolResult {
        val started = System.currentTimeMillis()
        val args = runCatching { Json.obj(call.arguments) }.getOrElse {
            return fail(call, ctx, started, "arguments are not valid JSON", "PARAM_INVALID")
        }

        val gate = offloadGate.check(call.name, ctx.fromCloud, ctx.sessionId, ctx.traceId)
        if (gate.action != OffloadAction.EXECUTE_LOCAL) {
            return fail(
                call, ctx, started,
                "offload gate ${gate.action.name}: ${gate.reason}",
                if (gate.action == OffloadAction.REJECT) "PERMISSION_DENIED" else "OFFLOADED",
            )
        }

        val tool = byName[call.name]
            ?: return fail(call, ctx, started, "unknown tool '${call.name}'", "PARAM_INVALID")
        validate(tool.spec, args)?.let {
            return fail(call, ctx, started, it, "PARAM_INVALID")
        }

        val result = runCatching { tool.execute(args, ctx) }.getOrElse { e ->
            ToolResult("Error: ${e.javaClass.simpleName}: ${e.message}", isError = true, code = -2)
        }
        audit(call, ctx, started, result, gate.action.name)
        return result
    }

    /** MCP / A2A 入口：无需构造 ToolCall，直接给工具名与参数 JSON。 */
    fun invokeRaw(name: String, argumentsJson: String, ctx: ToolContext): ToolResult =
        invoke(ToolCall("ext-" + UUID.randomUUID().toString().take(8), name, argumentsJson), ctx)

    /** HYBRID 回传云端用：直接产出 DESIGN.md 6.3 标准化出参。 */
    fun invokeForBus(call: ToolCall, ctx: ToolContext): JSONObject {
        val started = System.currentTimeMillis()
        val r = invoke(call, ctx)
        return r.toProtocolJson(call.name, ctx.sessionId, System.currentTimeMillis() - started)
    }

    private fun validate(spec: ToolSpec, args: JSONObject): String? {
        val required = spec.parameters.optJSONArray("required") ?: JSONArray()
        for (i in 0 until required.length()) {
            val k = required.optString(i)
            if (k.isBlank()) continue
            if (!args.has(k) || args.isNull(k)) return "missing required parameter: $k"
        }
        val props = spec.parameters.optJSONObject("properties") ?: return null
        for (k in props.keys()) {
            if (!args.has(k) || args.isNull(k)) continue
            val expected = props.optJSONObject(k)?.optString("type") ?: continue
            val value = args.opt(k)
            val mismatch = when (expected) {
                "string" -> value !is String
                "integer", "number" -> value !is Number
                "boolean" -> value !is Boolean
                "object" -> value !is JSONObject
                "array" -> value !is JSONArray
                else -> false
            }
            if (mismatch) return "parameter '$k' expects $expected but got ${value?.javaClass?.simpleName}"
        }
        return null
    }

    private fun fail(
        call: ToolCall,
        ctx: ToolContext,
        started: Long,
        message: String,
        code: String,
    ): ToolResult {
        val r = ToolResult("Error: $message", isError = true, code = -1, riskMsg = code)
        events?.emit(
            "tool_dispatch",
            JSONObject().put("tool", call.name).put("error", true)
                .put("code", code).put("message", message)
                .put("ms", System.currentTimeMillis() - started)
                .put("trace_id", ctx.traceId).put("session", ctx.sessionId)
                .put("from_cloud", ctx.fromCloud),
        )
        return r
    }

    private fun audit(call: ToolCall, ctx: ToolContext, started: Long, r: ToolResult, gate: String) {
        events?.emit(
            "tool_dispatch",
            JSONObject()
                .put("tool", call.name)
                .put("trace_id", ctx.traceId)
                .put("session", ctx.sessionId)
                .put("from_cloud", ctx.fromCloud)
                .put("gate", gate)
                .put("args_preview", call.arguments.take(400))
                .put("ms", System.currentTimeMillis() - started)
                .put("error", r.isError)
                .put("risk_block", r.riskBlock),
        )
    }
}
