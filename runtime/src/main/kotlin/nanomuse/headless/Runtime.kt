package nanomuse.headless

import nanomuse.headless.bus.AgentEnvelope
import nanomuse.headless.bus.CloudAgentBus
import nanomuse.headless.bus.EnvelopeDirection
import nanomuse.headless.bus.MsgType
import nanomuse.headless.bus.RouterTarget
import nanomuse.headless.bus.SimulatedCloudTransport
import nanomuse.headless.capability.LocalSystemCapability
import nanomuse.headless.capability.SystemCapability
import nanomuse.headless.context.ContextManager
import nanomuse.headless.core.AgentLoop
import nanomuse.headless.core.ApprovalGate
import nanomuse.headless.core.ModelProvider
import nanomuse.headless.core.Tool
import nanomuse.headless.core.ToolCall
import nanomuse.headless.core.ToolContext
import nanomuse.headless.guard.ConsoleApprovalGate
import nanomuse.headless.guard.OffloadGate
import nanomuse.headless.host.JsonlSessionStore
import nanomuse.headless.host.LocalProcessSandbox
import nanomuse.headless.host.MultiSessionEventSink
import nanomuse.headless.host.OpenAiCompatibleProvider
import nanomuse.headless.host.SessionWorkspace
import nanomuse.headless.interop.A2AAdapter
import nanomuse.headless.interop.A2ATask
import nanomuse.headless.interop.AppFunctionAdapter
import nanomuse.headless.interop.McpAdapter
import nanomuse.headless.memory.JsonlMemoryStore
import nanomuse.headless.memory.MemoryStore
import nanomuse.headless.router.AgentRequest
import nanomuse.headless.router.FixedNetworkProbe
import nanomuse.headless.router.RootRouter
import nanomuse.headless.router.RouterConfig
import nanomuse.headless.session.AgentTask
import nanomuse.headless.session.ConcurrencyController
import nanomuse.headless.session.SessionManager
import nanomuse.headless.session.SessionState
import nanomuse.headless.session.TaskQueue
import nanomuse.headless.tools.ToolDispatchCenter
import nanomuse.headless.tools.defaultTools
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

object Prompts {
    const val SYSTEM = """
You are a headless agent running on a server. You have a shell and a workspace.

Rules:
- Use shell_execute for anything the file tools cannot do.
- file_read / file_write / file_edit operate inside the workspace only; paths are relative to it.
- read_image / browser_use / memory_write / memory_get / android_calendar / android_notify are also available.
- Do not claim success without checking the result of the command you ran.
- When the task is done, answer with a short summary and stop calling tools.
"""
}

data class RuntimeConfig(
    val autoApprove: Boolean = false,
    /** 强制离线：验证 Router 断网降级（所有任务走 LOCAL）。 */
    val offline: Boolean = System.getenv("NM_OFFLINE").isNullOrBlank().not(),
    val hybridEnabled: Boolean = true,
    val allowOffload: Boolean = false,
    val echoEvents: Boolean = false,
    val maxTurns: Int = 200,
    val maxConcurrentSessions: Int = 4,
    val maxInFlightTasks: Int = 8,
    val baseUrl: String = System.getenv("NM_BASE_URL") ?: "http://127.0.0.1:8787/v1",
    val apiKey: String = System.getenv("NM_API_KEY") ?: "",
    val model: String = System.getenv("NM_MODEL") ?: "smoke-model",
    val busAckTimeoutMs: Long = 3_000,
    val busMaxRetries: Int = 3,
)

data class RuntimeResult(
    val traceId: String,
    val sessionId: String,
    val mode: String,
    val decisionReason: String,
    val stoppedBy: String = "done",
    val turns: Int = 0,
    val text: String = "",
    val toolCalls: Int = 0,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val envelopes: Int = 0,
    val deadLetters: Int = 0,
    val durationMs: Long = 0,
    val ok: Boolean = true,
    val error: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("trace_id", traceId).put("session", sessionId).put("mode", mode)
        .put("decision_reason", decisionReason).put("stopped_by", stoppedBy)
        .put("turns", turns).put("text", text).put("tool_calls", toolCalls)
        .put("prompt_tokens", promptTokens).put("completion_tokens", completionTokens)
        .put("envelopes", envelopes).put("dead_letters", deadLetters)
        .put("duration_ms", durationMs).put("ok", ok)
        .put("error", error ?: JSONObject.NULL)
}

/**
 * 五层装配器 —— 把 RootRouter / CloudAgentBus / SessionManager+TaskQueue /
 * AgentLoop+ContextManager / ToolDispatchCenter+风控+工具 串成一条可运行链路。
 *
 * 三条主链路（DESIGN.md 3）全部在这里落地：
 *  - LOCAL：任务入队 → Looper 本地闭环
 *  - CLOUD：只走总线，本地内核零启动
 *  - HYBRID：云端 TOOL_CALL 下发 → 端侧同一条风控链路执行 → TOOL_RESULT 回传
 */
class NanoMuseRuntime(
    private val root: Path,
    private val config: RuntimeConfig = RuntimeConfig(),
) {
    val events = MultiSessionEventSink(root.resolve("events"), config.echoEvents)
    val store = JsonlSessionStore(root.resolve("sessions"))
    val memory: MemoryStore = JsonlMemoryStore(root.resolve("system/memory.jsonl"))
    val capability: SystemCapability = LocalSystemCapability(root.resolve("system"))
    val approvals: ApprovalGate = ConsoleApprovalGate(autoApprove = config.autoApprove)
    val tools: List<Tool> = defaultTools(root.resolve("system"), memory, capability)
    val offloadGate = OffloadGate(
        tools.map { it.spec.name }.toSet(), config.allowOffload, events,
    )
    val dispatch = ToolDispatchCenter(tools, offloadGate, events)
    val contextManager = ContextManager()
    val sessions = SessionManager(root.resolve("sessions"), events)
    val concurrency = ConcurrencyController(config.maxConcurrentSessions, config.maxInFlightTasks, events)
    val taskQueue = TaskQueue(concurrency, events)
    val provider: ModelProvider = OpenAiCompatibleProvider(config.baseUrl, config.apiKey, config.model)

    private val cloud = SimulatedCloudTransport()
    val bus = CloudAgentBus(
        cloud, events, root.resolve("bus"),
        maxRetries = config.busMaxRetries, ackTimeoutMs = config.busAckTimeoutMs,
    )
    val router = RootRouter(
        probe = if (config.offline) FixedNetworkProbe(false) else nanomuse.headless.router.EnvNetworkProbe,
        config = RouterConfig(hybridEnabled = config.hybridEnabled),
        events = events,
    )
    val mcp = McpAdapter(dispatch)
    val a2a = A2AAdapter(bus)
    val appFunctions = AppFunctionAdapter(dispatch)

    init {
        Files.createDirectories(root)
        // 模拟云端回环：下发信封回投到本地队列，出站信封即时 ACK
        cloud.onDeliver = { bus.deliver(it) }
        cloud.onAck = { bus.onAck(it) }
        System.setProperty("java.awt.headless", "true")
    }

    fun submit(request: AgentRequest, resume: Boolean = false): RuntimeResult {
        val d = router.decide(request)
        val t0 = System.currentTimeMillis()
        return try {
            val r = when (d.mode) {
                nanomuse.headless.router.RouteMode.LOCAL -> runLocal(d, request.rawQuery, resume)
                nanomuse.headless.router.RouteMode.HYBRID -> runHybrid(d, request.rawQuery)
                nanomuse.headless.router.RouteMode.CLOUD -> runCloud(d, request.rawQuery)
            }
            r.copy(durationMs = System.currentTimeMillis() - t0, deadLetters = bus.deadLetters().size)
        } catch (e: Exception) {
            events.emit(
                "error",
                JSONObject().put("session", d.sessionId).put("trace_id", d.traceId)
                    .put("message", e.message ?: e.javaClass.simpleName),
            )
            RuntimeResult(
                d.traceId, d.sessionId, d.mode.name, d.reason,
                stoppedBy = "error", durationMs = System.currentTimeMillis() - t0,
                ok = false, error = "${e.javaClass.simpleName}: ${e.message}",
            )
        }
    }

    // ── 链路 1：LOCAL ────────────────────────────────────────────────────
    private fun runLocal(
        d: nanomuse.headless.router.RouteDecision,
        query: String,
        resume: Boolean,
    ): RuntimeResult {
        val sandbox = LocalProcessSandbox(SessionWorkspace(root.resolve("workspaces"), d.sessionId).dir())
        val ctx = ToolContext(d.sessionId, sandbox, approvals, events, store, d.traceId, fromCloud = false)
        val snap = sessions.open(d.sessionId, d.traceId, "LOCAL", reset = !resume)
        if (!resume || snap.state == SessionState.FINISHED || snap.state == SessionState.ERROR) {
            sessions.transition(d.sessionId, SessionState.RUNNING)
        }
        val loop = AgentLoop(
            provider = provider, tools = tools, sink = events, store = store,
            systemPrompt = Prompts.SYSTEM, maxTurns = config.maxTurns,
            contextManager = contextManager, dispatch = dispatch,
            isCancelled = { taskQueue.isCancelled(it) },
            onTurn = { t -> sessions.checkpoint(d.sessionId, SessionState.RUNNING, t) },
            onState = { s ->
                runCatching {
                    sessions.checkpoint(
                        d.sessionId, SessionState.valueOf(s),
                        sessions.get(d.sessionId)?.turn ?: 0,
                    )
                }
            },
        )
        val task = AgentTask(
            sessionId = d.sessionId, traceId = d.traceId, mode = "LOCAL",
            payload = JSONObject().put("query", query),
        )
        var outcome: AgentLoop.Outcome? = null
        taskQueue.submit(task) { outcome = loop.run(d.sessionId, query, ctx, resume) }
            .get(180, TimeUnit.SECONDS)
        val o = outcome ?: throw IllegalStateException("local task produced no outcome")
        sessions.finish(d.sessionId, o.turns, o.stoppedBy)
        sandbox.close()
        return RuntimeResult(
            d.traceId, d.sessionId, "LOCAL", d.reason,
            stoppedBy = o.stoppedBy, turns = o.turns, text = o.text,
            toolCalls = countToolResults(d.sessionId),
            promptTokens = o.usage.promptTokens, completionTokens = o.usage.completionTokens,
        )
    }

    // ── 链路 2：HYBRID ───────────────────────────────────────────────────
    private fun runHybrid(
        d: nanomuse.headless.router.RouteDecision,
        query: String,
    ): RuntimeResult {
        val sandbox = LocalProcessSandbox(SessionWorkspace(root.resolve("workspaces"), d.sessionId).dir())
        val ctx = ToolContext(d.sessionId, sandbox, approvals, events, store, d.traceId, fromCloud = true)
        sessions.open(d.sessionId, d.traceId, "HYBRID", reset = true)
        sessions.transition(d.sessionId, SessionState.RUNNING)
        sessions.transition(d.sessionId, SessionState.WAIT_CLOUD)

        val sent = bus.send(
            AgentEnvelope(
                traceId = d.traceId, sessionId = d.sessionId,
                direction = EnvelopeDirection.TO_CLOUD, msgType = MsgType.QUERY,
                routerTarget = RouterTarget.HYBRID,
                payload = JSONObject().put("query", query),
            )
        )
        if (!sent.ok) throw IllegalStateException("HYBRID 上送云端失败：${sent.error}")

        var toolCalls = 0
        var envelopes = 0
        var text = ""
        var stopped = "done"
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            val env = bus.poll(d.sessionId, 1_000) ?: continue
            envelopes++
            when (env.msgType) {
                MsgType.TOOL_CALL -> {
                    sessions.checkpoint(d.sessionId, SessionState.TOOL_EXEC, toolCalls + 1)
                    val name = env.payload.optString("tool_name")
                    val args = env.payload.optJSONObject("parameters") ?: JSONObject()
                    val call = ToolCall(
                        id = env.payload.optString("tool_call_id").ifBlank { "cloud_$toolCalls" },
                        name = name, arguments = args.toString(),
                    )
                    val result = dispatch.invokeForBus(call, ctx)
                    toolCalls++
                    val reply = bus.send(
                        AgentEnvelope(
                            traceId = d.traceId, sessionId = d.sessionId,
                            direction = EnvelopeDirection.TO_CLOUD, msgType = MsgType.TOOL_RESULT,
                            routerTarget = RouterTarget.HYBRID,
                            payload = JSONObject()
                                .put("tool_call_id", call.id).put("tool_name", name)
                                .put("result", result),
                        )
                    )
                    if (!reply.ok) {
                        stopped = "bus_dead_letter"
                        break
                    }
                    sessions.checkpoint(d.sessionId, SessionState.WAIT_CLOUD, toolCalls)
                }
                MsgType.STREAM_CHUNK -> text += env.payload.optString("delta")
                MsgType.STATUS -> {
                    text = env.payload.optString("text", text)
                    break
                }
                MsgType.ERROR -> {
                    stopped = "cloud_error"
                    text = env.payload.optString("message")
                    break
                }
                else -> Unit
            }
        }
        sessions.finish(d.sessionId, toolCalls, stopped)
        sandbox.close()
        return RuntimeResult(
            d.traceId, d.sessionId, "HYBRID", d.reason,
            stoppedBy = stopped, turns = toolCalls, text = text,
            toolCalls = toolCalls, envelopes = envelopes,
        )
    }

    // ── 链路 3：CLOUD ────────────────────────────────────────────────────
    private fun runCloud(
        d: nanomuse.headless.router.RouteDecision,
        query: String,
    ): RuntimeResult {
        // 本地内核零启动：不创建 sandbox、不构造 AgentLoop、不入 TaskQueue
        sessions.open(d.sessionId, d.traceId, "CLOUD", reset = true)
        sessions.transition(d.sessionId, SessionState.RUNNING)
        val sent = bus.send(
            AgentEnvelope(
                traceId = d.traceId, sessionId = d.sessionId,
                direction = EnvelopeDirection.TO_CLOUD, msgType = MsgType.QUERY,
                routerTarget = RouterTarget.CLOUD,
                payload = JSONObject().put("query", query),
            )
        )
        if (!sent.ok) throw IllegalStateException("CLOUD 透传失败：${sent.error}")

        val chunks = StringBuilder()
        var envelopes = 0
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            val env = bus.poll(d.sessionId, 1_000) ?: continue
            envelopes++
            when (env.msgType) {
                MsgType.STREAM_CHUNK -> chunks.append(env.payload.optString("delta"))
                MsgType.STATUS -> {
                    sessions.finish(d.sessionId, 0, "done")
                    return RuntimeResult(
                        d.traceId, d.sessionId, "CLOUD", d.reason,
                        text = env.payload.optString("text", chunks.toString()),
                        envelopes = envelopes,
                    )
                }
                else -> Unit
            }
        }
        sessions.finish(d.sessionId, 0, "timeout")
        return RuntimeResult(
            d.traceId, d.sessionId, "CLOUD", d.reason,
            stoppedBy = "timeout", text = chunks.toString(), envelopes = envelopes,
        )
    }

    // ── 互操作便捷入口 ───────────────────────────────────────────────────
    fun mcpHandle(request: JSONObject): JSONObject = mcp.handle(request)

    fun a2aSubmit(task: A2ATask) = a2a.submitRemote(task)

    fun a2aAccept(task: A2ATask): Boolean = a2a.accept(task)

    fun countToolResults(sessionId: String): Int {
        val f = events.fileFor(sessionId)
        if (!Files.exists(f)) return 0
        return Files.readAllLines(f).count { it.contains("\"type\":\"tool_result\"") }
    }

    fun eventLines(sessionId: String): List<JSONObject> {
        val f = events.fileFor(sessionId)
        if (!Files.exists(f)) return emptyList()
        return Files.readAllLines(f).mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
    }

    fun close() {
        taskQueue.shutdown()
        bus.close()
    }
}
