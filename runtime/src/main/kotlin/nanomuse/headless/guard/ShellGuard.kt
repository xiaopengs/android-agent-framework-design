package nanomuse.headless.guard

import nanomuse.headless.core.*

/**
 * 静态闸 —— 对应 Android 端 guard/ShellGuard.kt 的"四问"。
 *
 * 原实现会把命令按 ; && || 换行拆管道，再剥 sudo/env/timeout 外壳，递归看 sh -c / python -c / heredoc。
 * 这里保留判定口径（删 / 发 / 付 / 装），规则表更短，方便替换成自己的策略。
 */
object ShellGuard {
    private val destructive = listOf(
        Regex("""\brm\s+-\w*(r|f)""", RegexOption.IGNORE_CASE),
        Regex("""\bmkfs\b""", RegexOption.IGNORE_CASE),
        Regex("""\bdd\s+""", RegexOption.IGNORE_CASE),
        Regex("""\btruncate\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(shutdown|reboot|halt)\b""", RegexOption.IGNORE_CASE),
        Regex(""">\s*/dev/sd""", RegexOption.IGNORE_CASE),
        Regex("""\bchmod\s+-R\s+777\b""", RegexOption.IGNORE_CASE),
    )
    private val outbound = listOf(
        Regex("""\b(curl|wget|nc|ncat|ssh|scp|rsync)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bgit\s+push\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(sendmail|mail\s+-s)\b""", RegexOption.IGNORE_CASE),
    )
    private val install = listOf(
        Regex("""\b(apt|apt-get|yum|dnf)\s+(install|-i)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bpip3?\s+install\b""", RegexOption.IGNORE_CASE),
        Regex("""\bnpm\s+(i|install)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bapk\s+add\b""", RegexOption.IGNORE_CASE),
        Regex("""\bbrew\s+install\b""", RegexOption.IGNORE_CASE),
    )
    private val money = listOf(
        Regex("""\b(stripe|paypal|alipay|wechat)\b.*\b(charge|pay|transfer)\b""", RegexOption.IGNORE_CASE),
    )

    data class Verdict(val risk: RiskClass, val reason: String) {
        val gated: Boolean get() = risk != RiskClass.SAFE
    }

    fun inspect(command: String): Verdict {
        val cmd = command.replace("\\\n", " ")
        return when {
            money.any { it.containsMatchIn(cmd) } -> Verdict(RiskClass.MONEY, "涉及支付动作")
            destructive.any { it.containsMatchIn(cmd) } -> Verdict(RiskClass.DESTRUCTIVE, "会删除或破坏数据")
            outbound.any { it.containsMatchIn(cmd) } -> Verdict(RiskClass.OUTBOUND, "会把数据发到外部")
            install.any { it.containsMatchIn(cmd) } -> Verdict(RiskClass.INSTALL, "会改变运行环境（安装）")
            else -> Verdict(RiskClass.SAFE, "只读或本地内操作")
        }
    }
}

/**
 * 命令行审批 —— 对应 RiskGate 的卡片。
 * 无人回应按拒绝（Android 端 180s），[autoApprove] 只用于冒烟。
 */
class ConsoleApprovalGate(
    private val timeoutMs: Long = 180_000,
    private val autoApprove: Boolean = false,
    private val log: (String) -> Unit = { System.err.println(it) },
) : ApprovalGate {
    override fun ask(req: ApprovalRequest): ApprovalOutcome {
        if (autoApprove) return ApprovalOutcome.ALLOW_ONCE
        log("[approval] ${req.risk} — ${req.reason}\n           ${req.preview}\n           [a]llow / [s]ession / [n]o ?")
        val line = readLineWithTimeout(timeoutMs) ?: return ApprovalOutcome.TIMEOUT
        return when (line.trim().lowercase()) {
            "a", "y", "yes", "" -> ApprovalOutcome.ALLOW_ONCE
            "s", "session" -> ApprovalOutcome.ALLOW_SESSION
            else -> ApprovalOutcome.DENY
        }
    }

    private fun readLineWithTimeout(ms: Long): String? {
        val future = java.util.concurrent.CompletableFuture<String>()
        val t = Thread {
            runCatching { future.complete(readLine()) }
        }
        t.isDaemon = true
        t.start()
        return runCatching { future.get(ms, java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrNull()
    }
}
