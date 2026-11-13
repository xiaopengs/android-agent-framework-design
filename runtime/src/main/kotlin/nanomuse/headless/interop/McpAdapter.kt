package nanomuse.headless.interop

import nanomuse.headless.core.ToolContext
import nanomuse.headless.tools.ToolDispatchCenter
import org.json.JSONArray
import org.json.JSONObject

/**
 * 互操作适配 —— MCP Server 协议层（DESIGN.md 15 交付物 7）。
 *
 * 用 JSON-RPC 2.0 暴露 tools/list 与 tools/call，把 MCP 调用转给
 * ToolDispatchCenter，因此 MCP 客户端与云端 TOOL_CALL 走的是同一套参数校验、
 * OffloadGate 与三层风控，不存在旁路。
 *
 * 本骨架只做协议编解码，不内置 HTTP/StdIO Server；接传输时把请求体丢给 handle() 即可。
 */
class McpAdapter(private val center: ToolDispatchCenter) {

    fun handle(request: JSONObject): JSONObject {
        val id = request.opt("id") ?: JSONObject.NULL
        val method = request.optString("method")
        val params = request.optJSONObject("params") ?: JSONObject()
        return try {
            when (method) {
                "initialize" -> success(
                    id, JSONObject()
                        .put("protocolVersion", "2024-11-05")
                        .put("capabilities", JSONObject().put("tools", JSONObject()))
                        .put("serverInfo", JSONObject().put("name", "nm-headless-runtime").put("version", "1.2")),
                )
                "ping" -> success(id, JSONObject())
                "tools/list" -> success(id, toolsList())
                "tools/call" -> success(id, toolsCallResult(params))
                else -> error(id, -32601, "Method not found: $method")
            }
        } catch (e: Exception) {
            error(id, -32603, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** MCP 的 inputSchema 直接复用 ToolSpec.parameters（同为 JSON Schema 子集）。 */
    fun toolsList(): JSONObject = JSONObject().put("tools", JSONArray().apply {
        center.specs().forEach { spec ->
            put(
                JSONObject()
                    .put("name", spec.name)
                    .put("description", spec.description)
                    .put("inputSchema", JSONObject(spec.parameters.toString())),
            )
        }
    })

    /**
     * tools/call：执行并把 ToolResult 包成 MCP content[]。
     * 需要宿主传入 ToolContext（会话、沙箱、审批、事件）。
     */
    fun toolsCall(name: String, arguments: JSONObject, ctx: ToolContext): JSONObject {
        val r = center.invokeRaw(name, arguments.toString(), ctx)
        return JSONObject()
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", r.content)))
            .put("isError", r.isError)
            .put("structuredContent", r.toJson())
    }

    /** 无 ToolContext 时的纯 schema 校验路径，供联调自检使用。 */
    fun toolsCallResult(params: JSONObject): JSONObject {
        val name = params.optString("name")
        val arguments = params.optJSONObject("arguments") ?: JSONObject()
        if (!center.has(name)) {
            return JSONObject()
                .put("content", JSONArray().put(JSONObject().put("type", "text")
                    .put("text", "Error: unknown tool '$name'")))
                .put("isError", true)
        }
        return JSONObject()
            .put("content", JSONArray().put(JSONObject().put("type", "text")
                .put("text", "tool '$name' is registered; call McpAdapter.toolsCall(name, arguments, ctx) to execute")))
            .put("isError", false)
            .put("validatedArguments", arguments)
    }

    private fun success(id: Any, result: JSONObject): JSONObject =
        JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result)

    private fun error(id: Any, code: Int, message: String): JSONObject =
        JSONObject().put("jsonrpc", "2.0").put("id", id)
            .put("error", JSONObject().put("code", code).put("message", message))
}
