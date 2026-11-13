package nanomuse.headless

import nanomuse.headless.core.*
import nanomuse.headless.guard.ConsoleApprovalGate
import nanomuse.headless.host.*
import nanomuse.headless.tools.defaultTools
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

/**
 * 无界面后台入口。
 *
 *   java -cp out:json.jar nanomuse.headless.MainKt \
 *     --task "把 /tmp/nm/demo/notes.txt 读出来并总结成 3 行" \
 *     --root /tmp/nm \
 *     --session demo-1
 *
 * 模型端点从环境变量取：
 *   NM_BASE_URL (默认 http://127.0.0.1:8787/v1)
 *   NM_API_KEY  (可空)
 *   NM_MODEL    (默认 smoke-model)
 */

private const val SYSTEM_PROMPT = """
You are a headless agent running on a server. You have a shell and a workspace.

Rules:
- Use shell_execute for anything the file tools cannot do.
- file_read / file_write / file_edit operate inside the workspace only; paths are relative to it.
- Do not claim success without checking the result of the command you ran.
- When the task is done, answer with a short summary and stop calling tools.
"""

fun main(args: Array<String>) {
    val opts = parse(args)
    val task = opts["task"] ?: run {
        System.err.println("usage: --task <text> [--root dir] [--session id] [--max-turns N] [--auto-approve]")
        exitProcess(2)
    }
    val root: Path = Paths.get(opts["root"] ?: "./nm-run").toAbsolutePath().normalize()
    val sessionId = opts["session"] ?: "s-" + System.currentTimeMillis()
    Files.createDirectories(root)

    val sessionDir = SessionWorkspace(root.resolve("workspaces"), sessionId).dir()
    val sandbox = LocalProcessSandbox(sessionDir)
    val store = JsonlSessionStore(root.resolve("sessions"))
    val events = JsonlEventSink(root.resolve("events").resolve("$sessionId.ndjson"), echo = true)
    val approvals = ConsoleApprovalGate(autoApprove = opts.containsKey("auto-approve"))
    val provider = OpenAiCompatibleProvider(
        baseUrl = System.getenv("NM_BASE_URL") ?: "http://127.0.0.1:8787/v1",
        apiKey = System.getenv("NM_API_KEY") ?: "",
        model = System.getenv("NM_MODEL") ?: "smoke-model",
    ) { System.err.println(it) }

    val tools = defaultTools()
    val loop = AgentLoop(
        provider = provider,
        tools = tools,
        sink = events,
        store = store,
        systemPrompt = SYSTEM_PROMPT,
        maxTurns = opts["max-turns"]?.toIntOrNull() ?: 200,
    )
    val ctx = ToolContext(sessionId, sandbox, approvals, events, store)

    val started = System.currentTimeMillis()
    val outcome = try {
        loop.run(sessionId, task, ctx)
    } catch (e: Exception) {
        events.emit("error", JSONObject().put("message", e.message ?: e.javaClass.simpleName))
        System.err.println("run failed: ${e.message}")
        null
    }

    if (outcome == null) exitProcess(1)
    println("\n── result ─────────────────────────────")
    println("session   $sessionId")
    println("workspace $sessionDir")
    println("turns     ${outcome.turns}   stopped_by=${outcome.stoppedBy}   ${System.currentTimeMillis() - started}ms")
    println("tokens    prompt=${outcome.usage.promptTokens} completion=${outcome.usage.completionTokens}")
    println(outcome.text.trim())
    sandbox.close()
}

private fun parse(args: Array<String>): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    var i = 0
    while (i < args.size) {
        val a = args[i]
        if (a.startsWith("--")) {
            val k = a.removePrefix("--")
            val next = args.getOrNull(i + 1)
            if (next != null && !next.startsWith("--")) { out[k] = next; i += 2 } else { out[k] = ""; i += 1 }
        } else i += 1
    }
    return out
}
