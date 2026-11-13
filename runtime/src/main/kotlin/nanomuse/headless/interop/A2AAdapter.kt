package nanomuse.headless.interop

import nanomuse.headless.bus.AgentEnvelope
import nanomuse.headless.bus.CloudAgentBus
import nanomuse.headless.bus.EnvelopeDirection
import nanomuse.headless.bus.MsgType
import nanomuse.headless.bus.RouterTarget
import nanomuse.headless.bus.SendResult
import nanomuse.headless.core.ToolContext
import nanomuse.headless.tools.ToolDispatchCenter
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 互操作适配 —— A2A 跨设备任务信道 + App Function 适配层（DESIGN.md 15 交付物 8）。
 *
 * A2A 任务与 CloudAgentBus 信封双向转换，复用同一条 ACK/重试/死信链路；
 * App Function 把 10 个工具映射成可被系统意图调用的函数 schema。
 */
data class A2ATask(
    val taskId: String = "a2a-" + UUID.randomUUID().toString().take(10),
    val fromDevice: String,
    val toDevice: String,
    val intent: String,
    val payload: JSONObject = JSONObject(),
    val traceId: String = "tr-" + UUID.randomUUID().toString().replace("-", "").take(16),
    val sessionId: String = "s-" + UUID.randomUUID().toString().take(8),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("task_id", taskId).put("from_device", fromDevice)
        .put("to_device", toDevice).put("intent", intent)
        .put("payload", JSONObject(payload.toString()))
        .put("trace_id", traceId).put("session_id", sessionId)
}

class A2AAdapter(
    private val bus: CloudAgentBus,
    private val localDeviceId: String = "device-local",
) {
    /** 出站：A2A 任务 → TO_CLOUD QUERY 信封（meta 带设备信息）。 */
    fun toEnvelope(task: A2ATask): AgentEnvelope = AgentEnvelope(
        traceId = task.traceId,
        sessionId = task.sessionId,
        direction = EnvelopeDirection.TO_CLOUD,
        msgType = MsgType.QUERY,
        routerTarget = RouterTarget.HYBRID,
        payload = JSONObject()
            .put("a2a_task", task.toJson())
            .put("query", task.payload.optString("query", task.intent)),
        meta = JSONObject()
            .put("channel", "A2A")
            .put("from_device", task.fromDevice)
            .put("to_device", task.toDevice),
    )

    /** 入站：信封里的 a2a_task 还原成 A2A 任务；非 A2A 信封返回 null。 */
    fun fromEnvelope(env: AgentEnvelope): A2ATask? {
        val o = env.payload.optJSONObject("a2a_task") ?: return null
        return A2ATask(
            taskId = o.optString("task_id"),
            fromDevice = o.optString("from_device"),
            toDevice = o.optString("to_device"),
            intent = o.optString("intent"),
            payload = o.optJSONObject("payload") ?: JSONObject(),
            traceId = o.optString("trace_id", env.traceId),
            sessionId = o.optString("session_id", env.sessionId),
        )
    }

    fun submitRemote(task: A2ATask): SendResult = bus.send(toEnvelope(task))

    /** 本设备收到远端任务：落一个 TO_LOCAL QUERY 信封到本地队列。 */
    fun accept(task: A2ATask): Boolean = bus.enqueueLocal(
        AgentEnvelope(
            traceId = task.traceId,
            sessionId = task.sessionId,
            direction = EnvelopeDirection.TO_LOCAL,
            msgType = MsgType.QUERY,
            routerTarget = RouterTarget.LOCAL,
            payload = JSONObject().put("query", task.payload.optString("query", task.intent))
                .put("a2a_task", task.toJson()),
            meta = JSONObject().put("channel", "A2A").put("from_device", task.fromDevice),
        )
    )

    fun localDeviceId(): String = localDeviceId
}

/** Android App Functions：把工具表导出为 {functions:[{name, description, parameters}]}。 */
class AppFunctionAdapter(private val center: ToolDispatchCenter) {
    fun schema(): JSONObject = JSONObject().put("functions", JSONArray().apply {
        center.specs().forEach { spec ->
            put(
                JSONObject()
                    .put("name", spec.name)
                    .put("description", spec.description)
                    .put("parameters", JSONObject(spec.parameters.toString()))
                    .put("requiredPermissions", JSONArray().put("android.permission.HEADLESS_AGENT")),
            )
        }
    })

    /** 系统意图直接调函数，仍然经过 ToolDispatchCenter（风控不旁路）。 */
    fun invoke(functionName: String, args: JSONObject, ctx: ToolContext): JSONObject {
        val r = center.invokeRaw(functionName, args.toString(), ctx)
        return r.toProtocolJson(functionName, ctx.sessionId, 0)
    }
}
