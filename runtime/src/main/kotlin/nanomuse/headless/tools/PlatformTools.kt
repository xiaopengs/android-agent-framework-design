package nanomuse.headless.tools

import nanomuse.headless.capability.LocalSystemCapability
import nanomuse.headless.capability.SystemCapability
import nanomuse.headless.core.*
import nanomuse.headless.memory.JsonlMemoryStore
import nanomuse.headless.memory.MemoryEntry
import nanomuse.headless.memory.MemoryStore
import org.json.JSONArray
import org.json.JSONObject
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

/**
 * V1.2 补齐的 6 个 P0 平台工具（DESIGN.md 第 7 章）：
 *  read_image / browser_use / memory_write / memory_get / android_calendar / android_notify
 *
 * 平台差异全部收在 MemoryStore 与 SystemCapability 两个接口后面；Android 移植时
 * 只需替换这两个实现，工具本身与 AgentLoop 不动（见 runtime/README.md 第五节）。
 */

/** 与 BuiltinTools 同口径的工作目录路径校验。 */
internal fun resolveInWorkspace(ctx: ToolContext, raw: String): Path {
    require(raw.isNotBlank()) { "path is required" }
    val ws = ctx.sandbox.workspace.toAbsolutePath().normalize()
    val p = ws.resolve(raw).normalize()
    require(p.startsWith(ws)) { "path escapes workspace: $raw" }
    return p
}

private fun params(block: JSONObject.() -> Unit): JSONObject =
    JSONObject().put("type", "object").put("properties", JSONObject().apply(block))

private fun str(desc: String): JSONObject = JSONObject().put("type", "string").put("description", desc)
private fun int(desc: String): JSONObject = JSONObject().put("type", "integer").put("description", desc)
private fun bool(desc: String): JSONObject = JSONObject().put("type", "boolean").put("description", desc)

/** 5. read_image —— 图片解析、元数据、缩略图与 Base64 输出，含大图限流与隐私文件拦截。 */
class ReadImageTool(
    private val maxFileBytes: Long = 8_000_000,
    private val maxPixels: Int = 8_000,
) : Tool {
    override val spec = ToolSpec(
        name = "read_image",
        description = "读取工作目录内的图片，返回格式/尺寸/元数据，可输出缩略图 Base64。大图与隐私路径会被拦截。",
        parameters = params {
            put("path", str("相对工作目录的图片路径"))
            put("return_base64", bool("是否返回缩略图 PNG 的 Base64，默认 false"))
            put("thumbnail_w", int("缩略图宽度，默认 256，范围 16-1024"))
        }.put("required", JSONArray().put("path")),
    )

    private val privateHints = listOf(
        "/.ssh/", "/android/data/", "/passwd", "/shadow", "/keystore", "/credentials",
        "/.aws/", "/.config/gcloud/", "id_rsa", ".jks", ".p12",
    )

    override fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val raw = args.optString("path")
        if (privateHints.any { raw.lowercase().contains(it) }) {
            return ToolResult(
                "Error: 隐私/凭据路径禁止读取：$raw", isError = true,
                riskBlock = true, riskMsg = "RISK_BLOCK",
            )
        }
        val p = runCatching { resolveInWorkspace(ctx, raw) }.getOrElse {
            return ToolResult("Error: ${it.message}", isError = true)
        }
        if (!Files.exists(p) || !Files.isRegularFile(p))
            return ToolResult("Error: no such image: $raw", isError = true)
        if (Files.size(p) > maxFileBytes)
            return ToolResult(
                "Error: image too large (${Files.size(p)} > $maxFileBytes bytes); 大图限流",
                isError = true, riskBlock = true, riskMsg = "RISK_BLOCK",
            )

        val img: BufferedImage = runCatching { ImageIO.read(p.toFile()) }.getOrNull()
            ?: return ToolResult("Error: unsupported or corrupt image format: $raw", isError = true)
        if (img.width > maxPixels || img.height > maxPixels)
            return ToolResult(
                "Error: image dimensions ${img.width}x${img.height} exceed $maxPixels px",
                isError = true, riskBlock = true, riskMsg = "RISK_BLOCK",
            )

        val sb = StringBuilder()
            .append("file       ").append(p.fileName).append('\n')
            .append("format     ").append(detectFormat(p)).append('\n')
            .append("size       ").append(img.width).append("x").append(img.height).append('\n')
            .append("bytes      ").append(Files.size(p)).append('\n')

        if (args.optBoolean("return_base64", false)) {
            val w = args.optInt("thumbnail_w", 256).coerceIn(16, 1024)
            val h = (img.height.toDouble() * w / img.width).toInt().coerceAtLeast(1)
            val thumb = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            val g = thumb.createGraphics()
            g.drawImage(img, 0, 0, w, h, null)
            g.dispose()
            val buf = ByteArrayOutputStream()
            ImageIO.write(thumb, "png", buf)
            val b64 = Base64.getEncoder().encodeToString(buf.toByteArray())
            sb.append("thumbnail  ").append(w).append("x").append(h).append(" png\n")
                .append("base64_len ").append(b64.length).append('\n')
                .append("base64     ").append(b64)
        } else {
            sb.append("tip        传 return_base64=true 可取得缩略图 Base64")
        }
        return ToolResult(sb.toString())
    }

    private fun detectFormat(p: Path): String {
        val head = Files.newInputStream(p).use { s -> ByteArray(12).also { s.read(it) } }
        return when {
            head.take(8).toByteArray().contentEquals(
                byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            ) -> "PNG"
            head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() -> "JPEG"
            String(head.take(4).toByteArray(), Charsets.US_ASCII) == "GIF8" -> "GIF"
            String(head.take(4).toByteArray(), Charsets.US_ASCII) == "RIFF" -> "WEBP"
            else -> "unknown(ImageIO-decodable)"
        }
    }
}

/** 6. browser_use —— 页面打开/内容提取，含恶意域名与内网拦截、出网审批（与 shell 同一条风控链路）。 */
class BrowserUseTool(
    private val timeoutMs: Long = 10_000,
    private val maxBodyChars: Int = 1_000_000,
) : Tool {
    override val spec = ToolSpec(
        name = "browser_use",
        description = "浏览器自动化：navigate 打开公网页面、extract 提取正文。click/input 需 Android WebView 支持。",
        parameters = params {
            put("action", str("navigate / extract / click / input"))
            put("url", str("目标 URL，仅允许公网 http/https"))
            put("selector", str("extract 时的简单 CSS 选择器（按标签名粗提取）"))
            put("text", str("input 时的文本"))
        }.put("required", JSONArray().put("action")),
    )

    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NEVER)   // 禁止重定向绕过内网拦截
        .build()
    private val pages = ConcurrentHashMap<String, String>()
    private val blockedHosts = listOf(
        "localhost", "127.", "10.", "192.168.", "169.254.", "0.0.0.0", "::1",
        "172.16.", "172.17.", "172.18.", "172.19.", "172.2", "172.30.", "172.31.",
        ".local", ".internal", ".localhost", "metadata.google.internal",
    )

    override fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val action = args.optString("action").lowercase()
        if (action == "click" || action == "input") {
            return ToolResult(
                "Error: '$action' 需要 WebView/无障碍能力，纯后台骨架不支持；Android 移植时接平台 Handler。",
                isError = true,
            )
        }
        if (action != "navigate" && action != "extract") {
            return ToolResult("Error: unknown action '$action' (navigate/extract/click/input)", isError = true)
        }

        val url = args.optString("url").ifBlank { pages.keys.firstOrNull().orEmpty() }
        if (url.isBlank()) return ToolResult("Error: url is required", isError = true)
        val uri = runCatching { URI.create(url) }.getOrElse {
            return ToolResult("Error: invalid url: $url", isError = true)
        }
        if (uri.scheme?.lowercase() !in setOf("http", "https"))
            return ToolResult("Error: only http/https allowed, got ${uri.scheme}", isError = true)
        val host = (uri.host ?: "").lowercase()
        if (host.isBlank() || blockedHosts.any { host == it.removePrefix(".") || host.startsWith(it) || host.endsWith(it) }) {
            return ToolResult(
                "Error: 域名被黑名单/内网策略拦截：$host", isError = true,
                riskBlock = true, riskMsg = "RISK_BLOCK",
            )
        }

        // 出网统一过审批闸，和 shell_execute 的 OUTBOUND 判定同一条链路
        val outcome = ctx.approvals.ask(
            ApprovalRequest(ctx.sessionId, RiskClass.OUTBOUND, "浏览器访问外部网络", url.take(300)),
        )
        ctx.events.emit(
            "approval",
            JSONObject().put("risk", "OUTBOUND").put("outcome", outcome.name)
                .put("tool", "browser_use").put("session", ctx.sessionId)
                .put("trace_id", ctx.traceId),
        )
        if (outcome == ApprovalOutcome.DENY || outcome == ApprovalOutcome.TIMEOUT) {
            return ToolResult(
                "Error: 出网未获授权（${outcome.name}）：$url", isError = true,
                riskBlock = true, riskMsg = "RISK_BLOCK",
            )
        }

        val req = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofMillis(timeoutMs))
            .header("User-Agent", "nm-headless-runtime/1.2")
            .GET().build()
        val resp = runCatching { http.send(req, HttpResponse.BodyHandlers.ofString()) }.getOrElse { e ->
            return ToolResult("Error: 请求失败 ${e.javaClass.simpleName}: ${e.message}", isError = true)
        }
        val body = resp.body()?.take(maxBodyChars) ?: ""
        pages[url] = body
        val text = stripHtml(body)
        val selector = args.optString("selector").ifBlank { null }
        val extracted = if (selector != null) extractByTag(body, selector) else text.take(4000)
        return ToolResult(
            buildString {
                append("status     ").append(resp.statusCode()).append('\n')
                append("url        ").append(url).append('\n')
                append("bytes      ").append(body.length).append('\n')
                if (selector != null) append("selector   ").append(selector).append('\n')
                append("title      ").append(Regex("<title[^>]*>(.*?)</title>", RegexOption.IGNORE_CASE)
                    .find(body)?.groupValues?.get(1)?.trim() ?: "(none)").append('\n')
                append("--- content ---\n").append(extracted)
            }
        )
    }

    private fun stripHtml(s: String): String = s
        .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("(?s)<[^>]+>"), " ")
        .replace(Regex("\\s+"), " ").trim()

    private fun extractByTag(html: String, selector: String): String {
        val tag = selector.trim().removePrefix(".").removePrefix("#").takeWhile { it.isLetterOrDigit() }
        if (tag.isBlank()) return stripHtml(html).take(4000)
        return Regex("<$tag\\b[^>]*>(.*?)</$tag>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .findAll(html).map { stripHtml(it.groupValues[1]) }
            .filter { it.isNotBlank() }.take(50)
            .joinToString("\n").ifBlank { "(no <$tag> matched)" }
    }
}

/** 7. memory_write —— 长期记忆写入，容量与配额由 MemoryStore 兜底。 */
class MemoryWriteTool(private val memory: MemoryStore) : Tool {
    override val spec = ToolSpec(
        name = "memory_write",
        description = "写入一条 Agent 长期记忆（key + tags + content + 可选 expire_at）。单条与总容量有限额。",
        parameters = params {
            put("key", str("记忆键"))
            put("content", str("记忆内容"))
            put("tags", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")))
            put("expire_at", int("过期时间，Unix 毫秒；不传表示长期有效"))
        }.put("required", JSONArray().put("key").put("content")),
    )

    override fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val tags = args.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.optString(it) } }
            ?: emptyList()
        val expire = if (args.has("expire_at") && !args.isNull("expire_at"))
            args.optLong("expire_at", 0).takeIf { it > 0 } else null
        val r = memory.put(
            MemoryEntry(
                key = args.optString("key"),
                content = args.optString("content"),
                tags = tags,
                expireAt = expire,
                sessionId = ctx.sessionId,
            )
        )
        return ToolResult(
            if (r.ok) "memory_write ok: ${r.message}" else "Error: ${r.message}",
            isError = !r.ok,
        )
    }
}

/** 8. memory_get —— 按 key / tags 检索长期记忆，只读低风险。 */
class MemoryGetTool(private val memory: MemoryStore) : Tool {
    override val spec = ToolSpec(
        name = "memory_get",
        description = "按 key 精确检索或按 tags 交集检索长期记忆，返回最近写入的若干条。",
        parameters = params {
            put("key", str("记忆键，可选"))
            put("tags", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")))
            put("top_k", int("最多返回几条，默认 5"))
        },
    )

    override fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val tags = args.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.optString(it) } }
            ?: emptyList()
        val found = memory.get(args.optString("key").ifBlank { null }, tags, args.optInt("top_k", 5))
        if (found.isEmpty()) return ToolResult("no memory matched.")
        return ToolResult(buildString {
            append("matched ").append(found.size).append(" entr(y/ies)\n")
            found.forEach { e ->
                append("--- ").append(e.key).append(" [").append(e.tags.joinToString(",")).append("] ---\n")
                append(e.content).append('\n')
            }
        })
    }
}

/** 9. android_calendar —— 系统日历增删改查，接 SystemCapability 抽象。 */
class AndroidCalendarTool(private val capability: SystemCapability) : Tool {
    override val spec = ToolSpec(
        name = "android_calendar",
        description = "系统日历操作：query / add / update / delete。本地骨架用文件模拟，Android 端接 CalendarContract。",
        parameters = params {
            put("action", str("query / add / update / delete"))
            put("id", str("事件 ID，update/delete 必填"))
            put("title", str("日程标题"))
            put("start", str("开始时间 ISO8601"))
            put("end", str("结束时间 ISO8601"))
            put("description", str("备注"))
            put("from", str("query 起始过滤"))
            put("to", str("query 结束过滤"))
        }.put("required", JSONArray().put("action")),
    )

    override fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val r = capability.calendar(args)
        return ToolResult(r.toString(2), isError = !r.optBoolean("ok", false))
    }
}

/** 10. android_notify —— 本地通知推送，含频率限流。 */
class AndroidNotifyTool(private val capability: SystemCapability) : Tool {
    override val spec = ToolSpec(
        name = "android_notify",
        description = "推送一条本地系统通知（title/text/priority），带频率限流防刷屏。",
        parameters = params {
            put("title", str("通知标题"))
            put("text", str("通知正文"))
            put("priority", str("normal / high / low"))
        }.put("required", JSONArray().put("title")),
    )

    override fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val r = capability.notify(args)
        return ToolResult(r.toString(2), isError = !r.optBoolean("ok", false))
    }
}

/**
 * 10 个 P0 工具的统一装配入口。无参调用保持 V1.1 兼容（用于旧 CLI 冒烟），
 * 新装配应显式传入 root，让 MemoryStore / SystemCapability 落到指定目录。
 */
fun defaultTools(
    root: Path = Paths.get("nm-run/system"),
    memory: MemoryStore = JsonlMemoryStore(root.resolve("memory.jsonl")),
    capability: SystemCapability = LocalSystemCapability(root),
): List<Tool> = listOf(
    ShellTool(),
    FileReadTool(),
    FileWriteTool(),
    FileEditTool(),
    ReadImageTool(),
    BrowserUseTool(),
    MemoryWriteTool(memory),
    MemoryGetTool(memory),
    AndroidCalendarTool(capability),
    AndroidNotifyTool(capability),
)
