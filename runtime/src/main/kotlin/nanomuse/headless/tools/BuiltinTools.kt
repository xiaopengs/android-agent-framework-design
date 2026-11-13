package nanomuse.headless.tools

import nanomuse.headless.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * 内置工具 —— 对应 Android 端 tools/…kt。
 * 只带 4 个最必要的：shell / 读 / 写 / 改。原来的 read_image、browser_use、memory_* 都依赖平台，
 * 在纯后台形态里可以按需接回来（接口一致，加一个 Tool 实现即可）。
 */

private fun resolve(ctx: ToolContext, raw: String): Path {
    val ws = ctx.sandbox.workspace.toAbsolutePath().normalize()
    val p = ws.resolve(raw).normalize()
    require(p.startsWith(ws)) { "path escapes workspace: $raw" }
    return p
}

class ShellTool(private val defaultTimeoutMs: Long = 900_000) : Tool {
    override val spec = ToolSpec(
        name = "shell_execute",
        description = "在工作目录里跑一条 shell 命令（/bin/sh -c），stdout 与 stderr 合并返回。用于列目录、跑脚本、调 CLI。",
        parameters = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject()
                .put("command", JSONObject().put("type", "string").put("description", "要执行的命令"))
                .put("timeout_sec", JSONObject().put("type", "integer").put("description", "超时秒数，默认 900")))
            .put("required", JSONArray().put("command")),
    )

    override fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val command = args.optString("command").trim()
        if (command.isEmpty()) return ToolResult("Error: command is required.", true)
        val timeout = args.optLong("timeout_sec", defaultTimeoutMs / 1000).coerceIn(1, 3600) * 1000

        val verdict = nanomuse.headless.guard.ShellGuard.inspect(command)
        if (verdict.gated) {
            val outcome = ctx.approvals.ask(
                ApprovalRequest(ctx.sessionId, verdict.risk, verdict.reason, command.take(300)),
            )
            ctx.events.emit(
                "approval",
                JSONObject().put("risk", verdict.risk.name).put("outcome", outcome.name),
            )
            if (outcome == ApprovalOutcome.DENY || outcome == ApprovalOutcome.TIMEOUT) {
                return ToolResult("Error: the user did not allow this command (${verdict.reason}).", true)
            }
        }

        val r = ctx.sandbox.exec(listOf("/bin/sh", "-c", command), cwd = null, timeoutMs = timeout)
        val body = buildString {
            if (r.stdout.isNotBlank()) append(r.stdout.trimEnd())
            if (r.stderr.isNotBlank()) {
                if (isNotEmpty()) append("\n")
                append("[stderr]\n").append(r.stderr.trimEnd())
            }
            if (isEmpty()) append("(no output)")
            append("\n[exit ").append(r.exitCode).append(" in ").append(r.durationMs).append("ms]")
        }
        return ToolResult(body, isError = !r.ok)
    }
}

class FileReadTool : Tool {
    override val spec = ToolSpec(
        name = "file_read",
        description = "读取工作目录里的一个文本文件。返回带行号的内容。",
        parameters = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject()
                .put("path", JSONObject().put("type", "string").put("description", "相对工作目录的路径"))
                .put("offset", JSONObject().put("type", "integer").put("description", "从第几行开始，1 起"))
                .put("limit", JSONObject().put("type", "integer").put("description", "最多读多少行，默认 500")))
            .put("required", JSONArray().put("path")),
    )

    override fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val p = resolve(ctx, args.optString("path"))
        if (!Files.exists(p)) return ToolResult("Error: no such file: ${args.optString("path")}", true)
        if (Files.size(p) > 2_000_000) return ToolResult("Error: file too large (use shell to tail it).", true)
        val lines = Files.readAllLines(p)
        val offset = args.optInt("offset", 1).coerceAtLeast(1)
        val limit = args.optInt("limit", 500).coerceIn(1, 5000)
        val slice = lines.drop(offset - 1).take(limit)
        val text = slice.mapIndexed { i, l -> "${offset + i}\t$l" }.joinToString("\n")
        return ToolResult("${p.fileName} · ${lines.size} lines\n$text")
    }
}

class FileWriteTool : Tool {
    override val spec = ToolSpec(
        name = "file_write",
        description = "新建或整体覆盖一个文本文件（原子写）。改已有文件请用 file_edit。",
        parameters = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject()
                .put("path", JSONObject().put("type", "string"))
                .put("content", JSONObject().put("type", "string")))
            .put("required", JSONArray().put("path").put("content")),
    )

    override fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val p = resolve(ctx, args.optString("path"))
        if (!args.has("content")) return ToolResult("Error: content is required.", true)
        val content = args.optString("content")
        Files.createDirectories(p.parent)
        val tmp = Files.createTempFile(p.parent, ".nm-", ".tmp")
        Files.write(tmp, content.toByteArray(Charsets.UTF_8))
        Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        return ToolResult("wrote ${content.toByteArray().size} bytes to ${p.fileName}")
    }
}

class FileEditTool : Tool {
    override val spec = ToolSpec(
        name = "file_edit",
        description = "在文件里替换一段唯一出现的文本；找不到或有歧义就报错，不做模糊匹配。",
        parameters = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject()
                .put("path", JSONObject().put("type", "string"))
                .put("old", JSONObject().put("type", "string"))
                .put("new", JSONObject().put("type", "string")))
            .put("required", JSONArray().put("path").put("old").put("new")),
    )

    override fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val p = resolve(ctx, args.optString("path"))
        if (!Files.exists(p)) return ToolResult("Error: no such file: ${args.optString("path")}", true)
        val old = args.optString("old")
        val new = args.optString("new")
        if (old.isEmpty()) return ToolResult("Error: old is empty.", true)
        val text = Files.readString(p)
        val count = Regex(Regex.escape(old)).findAll(text).count()
        if (count == 0) return ToolResult("Error: old text not found.", true)
        if (count > 1) return ToolResult("Error: old text appears $count times; make it unique.", true)
        Files.writeString(p, text.replaceFirst(old, new))
        return ToolResult("edited ${p.fileName}")
    }
}

fun defaultTools(): List<Tool> = listOf(ShellTool(), FileReadTool(), FileWriteTool(), FileEditTool())
