// Extra experiments by Researcher #2 (Looper chapter). Does NOT modify anything
// in /data/kb/verify/src. Compile with `-cp out` so ToolLoopDetector comes from
// the coordinator's byte-for-byte build.
import com.openminis.app.agent.Level
import com.openminis.app.agent.ToolLoopDetector
import com.openminis.app.agent.InterruptedTailDetector
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import io.github.nanomuse.sync.RemoteRows

private fun line(s: String) = println(s)

/** Mimics ChatViewModel's per-tool ordering: check() -> (execute) -> record(). */
private fun runLoop(
    label: String,
    n: Int,
    tool: String = "file_read",
    paramsOf: (Int) -> Map<String, Any?>,
    resultOf: (Int) -> String = { "same output" },
    callCheck: Boolean = true,
) {
    val d = ToolLoopDetector()
    line("=== $label (check()=${callCheck}) ===")
    for (i in 1..n) {
        val p = paramsOf(i)
        if (callCheck) {
            val pre = d.check(tool, p)
            if (pre.level == Level.CRITICAL) {
                line("  #$i CHECK CRITICAL key=${pre.warningKey} msg=${pre.message}")
                // VM records the blocked attempt too (CVM:9161) and skips execution.
                d.record(tool, p, result = null, errorMessage = pre.message, toolCallId = "id$i")
                continue
            }
        }
        val post = d.record(tool, p, result = resultOf(i), toolCallId = "id$i")
        if (post.level != Level.NONE) {
            line("  #$i RECORD ${post.level} key=${post.warningKey} msg=${post.message}")
        }
    }
}

fun main() {
    // E1: only tool_title varies -> must still be treated as identical args.
    runLoop("E1 tool_title-only variation", 32, paramsOf = { i ->
        mapOf("path" to "/tmp/a.txt", "tool_title" to "Read a.txt #$i")
    })

    // E2: control — a real param varies, results identical.
    runLoop("E2 real param varies, result frozen", 32, paramsOf = { i ->
        mapOf("path" to "/tmp/a$i.txt", "tool_title" to "Read #$i")
    })

    // E3: identical args AND identical result, but caller forgets check().
    runLoop("E3 identical args, NO check() hook", 32,
        paramsOf = { mapOf("path" to "/tmp/a.txt") }, callCheck = false)

    // E4: hallucinated tool name, error string parsed by extractUnknownToolName.
    runLoop("E4 unknown tool", 14, tool = "teleport_user",
        paramsOf = { mapOf("dest" to "mars") },
        resultOf = { "Unknown tool: teleport_user" })

    // E5: poll tool with frozen result vs non-poll tool, same shape.
    runLoop("E5 poll tool command_status", 24, tool = "command_status",
        paramsOf = { mapOf("id" to "p1") }, resultOf = { "running" })
    runLoop("E6 process action=log", 24, tool = "process",
        paramsOf = { mapOf("action" to "log", "id" to "p1") }, resultOf = { "running" })
    runLoop("E7 process action=kill (NOT poll)", 24, tool = "process",
        paramsOf = { mapOf("action" to "kill", "id" to "p1") }, resultOf = { "running" })

    // E8: InterruptedTailDetector remote-row exemption.
    line("=== E8 InterruptedTailDetector remote-row exemption ===")
    val tail = listOf(
        LLMMessage(role = LLMMessage.Role.USER, content = "go", contentParts = listOf(AgentContentPart.Text("go"))),
        LLMMessage(role = LLMMessage.Role.ASSISTANT, content = "", contentParts = listOf(
            AgentContentPart.ToolUse(id = "t1", name = "shell_execute", input = org.json.JSONObject())
        ), dbMessageId = "row9"),
    )
    line("  local tail  -> " + InterruptedTailDetector.detect(tail))
    RemoteRows.remoteIds += "row9"
    line("  remote tail -> " + InterruptedTailDetector.detect(tail))
    RemoteRows.remoteIds.clear()
    line("  '#' suffix id (row9#2) treated as same row -> " +
        InterruptedTailDetector.detect(listOf(tail[0], tail[1].copy(dbMessageId = "row9#2"))))
}