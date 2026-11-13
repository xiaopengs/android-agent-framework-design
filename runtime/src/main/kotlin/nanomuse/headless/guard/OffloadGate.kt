package nanomuse.headless.guard

import nanomuse.headless.core.EventSink
import org.json.JSONObject

/**
 * Layer5 —— OffloadGate 卸载降级闸（DESIGN.md 2.1 / 第 4 章工具子时序）。
 *
 * 两件事：
 *  1. 云端下发标记校验：fromCloud 的 TOOL_CALL 必须带 traceId / sessionId，
 *     且目标工具在允许清单内，否则 REJECT（零特权绕过）；
 *  2. 本地能力不足时卸载到云端：本地不具备的能力且开启 allowOffload 才 OFFLOAD_CLOUD，
 *     默认 REJECT 并给出明确原因，避免静默把端侧独占能力交给云端。
 */
enum class OffloadAction { EXECUTE_LOCAL, OFFLOAD_CLOUD, REJECT }

data class OffloadDecision(
    val action: OffloadAction,
    val reason: String,
    val forceRiskCheck: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("action", action.name).put("reason", reason)
        .put("force_risk_check", forceRiskCheck)
}

class OffloadGate(
    private val localCapableTools: Set<String>,
    private val allowOffload: Boolean = false,
    private val events: EventSink? = null,
) {
    fun check(
        toolName: String,
        fromCloud: Boolean,
        sessionId: String = "",
        traceId: String = "",
    ): OffloadDecision {
        val decision = when {
            toolName !in localCapableTools && allowOffload ->
                OffloadDecision(
                    OffloadAction.OFFLOAD_CLOUD,
                    "本地不具备 '$toolName' 能力，卸载到云端执行",
                )
            toolName !in localCapableTools ->
                OffloadDecision(
                    OffloadAction.REJECT,
                    "本地不具备 '$toolName' 能力且未开启卸载；端侧独占能力不出端",
                )
            fromCloud && (traceId.isBlank() || sessionId.isBlank()) ->
                OffloadDecision(
                    OffloadAction.REJECT,
                    "云端 TOOL_CALL 缺少 traceId / sessionId，拒绝越权调用",
                )
            else -> OffloadDecision(
                OffloadAction.EXECUTE_LOCAL,
                if (fromCloud) "云端下发 TOOL_CALL，端侧强制走完整风控链路" else "本地能力完备，直接执行",
                forceRiskCheck = fromCloud,
            )
        }
        events?.emit(
            "offload_gate",
            decision.toJson()
                .put("tool", toolName).put("from_cloud", fromCloud)
                .put("session", sessionId).put("trace_id", traceId),
        )
        return decision
    }
}
