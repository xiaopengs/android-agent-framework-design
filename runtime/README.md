# nm-headless V1.2：nanoMuse 端侧 runtime 五层可运行骨架

把 nanoMuse Android 端「一个任务怎么跑完」的那部分拆成无界面后台 runtime：输入一个任务，输出一条 NDJSON 事件流。没有 UI、没有 WebView、没有无障碍。

V1.2 已按 `COVERAGE.md` 的补齐路线把**五层架构全部落地**：RootRouter 决策 → CloudAgentBus 信封总线 → SessionManager + TaskQueue → AgentLoop + ContextManager → ToolDispatchCenter + 三层风控 + 10 个 P0 工具。LOCAL / CLOUD / HYBRID 三条主链路均可端到端跑通，内置自检 31/31、端到端冒烟 18/18。

> 方案对齐矩阵、验收对照与剩余缺口见 [COVERAGE.md](COVERAGE.md)；方案基线见 [docs/DESIGN.md](../docs/DESIGN.md)。

---

## 一、它和 Android 端的对应关系

| 本仓库文件 | Android 原实现 | 说明 |
| :--- | :--- | :--- |
| `core/Contracts.kt` | 散落在 VM 与各 repository | 6 个平台无关接口 + `ToolContext(traceId/fromCloud)`、`ToolResult` 标准出参 |
| `core/AgentLoop.kt` | `ui/chat/ChatViewModel.kt#runAgentLoop()` | 200 轮上限、tool_call 回灌、打转检测、取消/上下文/分发注入 |
| `router/RootRouter.kt` | 新增（方案 Layer1） | 三模式决策、离线降级、traceId 生成 |
| `bus/CloudAgentBus.kt` | 新增（方案 Layer2） | 信封、ACK、重试、死信、流式、可插拔 Transport |
| `session/SessionManager.kt` / `TaskQueue.kt` | 新增（方案 Layer3） | 状态机持久化、断点续跑、队列与并发管控 |
| `context/ContextManager.kt` | `io.github.nanomuse.chat`（2,159 行） | 历史压缩、超长截断、摘要回收 |
| `tools/ToolDispatchCenter.kt` | 原分散在各调用点 | 统一分发、参数校验、审计日志 |
| `tools/BuiltinTools.kt` + `PlatformTools.kt` | `tools/*.kt` + 24 个系统 handler | 10 个 P0 工具 |
| `guard/ShellGuard.kt` / `OffloadGate.kt` | `guard/*.kt` | 四问静态闸、阻塞审批、卸载降级闸 |
| `capability/SystemCapability.kt` / `memory/MemoryStore.kt` | Android 系统能力 / 记忆库 | 平台差异隔离接口 |
| `host/LocalProcessSandbox.kt` | `sandbox/{PRootKernel,PersistentShell,…}.kt` | 用宿主进程替代 proot |
| `host/MultiSessionEventSink.kt` / `Stores.kt` | SQLite + hub 事件流 | JSONL + 按会话分流 NDJSON |
| `host/OpenAiProvider.kt` | `provider/openai/OpenAIProvider.kt`（SSE） | 非流式调用，`onText` 已留 |
| `interop/McpAdapter.kt` / `A2AAdapter.kt` | 方案交付物 7/8 | MCP JSON-RPC、A2A 与 App Function 适配 |
| `Runtime.kt` | — | 五层装配器，三条主链路 |
| `SelfTest.kt` | — | 31 项不连模型的自检 |
| `Main.kt` | `debug/HeadlessChatRunner.kt` | CLI 入口 |

## 二、布局

```
runtime/
├── src/main/kotlin/nanomuse/headless/
│   ├── Main.kt                     # CLI
│   ├── Runtime.kt                  # 五层装配器（三条主链路）
│   ├── SelfTest.kt                 # 31 项内置自检
│   ├── router/RootRouter.kt        # Layer1
│   ├── bus/CloudAgentBus.kt        # Layer2（含模拟云端 / 离线 Transport）
│   ├── session/{SessionManager,TaskQueue}.kt   # Layer3
│   ├── context/ContextManager.kt   # Layer4
│   ├── core/{Contracts,AgentLoop}.kt
│   ├── tools/{BuiltinTools,PlatformTools,ToolDispatchCenter}.kt  # Layer5 + 10 工具
│   ├── guard/{ShellGuard,OffloadGate}.kt
│   ├── capability/SystemCapability.kt
│   ├── memory/MemoryStore.kt
│   ├── host/{LocalProcessSandbox,Stores,OpenAiProvider,MultiSessionEventSink}.kt
│   └── interop/{McpAdapter,A2AAdapter}.kt
├── tools/{build.sh,fake_model.py,smoke.py}
├── libs/json.jar
└── out/nm-headless.jar            # 自包含（内嵌 kotlin-stdlib）
```

运行期产物（可删）：

```
nm-run/
├── workspaces/<session>/     # 工具工作目录
├── sessions/*.jsonl          # 会话消息 + meta
├── sessions/*.state.json     # 会话状态机快照
├── bus/*.jsonl               # 总线投递日志 + dead-letter.jsonl
├── system/                   # memory.jsonl / calendar.json / notifications.ndjson
└── events/*.ndjson           # 按会话分流的事件流
```

## 三、编译

```bash
./tools/build.sh
```

脚本优先用 `kotlinc`；未安装时自动退回 Gradle 自带的 `kotlin-compiler-embeddable`（本机 Kotlin 2.2.21 / JDK 20，`-jvm-target 11`），并把 `kotlin-stdlib` 合并进产物。产出 `out/nm-headless.jar`，运行时只需 `libs/json.jar`：

```bash
java -cp out/nm-headless.jar:libs/json.jar nanomuse.headless.MainKt --self-test --root ./nm-run
```

## 四、运行

```bash
# 1) 内置自检（不连模型，31 项）
java -cp out/nm-headless.jar:libs/json.jar nanomuse.headless.MainKt --self-test --root ./nm-run

# 2) 起脚本化模型端点（不需要真 key）
python3 tools/fake_model.py 8787

# 3) LOCAL：本地闭环
java -cp out/nm-headless.jar:libs/json.jar nanomuse.headless.MainKt \
  --task "写一个 hello.txt 再读回来" --mode local --root ./nm-run \
  --session smoke-1 --auto-approve

# 4) HYBRID：云端下发 TOOL_CALL、端侧执行、结果回传（模拟云端，不需要真模型）
java -cp out/nm-headless.jar:libs/json.jar nanomuse.headless.MainKt \
  --task "端云协同跑一遍" --mode hybrid --root ./nm-run --session hybrid-1

# 5) CLOUD：云端全权，本地内核零启动
java -cp out/nm-headless.jar:libs/json.jar nanomuse.headless.MainKt \
  --task "介绍一下端云协同" --mode cloud --root ./nm-run

# 6) 一键端到端冒烟（18 项，自动起/停假模型）
python3 tools/smoke.py
```

接真模型：

```bash
export NM_BASE_URL=https://api.openai.com/v1 NM_API_KEY=sk-... NM_MODEL=gpt-4o-mini
java -cp out/nm-headless.jar:libs/json.jar nanomuse.headless.MainKt \
  --task "统计 workspace 里有多少行代码" --mode local --root ./nm-run --session real-1
```

| 参数 | 说明 |
| :--- | :--- |
| `--mode auto\|local\|cloud\|hybrid` | **只设置请求偏好**，最终模式仍由 RootRouter 决策，禁止旁路 |
| `--offline` / `NM_OFFLINE=1` | 断网降级 LOCAL |
| `--resume` | 复用已落盘历史断点续跑 |
| `--auto-approve` | 审批自动放行（仅冒烟） |
| `--max-turns N` | 轮次上限，默认 200 |
| `--echo-events` | 事件同时打印到 stdout |
| `--json` | 结果以 JSON 输出，便于脚本解析 |
| `--self-test` | 运行 31 项自检 |

## 五、事件契约

`events/<session>.ndjson`，一行一个 JSON，`type` 区分；无 session 字段的事件落 `_global.ndjson`。

| type | 何时 | 关键字段 |
| :--- | :--- | :--- |
| `router_decision` | Router 决策 | `trace_id`,`session_id`,`mode`,`reason`,`decision_ms` |
| `session_open` / `session_state` / `session_checkpoint` / `session_finish` | 状态机流转 | `state`,`turn`,`mode` |
| `task_enqueue` / `task_start` / `task_rejected` / `task_cancelled` | 队列与并发 | `task_id`,`in_flight`,`reason` |
| `bus_send` / `bus_ack` / `bus_retry` / `bus_inbound` / `bus_dead_letter` | 总线投递 | `envelope_id`,`seq`,`msg_type`,`attempt` |
| `offload_gate` | 卸载判定 | `action`,`from_cloud`,`force_risk_check` |
| `tool_dispatch` | 统一分发审计 | `tool`,`trace_id`,`from_cloud`,`ms`,`risk_block` |
| `turn_start` / `turn` / `text` | 任务开始、每轮、模型吐字 | `turn`,`delta`,`resume` |
| `context_compact` | 上下文压缩 | `dropped`,`truncated`,`summary_added` |
| `tool_start` / `tool_result` | 工具调用前后 | `id`,`name`,`ms`,`error`,`preview` |
| `approval` | 过闸 | `risk`,`outcome` |
| `loop_detected` / `cancelled` | 打转 / 取消 | `tool`,`repeat` |
| `done` | 收尾 | `turns`,`stopped_by`,`prompt_tokens`,`completion_tokens` |
| `error` | 异常 | `message` |

`stopped_by`：`done` / `turn_limit`（可继续，不是失败）/ `loop_detected` / `cancelled` / `error` / `bus_dead_letter`。
上层做 SSE / WebSocket 只需订阅对应会话文件，内核不用动。

## 六、换宿主：只改实现，不改内核

| 想换 | 实现 | 怎么换 |
| :--- | :--- | :--- |
| 隔离强度 | `Sandbox` | bwrap / proot / 容器；`exec(argv,cwd,timeoutMs)` 语义不变 |
| 审批通道 | `ApprovalGate` | HTTP 回调 / IM 卡片；超时必须能触发 TIMEOUT |
| 会话存储 | `SessionStore` | SQLite / Postgres |
| 事件去向 | `EventSink` | SSE / Kafka / WebSocket Hub |
| 模型流式 | `ModelProvider` | 把 SSE 解析接到 `onText`，循环不用动 |
| 长期记忆 | `MemoryStore` | SQLite / 向量库 |
| 设备能力 | `SystemCapability` | CalendarContract / NotificationManager / WebView |
| 云端传输 | `Transport` | WebSocket / gRPC，替换 `SimulatedCloudTransport` |

**不要改** `AgentLoop`：它是被验证过的部分；新增能力全部通过可选构造参数注入。

## 七、已知取舍

- **模拟云端**：`SimulatedCloudTransport` 按脚本下发 TOOL_CALL 用于闭环验证，非真实云端 Agent 服务。
- **非流式模型**：`OpenAiProvider` 一次拿完整 message；`onText` 已留，补 SSE 即可逐字吐字。
- **无持久 shell**：每次 `exec` 一个新进程，proot 那套「同会话一条 /bin/sh、cd 保留」未复刻。
- **审批无 UI**：命令行读 stdin，180s 无输入按 TIMEOUT（拒绝）；会话级/永久授权态未持久化。
- **沙箱隔离强度**：宿主进程级，不是 OS 级隔离。
- **上下文摘要**：规则生成，未接模型摘要。
- **browser_use.click/input**：需 Android WebView / 无障碍，纯后台骨架明确返回不支持。
- **零协程依赖**：方案里的 `Flow` / `suspend` 用回调 + `BlockingQueue` 等价实现。

## 八、冒烟记录

| 场景 | 结果 |
| :--- | :--- |
| 内置自检 31 项 | 31/31 通过（路由决策表、信封往返、状态机、上下文压缩、10 工具、风控、总线重试死信、MCP/A2A） |
| 端到端冒烟 18 项 | 18/18 通过，连续两次结果一致 |
| LOCAL 写文件 → shell 读回 → 收尾 | 3 轮，`stopped_by=done`，2 次工具调用 |
| 断网降级 | Router 强制 LOCAL |
| `rm -rf` 高危命令 | ShellGuard 判 `DESTRUCTIVE` → 审批 `DENY` → 命令未执行 |
| HYBRID 协同闭环 | 2 个 TOOL_CALL 下发 → 端侧同风控执行 → TOOL_RESULT 回传 → FINISHED，0 死信 |
| CLOUD 全权 | 无 `turn_start` / `tool_result`，本地内核零启动 |
