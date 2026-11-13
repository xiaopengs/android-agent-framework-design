package nanomuse.headless.host

import nanomuse.headless.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * OpenAI 兼容 provider —— 对应 Android 端 provider/openai/OpenAIProvider.kt。
 *
 * 这里刻意用非流式（一次拿完整 message），把复杂度压到最低；
 * 原实现是 SSE 增量 + tool_call 按 index 拼装。接口的 onText 已经留好，
 * 接回 SSE 时只需要在解析处调用它，循环与工具层不用改。
 */
class OpenAiCompatibleProvider(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val temperature: Double = 0.2,
    private val log: (String) -> Unit = {},
) : ModelProvider {

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .build()

    override fun complete(
        system: String,
        messages: List<Message>,
        tools: List<ToolSpec>,
        onText: (String) -> Unit,
    ): ModelReply {
        val body = JSONObject().apply {
            put("model", model)
            put("temperature", temperature)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", system))
                messages.forEach { put(it.toJson()) }
            })
            if (tools.isNotEmpty()) {
                put("tools", JSONArray().apply { tools.forEach { put(it.toJson()) } })
                put("tool_choice", "auto")
            }
        }
        val req = HttpRequest.newBuilder(URI.create("$baseUrl/chat/completions"))
            .timeout(Duration.ofSeconds(180))
            .header("Content-Type", "application/json")
            .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()

        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in 200..299) {
            throw IllegalStateException("provider HTTP ${resp.statusCode()}: ${resp.body().take(500)}")
        }
        val root = JSONObject(resp.body())
        val choice = root.optJSONArray("choices")?.optJSONObject(0)
            ?: throw IllegalStateException("provider returned no choices")
        val msg = choice.optJSONObject("message") ?: JSONObject()

        val text = msg.optString("content").ifBlank { null }
        text?.let(onText)

        val calls = msg.optJSONArray("tool_calls")?.let { arr ->
            (0 until arr.length()).map { i ->
                val c = arr.getJSONObject(i)
                val fn = c.optJSONObject("function") ?: JSONObject()
                ToolCall(
                    id = c.optString("id").ifBlank { "call_$i" },
                    name = fn.optString("name"),
                    arguments = fn.optString("arguments").ifBlank { "{}" },
                )
            }
        } ?: emptyList()

        val usage = root.optJSONObject("usage")
        log("provider: ${calls.size} tool_call(s), text=${text?.length ?: 0}")

        return ModelReply(
            text = text,
            toolCalls = calls,
            usage = Usage(
                promptTokens = usage?.optInt("prompt_tokens", 0) ?: 0,
                completionTokens = usage?.optInt("completion_tokens", 0) ?: 0,
            ),
            stopReason = choice.optString("finish_reason").ifBlank { null },
        )
    }
}
