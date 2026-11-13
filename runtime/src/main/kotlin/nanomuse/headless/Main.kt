package nanomuse.headless

import nanomuse.headless.router.AgentRequest
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

/**
 * 无界面后台入口（V1.2）。
 *
 *   # LOCAL 冒烟（配合 tools/fake_model.py）
 *   java -cp out/nm-headless.jar:libs/json.jar nanomuse.headless.MainKt \
 *     --task "写一个 hello.txt 再读回来" --root ./nm-run --session smoke-1 --auto-approve
 *
 *   # HYBRID 端云协同闭环（模拟云端下发 TOOL_CALL，不需要真模型）
 *   java -cp out/nm-headless.jar:libs/json.jar nanomuse.headless.MainKt \
 *     --task "端云协同跑一遍" --mode hybrid --root ./nm-run --session hybrid-1
 *
 *   # CLOUD 全权（本地内核零启动）
 *   java -cp out/nm-headless.jar:libs/json.jar nanomuse.headless.MainKt \
 *     --task "介绍一下端云协同" --mode cloud --root ./nm-run
 *
 *   # 内置自检（不连模型端点）
 *   java -cp out/nm-headless.jar:libs/json.jar nanomuse.headless.MainKt --self-test --root ./nm-run
 *
 * 模型端点：NM_BASE_URL（默认 http://127.0.0.1:8787/v1）/ NM_API_KEY / NM_MODEL
 * 离线降级：NM_OFFLINE=1 或 --offline
 *
 * 注意：--mode 只是设置请求偏好，最终模式仍由 RootRouter 决策，禁止旁路。
 */
fun main(args: Array<String>) {
    val opts = parse(args)
    val root: Path = Paths.get(opts["root"] ?: "./nm-run").toAbsolutePath().normalize()

    if (opts.containsKey("self-test")) {
        val report = SelfTest.run(root.resolve("selftest"))
        println(report.toString(2))
        exitProcess(if (report.optBoolean("all_passed", false)) 0 else 1)
    }

    val task = opts["task"] ?: run {
        System.err.println(
            "usage: --task <text> [--mode auto|local|cloud|hybrid] [--root dir] [--session id] " +
                    "[--max-turns N] [--auto-approve] [--offline] [--resume] [--echo-events] [--json] [--self-test]",
        )
        exitProcess(2)
    }

    val mode = opts["mode"] ?: "auto"
    require(mode in setOf("auto", "local", "cloud", "hybrid")) { "invalid --mode: $mode" }
    val config = RuntimeConfig(
        autoApprove = opts.containsKey("auto-approve"),
        offline = opts.containsKey("offline") || !System.getenv("NM_OFFLINE").isNullOrBlank(),
        echoEvents = opts.containsKey("echo-events"),
        maxTurns = opts["max-turns"]?.toIntOrNull() ?: 200,
    )
    val rt = NanoMuseRuntime(root, config)
    val request = AgentRequest(
        rawQuery = task,
        sessionId = opts["session"],
        preferLocal = mode == "local",
        allowCloud = mode != "local",
        // hybrid 只是把"有本地独占工具依赖"这一事实告诉 Router，模式仍由 Router 判定
        explicitTools = if (mode == "hybrid") listOf("file_write") else emptyList(),
    )

    val result = rt.submit(request, resume = opts.containsKey("resume"))
    if (opts.containsKey("json")) {
        println(result.toJson().toString(2))
    } else {
        println("\n── result ─────────────────────────────")
        println("trace     ${result.traceId}")
        println("session   ${result.sessionId}")
        println("mode      ${result.mode}   ${result.decisionReason}")
        println(
            "turns     ${result.turns}   stopped_by=${result.stoppedBy}   " +
                    "tool_calls=${result.toolCalls}   envelopes=${result.envelopes}   ${result.durationMs}ms",
        )
        println("tokens    prompt=${result.promptTokens} completion=${result.completionTokens}")
        println("events    ${root.resolve("events").resolve(result.sessionId + ".ndjson")}")
        if (result.error != null) println("error     ${result.error}")
        println(result.text.trim())
    }
    rt.close()
    if (!result.ok) exitProcess(1)
}

private fun parse(args: Array<String>): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    var i = 0
    while (i < args.size) {
        val a = args[i]
        if (a.startsWith("--")) {
            val k = a.removePrefix("--")
            val next = args.getOrNull(i + 1)
            if (next != null && !next.startsWith("--")) {
                out[k] = next; i += 2
            } else {
                out[k] = ""; i += 1
            }
        } else i += 1
    }
    return out
}
