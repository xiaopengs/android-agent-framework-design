import com.openminis.app.agent.ToolLoopDetector
import com.openminis.app.agent.ToolLoopConfig
import com.openminis.app.agent.Level
import com.openminis.app.data.ContextPolicy

/**
 * Probe harness: prints the ACTUAL runtime behaviour of two pure-logic classes
 * lifted verbatim from the nanoMuse Android source tree.
 *
 * Sources (relative to repo root, commit 067384b):
 *   android/src/android/app/src/main/java/com/openminis/app/agent/ToolLoopDetector.kt
 *   android/src/android/app/src/main/java/com/openminis/app/data/ContextPolicy.kt
 *
 * Only AppLogger is shimmed (it needs android.util.Log). No logic is altered.
 */
fun main() {
    println("=".repeat(78))
    println("PROBE 1 — ToolLoopConfig defaults (ToolLoopDetector.kt:41-47)")
    println("=".repeat(78))
    val c = ToolLoopConfig()
    println("historySize                   = ${c.historySize}")
    println("warningThreshold              = ${c.warningThreshold}")
    println("unknownToolThreshold          = ${c.unknownToolThreshold}")
    println("criticalThreshold             = ${c.criticalThreshold}")
    println("globalCircuitBreakerThreshold = ${c.globalCircuitBreakerThreshold}")

    println()
    println("=".repeat(78))
    println("PROBE 2 — identical file_read repeated: warning cadence + escalation")
    println("=".repeat(78))
    val d = ToolLoopDetector()
    for (i in 1..32) {
        val post = d.record("file_read", mapOf("path" to "/etc/hosts"), result = "same", errorMessage = null)
        if (post.level != Level.NONE) println("call #%-2d -> %-8s %s".format(i, post.level, post.message))
    }

    println()
    println("=".repeat(78))
    println("PROBE 3 — unknown tool repeated (hallucinated tool name)")
    println("=".repeat(78))
    val d2 = ToolLoopDetector()
    for (i in 1..12) {
        val post = d2.record("teleport_user", mapOf("x" to "1"), result = "Unknown tool: teleport_user", errorMessage = "unknown")
        if (post.level != Level.NONE) println("call #%-2d -> %-8s %s".format(i, post.level, post.message))
    }

    println()
    println("=".repeat(78))
    println("PROBE 4 — varying args: global breaker is check()-only (pre-execution)")
    println("=".repeat(78))
    println("record() alone never emits CRITICAL — by spec (ToolLoopDetector.kt:163-167).")
    println("Below we call check() BEFORE record(), the way the agent loop does.")
    val d3 = ToolLoopDetector()
    for (i in 1..32) {
        val pre = d3.check("custom_widget", mapOf("n" to i.toString()))
        if (pre.level != Level.NONE) println("PRE  call #%-2d -> %-8s %s".format(i, pre.level, pre.message))
        val post = d3.record("custom_widget", mapOf("n" to i.toString()), result = "r$i", errorMessage = null)
        if (post.level != Level.NONE) println("POST call #%-2d -> %-8s %s".format(i, post.level, post.message))
    }

    println()
    println("=".repeat(78))
    println("PROBE 4b — unknown tool: CRITICAL comes from check(), not record()")
    println("=".repeat(78))
    val d4 = ToolLoopDetector()
    for (i in 1..12) {
        val pre = d4.check("teleport_user", mapOf("x" to "1"))
        if (pre.level != Level.NONE) println("PRE  call #%-2d -> %-8s %s".format(i, pre.level, pre.message))
        d4.record("teleport_user", mapOf("x" to "1"), result = null, errorMessage = "Unknown tool: teleport_user")
    }

    println()
    println("=".repeat(78))
    println("PROBE 5 — ContextPolicy.forContextWindow: all four tiers")
    println("=".repeat(78))
    println("%-9s %-9s %-9s %-9s %-13s %s".format("window", "offload", "offTgt", "compact", "exhaustedOnly", "manualCompact"))
    for (w in listOf(16_000, 31_999, 32_000, 48_000, 63_999, 64_000, 100_000, 127_999, 128_000, 200_000, 1_000_000)) {
        val p = ContextPolicy.forContextWindow(w)
        println("%-9d %-9d %-9d %-9d %-13s %s".format(w, p.offloadThreshold, p.offloadTarget, p.compactThreshold, p.exhaustedOnly, p.manualCompactAllowed))
    }

    println()
    println("=".repeat(78))
    println("PROBE 6 — ContextPolicy.check: EXHAUSTED can only come from exhaustedOnly tier")
    println("=".repeat(78))
    for (w in listOf(48_000, 200_000)) {
        val p = ContextPolicy.forContextWindow(w)
        println("window=$w compactThreshold=${p.compactThreshold} exhaustedOnly=${p.exhaustedOnly}")
        for (t in listOf(1_000, p.offloadThreshold, p.compactThreshold, w - 1, w, w + 5_000)) {
            println("   tokens=%-8d -> %s".format(t, p.check(t, w)))
        }
    }
}