package nanomuse.headless.core

import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path

/**
 * 与平台无关的契约层。
 *
 * 这里的每个接口，在 Android 端都对应一个具体实现（见报告 M16 的映射表）：
 *  - [Sandbox]        ← sandbox/PRootKernel.kt + PersistentShell.kt + ExecutionCoordinator.kt
 *  - [ApprovalGate]   ← guard/ShellGuard.kt + RiskGate.kt + Grants.kt
 *  - [EventSink]      ← ui/chat/ChatViewModel 的流式回调 + hub 的事件流
 *  - [SessionStore]   ← data/repository/ChatRepository.kt（SQLite）
 *  - [ModelProvider]  ← provider/…（SSE 增量 + tool_call 拼装）
 *  - [Tool]           ← tools/…（8 个内置工具）
 *
 * 换宿主时只需要换这些实现，[AgentLoop] 与工具本身不动。
 */

data class Message(
    val role: String,               // system / user / assistant / tool
    val content: String? = null,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
    val name: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("role", role)
        content?.let { put("content", it) }
        if (toolCalls.isNotEmpty()) {
            put("tool_calls", JSONArray().apply {
                toolCalls.forEach { put(it.toJson()) }
            })
        }
        toolCallId?.let { put("tool_call_id", it) }
        name?.let { put("name", it) }
    }
}

data class ToolCall(val id: String, val name: String, val arguments: String) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", "function")
        put("function", JSONObject().put("name", name).put("arguments", arguments))
    }
}

data class ToolSpec(
    val name: String,
    val description: String,
    val parameters: JSONObject,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", "function")
        put("function", JSONObject().apply {
            put("name", name)
            put("description", description)
            put("parameters", parameters)
        })
    }
}

data class Usage(val promptTokens: Int = 0, val completionTokens: Int = 0)

data class ModelReply(
    val text: String?,
    val toolCalls: List<ToolCall>,
    val usage: Usage = Usage(),
    val stopReason: String? = null,
)

interface ModelProvider {
    /** 一轮模型调用。流式文本通过 [onText] 增量吐出，返回值里带这一轮的 tool_call。 */
    fun complete(
        system: String,
        messages: List<Message>,
        tools: List<ToolSpec>,
        onText: (String) -> Unit,
    ): ModelReply
}

data class ExecResult(val exitCode: Int, val stdout: String, val stderr: String, val durationMs: Long) {
    val ok: Boolean get() = exitCode == 0
}

interface Sandbox {
    /**
     * 执行一条命令。[cwd] 相对工作根目录。
     * Android 实现是 proot + 该会话的持久 shell；本地实现是宿主进程。
     */
    fun exec(argv: List<String>, cwd: String?, timeoutMs: Long, env: Map<String, String> = emptyMap()): ExecResult

    /** 宿主侧的文件根，工具的读写都落在这里。 */
    val workspace: Path

    fun close() {}
}

enum class RiskClass { SAFE, INSTALL, DESTRUCTIVE, OUTBOUND, REMOTE, MONEY }
enum class ApprovalOutcome { ALLOW_ONCE, ALLOW_SESSION, ALLOW_ALWAYS, DENY, TIMEOUT }

data class ApprovalRequest(
    val sessionId: String,
    val risk: RiskClass,
    val reason: String,
    val preview: String,
)

interface ApprovalGate {
    /** 阻塞到有人回答或超时。超时按拒绝处理（Android 端是 180s）。 */
    fun ask(req: ApprovalRequest): ApprovalOutcome
}

interface EventSink {
    fun emit(type: String, payload: JSONObject)
}

interface SessionStore {
    fun append(sessionId: String, message: Message)
    fun history(sessionId: String): List<Message>
    fun saveTurn(sessionId: String, turn: Int, status: String)
}

/** 工具执行时能拿到的一切。traceId / fromCloud 为 V1.2 新增，末尾带默认值保持旧装配兼容。 */
data class ToolContext(
    val sessionId: String,
    val sandbox: Sandbox,
    val approvals: ApprovalGate,
    val events: EventSink,
    val store: SessionStore,
    /** 全链路追踪 ID（DESIGN.md 1.5 可观测原则）。 */
    val traceId: String = "",
    /** 该调用是否由云端 TOOL_CALL 下发：必须与本地触发走同一条风控链路。 */
    val fromCloud: Boolean = false,
)

/**
 * 统一工具返回。前两个字段保持 V1.1 兼容；code / riskBlock / riskMsg 对齐 DESIGN.md 6.3。
 */
data class ToolResult(
    val content: String,
    val isError: Boolean = false,
    val code: Int = 0,
    val riskBlock: Boolean = false,
    val riskMsg: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("content", content)
        put("is_error", isError)
        put("code", code)
        put("risk_block", riskBlock)
        put("risk_msg", riskMsg)
    }

    /** DESIGN.md 6.3 / 第 7 章的标准化出参，供总线回传云端与 MCP 响应使用。 */
    fun toProtocolJson(toolName: String, sessionId: String, elapsedMs: Long): JSONObject = JSONObject()
        .put("success", !isError)
        .put("code", if (isError && code == 0) -1 else code)
        .put("msg", if (isError) content.take(300) else "ok")
        .put("data", JSONObject().put("tool_name", toolName).put("content", content)
            .put("elapsed_ms", elapsedMs))
        .put("risk_block", riskBlock)
        .put("risk_msg", riskMsg)
        .put("session_id", sessionId)
}

interface Tool {
    val spec: ToolSpec
    fun execute(args: JSONObject, ctx: ToolContext): ToolResult
}

object Json {
    fun obj(s: String?): JSONObject = if (s.isNullOrBlank()) JSONObject() else JSONObject(s)
    fun arr(s: String?): JSONArray = if (s.isNullOrBlank()) JSONArray() else JSONArray(s)
    fun readFile(p: Path): String = String(Files.readAllBytes(p), Charsets.UTF_8)
}
