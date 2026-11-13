package nanomuse.headless.router

import nanomuse.headless.core.EventSink
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Layer1 —— RootRouter，全局唯一入口。
 *
 * 对齐 docs/DESIGN.md 6.1：
 *  - 所有请求必须先过 Router，禁止旁路直连 Looper；
 *  - 命中 10 个端侧独占工具时禁止输出 CLOUD；
 *  - 断网强制降级 LOCAL；
 *  - 生成 traceId / sessionId，决策过程落事件流（可观测）。
 *
 * 取舍：方案接口写的是 `Flow<AgentEvent>`，本骨架不引入 kotlinx-coroutines，
 * 流式出口统一走 [EventSink]（`router_decision` / NDJSON），语义等价、可转 SSE。
 */
enum class RouteMode { LOCAL, CLOUD, HYBRID }

/** 10 个 P0 端侧独占工具白名单（DESIGN.md 1.3）。 */
object LocalExclusiveTools {
    val names: Set<String> = linkedSetOf(
        "shell_execute", "file_read", "file_write", "file_edit", "read_image",
        "browser_use", "memory_write", "memory_get", "android_calendar", "android_notify",
    )

    /** 从自然语言 Query 与显式工具声明里识别本地独占工具依赖。 */
    fun hits(query: String, explicit: List<String> = emptyList()): List<String> =
        (names.filter { query.contains(it, ignoreCase = true) } +
                explicit.filter { it in names }).distinct()
}

data class AgentRequest(
    val rawQuery: String,
    val allowLocal: Boolean = true,
    val allowCloud: Boolean = true,
    val preferLocal: Boolean = false,
    val userSLA: String? = null,
    val sessionId: String? = null,
    /** 三方 Agent / A2A / 云端子任务显式声明的工具依赖。 */
    val explicitTools: List<String> = emptyList(),
    /** 云端下发的 TOOL_CALL 子任务：必须走本地风控，不允许再上云。 */
    val fromCloud: Boolean = false,
)

data class RouteDecision(
    val traceId: String,
    val sessionId: String,
    val mode: RouteMode,
    val reason: String,
    val networkAvailable: Boolean,
    val localExclusiveHits: List<String>,
    val decisionMs: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("trace_id", traceId)
        .put("session_id", sessionId)
        .put("mode", mode.name)
        .put("reason", reason)
        .put("network_available", networkAvailable)
        .put("local_exclusive_hits", JSONArray().apply { localExclusiveHits.forEach { put(it) } })
        .put("decision_ms", decisionMs)
}

/** 网络探测：Android 端接 ConnectivityManager，本地用环境变量或固定值。 */
interface NetworkProbe {
    fun isOnline(): Boolean
}

class FixedNetworkProbe(private val online: Boolean) : NetworkProbe {
    override fun isOnline(): Boolean = online
}

/** 默认按 NM_OFFLINE 环境变量判断，便于离线降级冒烟。 */
object EnvNetworkProbe : NetworkProbe {
    override fun isOnline(): Boolean = System.getenv("NM_OFFLINE").isNullOrBlank()
}

/**
 * 可配置路由规则（DESIGN.md 12：路由规则可配置、无需改内核）。
 *
 * @param offlineFallback 断网时强制 LOCAL
 * @param hybridEnabled   HYBRID 开关，关闭后命中独占工具一律 LOCAL
 * @param privacyKeywords 命中这些词的 Query 视为隐私任务，强制 LOCAL
 */
data class RouterConfig(
    val offlineFallback: Boolean = true,
    val hybridEnabled: Boolean = true,
    val privacyKeywords: List<String> = listOf("隐私", "本地", "离线", "不上云", "private", "offline"),
    val exclusiveToolSet: Set<String> = LocalExclusiveTools.names,
)

class RootRouter(
    private val probe: NetworkProbe = EnvNetworkProbe,
    private val config: RouterConfig = RouterConfig(),
    private val events: EventSink? = null,
) {
    /**
     * 决策优先级（与验收标准 14.2 对齐）：
     *  1. 断网 → LOCAL
     *  2. 云端下发子任务 → LOCAL（能力独占 + 同链路风控）
     *  3. 命中独占工具 → HYBRID 或 LOCAL，**绝不 CLOUD**
     *  4. preferLocal / allowCloud=false → LOCAL
     *  5. 其余且 allowCloud → CLOUD
     */
    fun decide(req: AgentRequest): RouteDecision {
        val t0 = System.nanoTime()
        val online = probe.isOnline()
        val hits = (config.exclusiveToolSet.filter {
            req.rawQuery.contains(it, ignoreCase = true)
        } + req.explicitTools.filter { it in config.exclusiveToolSet }).distinct()
        val privacy = config.privacyKeywords.any { req.rawQuery.contains(it, ignoreCase = true) }

        val (mode, reason) = when {
            !online && config.offlineFallback ->
                RouteMode.LOCAL to "断网，自动降级本地执行"
            req.fromCloud ->
                RouteMode.LOCAL to "云端下发的 TOOL_CALL 子任务，端侧独占执行并强制风控"
            hits.isNotEmpty() && privacy ->
                RouteMode.LOCAL to "命中独占工具 [${hits.joinToString(",")}] 且为隐私任务，强制本地"
            hits.isNotEmpty() && config.hybridEnabled && req.allowCloud && !req.preferLocal ->
                RouteMode.HYBRID to "命中独占工具 [${hits.joinToString(",")}]，云端规划 + 端侧执行，禁止 CLOUD"
            hits.isNotEmpty() ->
                RouteMode.LOCAL to "命中独占工具 [${hits.joinToString(",")}]，本地闭环"
            privacy || req.preferLocal ->
                RouteMode.LOCAL to "用户偏好/隐私要求优先本地"
            !req.allowCloud ->
                RouteMode.LOCAL to "调用方禁用云端"
            req.allowCloud ->
                RouteMode.CLOUD to "无本地工具依赖，云端全权，本地内核零启动"
            else ->
                RouteMode.LOCAL to "无可用云端通道，本地兜底"
        }

        val decision = RouteDecision(
            traceId = "tr-" + UUID.randomUUID().toString().replace("-", "").take(20),
            sessionId = req.sessionId?.takeIf { it.isNotBlank() }
                ?: ("s-" + UUID.randomUUID().toString().take(8)),
            mode = mode,
            reason = reason,
            networkAvailable = online,
            localExclusiveHits = hits,
            decisionMs = (System.nanoTime() - t0) / 1_000_000,
        )
        events?.emit("router_decision", decision.toJson().put("query", req.rawQuery))
        return decision
    }
}
