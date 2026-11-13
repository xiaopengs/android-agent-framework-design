package nanomuse.headless.host

import nanomuse.headless.core.*
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * 宿主沙箱：用本地进程替代 proot + Alpine。
 *
 * Android 端一个会话一条 /bin/sh，靠结束标记取 stdout；这里是同构的：
 * 每次 exec 一个进程，超时强杀，输出与退出码原样带回。
 * 差别只在隔离强度 —— 这一层是可替换的，见报告 M16。
 */
class LocalProcessSandbox(
    override val workspace: Path,
    private val shell: String = System.getenv("NM_SHELL") ?: "/bin/sh",
    private val baseEnv: Map<String, String> = mapOf(
        "PATH" to (System.getenv("PATH") ?: "/usr/local/bin:/usr/bin:/bin"),
        "HOME" to System.getProperty("user.home"),
        "LANG" to "C.UTF-8",
    ),
) : Sandbox {

    override fun exec(
        argv: List<String>,
        cwd: String?,
        timeoutMs: Long,
        env: Map<String, String>,
    ): ExecResult {
        val dir = workspace.resolve(cwd ?: ".").toFile()
        if (!dir.exists()) dir.mkdirs()
        val pb = ProcessBuilder(argv)
            .directory(dir)
            .redirectErrorStream(false)
        pb.environment().clear()
        pb.environment().putAll(baseEnv)
        pb.environment().putAll(env)
        pb.environment()["NM_WORKSPACE"] = workspace.toAbsolutePath().toString()

        val started = System.currentTimeMillis()
        val proc = pb.start()
        val out = StringBuilder()
        val err = StringBuilder()
        val tOut = Thread { out.append(proc.inputStream.bufferedReader().readText()) }
        val tErr = Thread { err.append(proc.errorStream.bufferedReader().readText()) }
        tOut.start(); tErr.start()

        val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            proc.destroyForcibly()
            tOut.join(2000); tErr.join(2000)
            return ExecResult(
                exitCode = 124,
                stdout = out.toString().take(20000),
                stderr = (err.toString() + "\n[timeout after ${timeoutMs}ms]").trim(),
                durationMs = System.currentTimeMillis() - started,
            )
        }
        tOut.join(5000); tErr.join(5000)
        return ExecResult(
            exitCode = proc.exitValue(),
            stdout = out.toString().take(20000),
            stderr = err.toString().take(8000),
            durationMs = System.currentTimeMillis() - started,
        )
    }
}

/** 与会话绑定的工作目录：workspace/<sessionId>/，对应 /var/minis/workspace 的会话隔离。 */
class SessionWorkspace(private val root: Path, private val sessionId: String) {
    fun dir(): Path {
        val d = root.resolve(sessionId.replace(Regex("[^A-Za-z0-9_-]"), "_"))
        if (!Files.exists(d)) Files.createDirectories(d)
        return d
    }
}
