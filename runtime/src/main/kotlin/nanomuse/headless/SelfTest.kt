package nanomuse.headless

import nanomuse.headless.bus.*
import nanomuse.headless.core.*
import nanomuse.headless.guard.OffloadGate
import nanomuse.headless.router.AgentRequest
import nanomuse.headless.router.FixedNetworkProbe
import nanomuse.headless.router.RootRouter
import nanomuse.headless.router.RouteMode
import nanomuse.headless.session.SessionManager
import nanomuse.headless.session.SessionState
import nanomuse.headless.session.SessionTransitions
import nanomuse.headless.tools.ToolDispatchCenter
import org.json.JSONArray
import org.json.JSONObject
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * 内置自检：不连模型端点，覆盖 DESIGN.md 14 验收标准的骨架部分。
 * 由 `--self-test` 触发，输出 JSON 结果，便于 CI / 回归。
 */
object SelfTest {

    private class DenyGate : ApprovalGate {
        override fun ask(req: ApprovalRequest): ApprovalOutcome = ApprovalOutcome.DENY
    }

    data class Case(val name: String, val ok: Boolean, val detail: String)

    fun run(root: Path): JSONObject {
        val cases = mutableListOf<Case>()
        fun check(name: String, detail: String, block: () -> Boolean) {
            cases += runCatching { Case(name, block(), detail) }
                .getOrElse { Case(name, false, "${it.javaClass.simpleName}: ${it.message}") }
        }

        val rt = NanoMuseRuntime(root, RuntimeConfig(autoApprove = true, offline = true, echoEvents = false))
        val sessionId = "selftest"
        val ws = Files.createDirectories(root.resolve("workspaces").resolve(sessionId))
        val sandbox = nanomuse.headless.host.LocalProcessSandbox(ws)
        val ctx = ToolContext(sessionId, sandbox, rt.approvals, rt.events, rt.store, "tr-selftest", false)

        // ── 1. RootRouter 决策表 + 独占工具禁 CLOUD ─────────────────────
        check("router.local_when_offline", "离线强制 LOCAL") {
            val r = RootRouter(FixedNetworkProbe(false)).decide(AgentRequest("请总结这段文本"))
            r.mode == RouteMode.LOCAL
        }
        check("router.cloud_when_no_local_tool", "无独占工具 → CLOUD") {
            RootRouter(FixedNetworkProbe(true)).decide(AgentRequest("请写一段介绍")).mode == RouteMode.CLOUD
        }
        check("router.hybrid_on_exclusive_tool", "命中独占工具 → HYBRID") {
            RootRouter(FixedNetworkProbe(true)).decide(AgentRequest("用 shell_execute 看看目录")).mode == RouteMode.HYBRID
        }
        check("router.never_cloud_for_10_tools", "10 个 P0 工具任一命中都不输出 CLOUD") {
            val router = RootRouter(FixedNetworkProbe(true))
            nanomuse.headless.router.LocalExclusiveTools.names.all { name ->
                val modes = listOf(
                    router.decide(AgentRequest("请调用 $name")),
                    router.decide(AgentRequest("任务", explicitTools = listOf(name))),
                    router.decide(AgentRequest("任务", explicitTools = listOf(name), preferLocal = true)),
                )
                modes.all { it.mode != RouteMode.CLOUD }
            }
        }
        check("router.cloud_subtask_forced_local", "云端下发子任务强制 LOCAL") {
            RootRouter(FixedNetworkProbe(true)).decide(AgentRequest("执行", fromCloud = true)).mode == RouteMode.LOCAL
        }
        check("router.trace_and_session_generated", "生成 traceId / sessionId") {
            val d = RootRouter(FixedNetworkProbe(true)).decide(AgentRequest("hi"))
            d.traceId.startsWith("tr-") && d.sessionId.startsWith("s-") && d.decisionMs < 10
        }

        // ── 2. 信封编解码 ────────────────────────────────────────────────
        check("bus.envelope_roundtrip", "信封 JSON 往返字段一致") {
            val e = AgentEnvelope(
                traceId = "tr-1", sessionId = "s-1",
                direction = EnvelopeDirection.TO_CLOUD, msgType = MsgType.TOOL_CALL,
                routerTarget = RouterTarget.HYBRID, seq = 7, isStream = true,
                payload = JSONObject().put("tool_name", "file_read"),
            )
            val back = AgentEnvelope.fromJson(e.toJson())
            back.envelopeId == e.envelopeId && back.seq == 7L && back.msgType == MsgType.TOOL_CALL &&
                    back.routerTarget == RouterTarget.HYBRID && back.isStream &&
                    back.payload.optString("tool_name") == "file_read"
        }

        // ── 3. 会话状态机 ────────────────────────────────────────────────
        check("session.state_machine_legal", "状态机合法转移链") {
            val m = SessionManager(root.resolve("selftest-states"))
            m.open("sm-1", "tr-1", "LOCAL")
            m.transition("sm-1", SessionState.RUNNING)
            m.transition("sm-1", SessionState.WAIT_CLOUD)
            m.transition("sm-1", SessionState.RUNNING)
            m.transition("sm-1", SessionState.TOOL_EXEC)
            m.transition("sm-1", SessionState.RUNNING)
            m.finish("sm-1", 3, "done").state == SessionState.FINISHED
        }
        check("session.state_machine_illegal", "非法转移被拒绝") {
            val m = SessionManager(root.resolve("selftest-states2"))
            m.open("sm-2", "tr-2", "LOCAL")
            runCatching { m.transition("sm-2", SessionState.FINISHED) }.isFailure &&
                    !SessionTransitions.can(SessionState.IDLE, SessionState.FINISHED)
        }
        check("session.resumable", "断点续跑判定") {
            val m = SessionManager(root.resolve("selftest-states3"))
            m.open("sm-3", "tr-3", "LOCAL")
            m.transition("sm-3", SessionState.RUNNING)
            m.checkpoint("sm-3", SessionState.RUNNING, 2)
            m.resumable("sm-3") && m.resume("sm-3")?.turn == 2
        }

        // ── 4. 上下文压缩 ────────────────────────────────────────────────
        check("context.compact", "历史压缩 + 超长截断 + 摘要回收") {
            val msgs = mutableListOf<Message>()
            msgs += Message("user", "最初的任务")
            repeat(40) { i ->
                msgs += Message("assistant", "第 $i 轮", toolCalls = listOf(ToolCall("c$i", "file_read", "{}")))
                msgs += Message(
                    "tool", "x".repeat(if (i == 20) 12_000 else 100),
                    toolCallId = "c$i", name = "file_read",
                )
            }
            val r = nanomuse.headless.context.ContextManager(maxChars = 5_000, keepRecent = 6).compact(msgs)
            r.dropped > 0 && r.truncated > 0 && r.summaryAdded && r.compactedChars <= r.originalChars &&
                    r.messages.any { it.content?.startsWith("[context summary]") == true }
        }

        // ── 5. 10 个 P0 工具 ─────────────────────────────────────────────
        check("tools.all_10_registered", "10 个 P0 工具全部注册") {
            val expected = nanomuse.headless.router.LocalExclusiveTools.names
            rt.dispatch.names().containsAll(expected) && rt.dispatch.names().size == 10 &&
                    rt.mcp.toolsList().optJSONArray("tools")?.length() == 10
        }
        check("tools.shell_execute", "shell 正常执行") {
            !rt.dispatch.invokeRaw("shell_execute", JSONObject().put("command", "echo selftest-ok").toString(), ctx)
                .isError
        }
        check("tools.file_write_read_edit", "写 / 读 / 差分替换闭环") {
            val w = rt.dispatch.invokeRaw("file_write",
                JSONObject().put("path", "selftest.txt").put("content", "hello v1\n").toString(), ctx)
            val r = rt.dispatch.invokeRaw("file_read", JSONObject().put("path", "selftest.txt").toString(), ctx)
            val e = rt.dispatch.invokeRaw("file_edit",
                JSONObject().put("path", "selftest.txt").put("old", "v1").put("new", "v2").toString(), ctx)
            !w.isError && !r.isError && !e.isError && Files.readString(ws.resolve("selftest.txt")).contains("v2")
        }
        check("tools.read_image", "图片解析与元数据") {
            val img = BufferedImage(12, 8, BufferedImage.TYPE_INT_RGB)
            ImageIO.write(img, "png", ws.resolve("selftest.png").toFile())
            val r = rt.dispatch.invokeRaw("read_image",
                JSONObject().put("path", "selftest.png").put("return_base64", false).toString(), ctx)
            !r.isError && r.content.contains("12x8") && r.content.contains("PNG")
        }
        check("tools.browser_use_blocks_intranet", "browser_use 内网域名拦截") {
            val r = rt.dispatch.invokeRaw("browser_use",
                JSONObject().put("action", "navigate").put("url", "http://127.0.0.1:9/x").toString(), ctx)
            r.isError && r.riskBlock
        }
        check("tools.memory_write_get", "长期记忆写入与检索") {
            val w = rt.dispatch.invokeRaw("memory_write",
                JSONObject().put("key", "selftest-key").put("content", "记住 V1.2")
                    .put("tags", JSONArray().put("selftest")).toString(), ctx)
            val g = rt.dispatch.invokeRaw("memory_get",
                JSONObject().put("key", "selftest-key").toString(), ctx)
            !w.isError && !g.isError && g.content.contains("记住 V1.2")
        }
        check("tools.android_calendar", "日历 add + query") {
            val a = rt.dispatch.invokeRaw("android_calendar",
                JSONObject().put("action", "add").put("title", "自检日程")
                    .put("start", "2026-11-13T10:00").toString(), ctx)
            val q = rt.dispatch.invokeRaw("android_calendar",
                JSONObject().put("action", "query").toString(), ctx)
            !a.isError && !q.isError && q.content.contains("自检日程")
        }
        check("tools.android_notify", "通知推送与限流") {
            val r = rt.dispatch.invokeRaw("android_notify",
                JSONObject().put("title", "自检").put("text", "V1.2 ok").toString(), ctx)
            !r.isError && r.content.contains("ntf-")
        }
        check("tools.param_validation", "统一入参校验（缺 required）") {
            rt.dispatch.invokeRaw("file_write", "{}", ctx).isError
        }
        check("tools.unknown_tool", "未知工具兜底") {
            rt.dispatch.invokeRaw("no_such_tool", "{}", ctx).isError
        }
        check("tools.risk_gate_deny", "高危命令被审批闸拦截且不执行") {
            val denyCtx = ctx.copy(approvals = DenyGate())
            val r = rt.dispatch.invokeRaw("shell_execute",
                JSONObject().put("command", "rm -rf ./selftest.txt").toString(), denyCtx)
            r.isError && Files.exists(ws.resolve("selftest.txt"))
        }
        check("tools.offload_gate_rejects_unauthorized_cloud_call", "云端越权 TOOL_CALL 被拒") {
            val bad = ctx.copy(fromCloud = true, traceId = "")
            rt.dispatch.invokeRaw("file_read", JSONObject().put("path", "selftest.txt").toString(), bad).isError
        }
        check("tools.cloud_call_shares_risk_chain", "云端下发与本地调用走同一分发中心") {
            val cloudCtx = ctx.copy(fromCloud = true, traceId = "tr-cloud")
            !rt.dispatch.invokeRaw("file_read",
                JSONObject().put("path", "selftest.txt").toString(), cloudCtx).isError
        }

        // ── 6. 总线 ACK / 重试 / 死信 / 幂等 ─────────────────────────────
        check("bus.ack_and_seq", "模拟云端 ACK 与 seq 递增") {
            val cloud = SimulatedCloudTransport()
            val bus = CloudAgentBus(cloud, null, null, maxRetries = 1, ackTimeoutMs = 500)
            cloud.onAck = { bus.onAck(it) }
            val a = bus.send(AgentEnvelope(traceId = "t", sessionId = "b1",
                direction = EnvelopeDirection.TO_CLOUD, msgType = MsgType.STATUS,
                routerTarget = RouterTarget.CLOUD))
            val b = bus.send(AgentEnvelope(traceId = "t", sessionId = "b1",
                direction = EnvelopeDirection.TO_CLOUD, msgType = MsgType.STATUS,
                routerTarget = RouterTarget.CLOUD))
            a.ok && b.ok && a.seq == 1L && b.seq == 2L
        }
        check("bus.idempotent", "重复 envelopeId 幂等去重") {
            val cloud = SimulatedCloudTransport()
            val bus = CloudAgentBus(cloud, null, null, maxRetries = 1, ackTimeoutMs = 500)
            cloud.onAck = { bus.onAck(it) }
            val e = AgentEnvelope(traceId = "t", sessionId = "b2",
                direction = EnvelopeDirection.TO_CLOUD, msgType = MsgType.STATUS,
                routerTarget = RouterTarget.CLOUD)
            bus.send(e).ok && bus.send(e).duplicate
        }
        check("bus.retry_and_dead_letter", "离线传输重试后进死信") {
            val bus = CloudAgentBus(OfflineTransport(), null, root.resolve("selftest-bus"),
                maxRetries = 2, ackTimeoutMs = 50, retryBackoffMs = 1)
            val r = bus.send(AgentEnvelope(traceId = "t", sessionId = "b3",
                direction = EnvelopeDirection.TO_CLOUD, msgType = MsgType.QUERY,
                routerTarget = RouterTarget.CLOUD))
            !r.ok && r.deadLettered && r.attempts == 3 && bus.deadLetters().size == 1
        }

        // ── 7. MCP / A2A / App Functions ─────────────────────────────────
        check("interop.mcp_tools_list", "MCP tools/list 暴露 10 个工具") {
            val resp = rt.mcpHandle(JSONObject().put("jsonrpc", "2.0").put("id", 1)
                .put("method", "tools/list"))
            resp.optJSONObject("result")?.optJSONArray("tools")?.length() == 10
        }
        check("interop.mcp_unknown_method", "MCP 未知方法返回 -32601") {
            val resp = rt.mcpHandle(JSONObject().put("id", 2).put("method", "nope"))
            resp.optJSONObject("error")?.optInt("code") == -32601
        }
        check("interop.a2a_roundtrip", "A2A 任务 ↔ 信封往返") {
            val t = nanomuse.headless.interop.A2ATask(
                fromDevice = "phone", toDevice = "tablet", intent = "sync",
                payload = JSONObject().put("query", "hello"),
            )
            val e = rt.a2a.toEnvelope(t)
            val back = rt.a2a.fromEnvelope(e)
            back?.taskId == t.taskId && back?.intent == "sync" && back?.fromDevice == "phone"
        }
        check("interop.app_functions", "App Function schema 完整") {
            rt.appFunctions.schema().optJSONArray("functions")?.length() == 10
        }

        rt.close()
        val passed = cases.count { it.ok }
        return JSONObject()
            .put("suite", "nm-headless-runtime V1.2 self-test")
            .put("total", cases.size)
            .put("passed", passed)
            .put("failed", cases.size - passed)
            .put("all_passed", passed == cases.size)
            .put("cases", JSONArray().apply {
                cases.forEach {
                    put(JSONObject().put("name", it.name).put("ok", it.ok).put("detail", it.detail))
                }
            })
    }
}
