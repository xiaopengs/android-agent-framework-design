# 代码与方案对齐度矩阵（V1.2 Runtime）

> 对照 `docs/DESIGN.md`（V1.1 Final-Dev）五层架构、3 条主链路与 10 个 P0 工具清单，逐项核对 `runtime/` 内 Kotlin 骨架的实现状态。
>
> **总体结论（V1.2）**：五层架构全部模块已落地，10 个 P0 工具全集实现，LOCAL / CLOUD / HYBRID 三条主链路端到端可跑；内置自检 **31/31**、端到端冒烟 **18/18** 全绿（见 `tools/self-test`、`tools/smoke.py`）。剩余缺口集中在**真实传输层、流式模型、Android 平台 Handler 与隔离强度**等移植项，见第七节。

## 一、状态说明

| 标记 | 含义 |
| :--- | :--- |
| 已实现 | 代码可运行，行为与方案契约一致，有自检用例覆盖 |
| 部分实现 | 核心链路在，方案中的子能力为可替换的简化实现或待接真实平台 |
| 未实现 | 代码库中暂无对应模块 |

## 二、五层架构覆盖矩阵

| 方案层 / 模块 | 代码位置 | V1.1 | V1.2 | 说明 |
| :--- | :--- | :--- | :--- | :--- |
| **Layer1 RootRouter** 协同调度 | `router/RootRouter.kt` | 未实现 | **已实现** | 唯一入口、网络探测、独占工具识别、LOCAL/CLOUD/HYBRID 决策、traceId/sessionId 生成、离线降级、隐私关键词强制本地、可配置规则；决策 <10ms 有自检 |
| **Layer2 CloudAgentBus** 通信总线 | `bus/CloudAgentBus.kt` | 未实现 | **已实现** | 信封编解码、seq 递增、幂等去重、ACK 等待、指数退避重试、死信落盘、流式分片（STREAM_CHUNK）、per-session 队列订阅、全量投递日志；`Transport` 可插拔 |
| **Layer3 SessionManager** | `session/SessionManager.kt` | 部分 | **已实现** | 会话注册表、`SessionState` 六态状态机持久化、合法转移校验、checkpoint 现场、断点续跑判定 |
| **Layer3 TaskQueue / ConcurrencyController** | `session/TaskQueue.kt` | 未实现 | **已实现** | 会话内单线程串行、会话间并行、全局在途任务 Semaphore、并发会话上限、协作式取消、awaitIdle |
| **Layer4 AgentLooper** | `core/AgentLoop.kt` | 已实现 | **已实现** | 200 轮上限、tool_call 回灌、打转检测、turn_limit 收尾；V1.2 新增取消检查、每轮 checkpoint、上下文窗口、分发中心注入（均为可选参数，旧装配不变） |
| **Layer4 StateMachine** | `session/SessionManager.kt` | 部分 | **已实现** | IDLE / RUNNING / WAIT_CLOUD / TOOL_EXEC / FINISHED / ERROR 全状态落盘 |
| **Layer4 ContextManager** | `context/ContextManager.kt` | 未实现 | **已实现** | 单条超长截断（工具结果 4k）、超预算折叠中间历史为摘要、保留首条意图与最近 N 轮、防止 tool_call_id 失配 |
| **Layer5 ToolDispatchCenter** | `tools/ToolDispatchCenter.kt` | 部分 | **已实现** | 按名路由、required + 类型统一校验、未知工具/非法参数兜底、审计日志（traceId / fromCloud / 耗时）、标准出参 `toProtocolJson`；本地与云端调用共用入口 |
| **Layer5 ShellGuard** | `guard/ShellGuard.kt` | 已实现 | **已实现** | 四问口径：destructive / outbound / install / money |
| **Layer5 RiskGate** | `guard/ShellGuard.kt#ConsoleApprovalGate` | 已实现 | **部分实现** | 阻塞审批、180s 超时按拒绝、autoApprove 冒烟；会话级/永久授权态与 UI 卡片仍待持久化（见第七节） |
| **Layer5 OffloadGate** | `guard/OffloadGate.kt` | 未实现 | **已实现** | 云端 TOOL_CALL 的 traceId/sessionId 越权校验、本地能力不足时的卸载判定（默认拒绝，不出端） |
| **Layer5 SandboxRuntime** | `host/LocalProcessSandbox.kt` | 已实现 | **部分实现** | 工作目录隔离、超时强杀（124）、输出截断；隔离强度仍为宿主进程，proot/bwrap 为可替换实现 |
| **Layer5 SystemCapability** | `capability/SystemCapability.kt` | 未实现 | **部分实现** | 日历 CRUD、通知推送与频率限流已接通（本地文件模拟）；Android 端需换 CalendarContract / NotificationManager |
| **Layer5 MemoryStore** | `memory/MemoryStore.kt` | 未实现 | **已实现** | key/tags 检索、expire_at、单条容量与总配额限制、JSONL 落盘；接口可换 SQLite/向量库 |
| **ModelProvider** | `host/OpenAiProvider.kt` | 部分 | **部分实现** | OpenAI 兼容非流式调用；`onText` 已留，SSE 增量吐字待接（循环层无需改动） |
| **EventSink** | `host/MultiSessionEventSink.kt` | 已实现 | **已实现** | NDJSON 一行一事件，按 session 自动分流文件，无 session 的事件落 `_global.ndjson`；可直接转 SSE/WebSocket |
| **MCP 适配** | `interop/McpAdapter.kt` | 未实现 | **部分实现** | JSON-RPC 2.0 的 initialize / tools/list / tools/call 协议层已通；未内置 HTTP/StdIO Server |
| **A2A 适配** | `interop/A2AAdapter.kt` | 未实现 | **部分实现** | A2A 任务 ↔ 信封双向转换、远程提交与本地接收；未接真实跨设备传输 |
| **App Function 适配** | `interop/A2AAdapter.kt#AppFunctionAdapter` | 未实现 | **已实现** | 10 个工具导出为函数 schema，系统意图调用仍经过分发中心 |

## 三、10 个 P0 工具覆盖矩阵

| # | 方案工具 | 代码类 | V1.1 | V1.2 | 说明与风控 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| 1 | `shell_execute` | `tools/BuiltinTools.kt#ShellTool` | 已实现 | **已实现** | `/bin/sh -c`、超时、四问静态闸 + 审批；stdout/stderr/退出码带回 |
| 2 | `file_read` | `tools/BuiltinTools.kt#FileReadTool` | 已实现 | **已实现** | 工作目录内读取、行号、offset/limit、2MB 上限 |
| 3 | `file_write` | `tools/BuiltinTools.kt#FileWriteTool` | 已实现 | **已实现** | 原子写（临时文件 + ATOMIC_MOVE）、路径不得逃逸 |
| 4 | `file_edit` | `tools/BuiltinTools.kt#FileEditTool` | 已实现 | **已实现** | 唯一匹配替换，找不到/多义即报错 |
| 5 | `read_image` | `tools/PlatformTools.kt#ReadImageTool` | 未实现 | **已实现** | ImageIO 解码、格式/尺寸/字节数、缩略图 Base64；大图限流、隐私路径拦截 |
| 6 | `browser_use` | `tools/PlatformTools.kt#BrowserUseTool` | 未实现 | **部分实现** | navigate/extract 走 JDK HttpClient；域名黑名单 + 内网拦截 + 出网审批；click/input 需 Android WebView |
| 7 | `memory_write` | `tools/PlatformTools.kt#MemoryWriteTool` | 未实现 | **已实现** | 容量限额、总配额、expire_at |
| 8 | `memory_get` | `tools/PlatformTools.kt#MemoryGetTool` | 未实现 | **已实现** | key 精确 / tags 交集检索、top_k |
| 9 | `android_calendar` | `tools/PlatformTools.kt#AndroidCalendarTool` | 未实现 | **已实现** | query/add/update/delete，接 `SystemCapability` 抽象 |
| 10 | `android_notify` | `tools/PlatformTools.kt#AndroidNotifyTool` | 未实现 | **已实现** | 频率限流（5 条/分钟），接 `SystemCapability` 抽象 |

## 四、接口契约对齐（V1.2 增补）

| 契约 | 对齐点 |
| :--- | :--- |
| `Tool` / `ToolSpec` | 方案 6.3 统一入口；`function.name` / `function.parameters` 同步用于 MCP `inputSchema` 与 App Function schema |
| `ToolCall` | 方案 TOOL_CALL / MCP 对齐：`id` / `name` / `arguments(JSON 字符串)` |
| `ToolResult` | 新增 `code` / `riskBlock` / `riskMsg` 与 `toProtocolJson()`，产出方案 6.3 的 `{success, code, msg, data, risk_block, risk_msg}` |
| `ToolContext` | 新增 `traceId` / `fromCloud`（末尾默认值，旧装配兼容），用于全链路 trace 与「云端下发同风控」判定 |
| `AgentEnvelope` | 方案 6.2 信封 JSON 全字段 + 往返编解码 |
| `SessionSnapshot` | 方案第 5 章状态机持久化 + checkpoint |
| `Transport` | 新增可插拔传输抽象；`SimulatedCloudTransport` / `OfflineTransport` 用于联调与降级测试 |

> 取舍：方案接口写的是 `Flow` / `suspend`。本骨架不引入 kotlinx-coroutines，流式出口统一走 `EventSink`（NDJSON）、订阅走 `BlockingQueue`、ACK 走阻塞等待，语义等价且保持零额外依赖；Android 端可直接替换为 Kotlin Flow。

## 五、验证记录（V1.2）

| 验证 | 命令 | 结果 |
| :--- | :--- | :--- |
| 内置自检（31 项） | `java -cp out/nm-headless.jar:libs/json.jar nanomuse.headless.MainKt --self-test --root ./nm-run` | **31/31** |
| 端到端冒烟（18 项） | `python3 tools/smoke.py` | **18/18**，连续两次运行结果一致 |
| LOCAL 主链路 | `--mode local --auto-approve` | 3 轮 `done`、2 次工具调用、事件流齐全、文件落盘 |
| 断网降级 | `--offline` | Router 强制 LOCAL，任务不丢失 |
| 风控拦截 | destructive 场景 + 审批输入 `n` | `DESTRUCTIVE → DENY`，命令未执行，文件保留 |
| HYBRID 闭环 | `--mode hybrid` | 云端下发 2 个 TOOL_CALL → 端侧执行 → TOOL_RESULT 回传 → STATUS FINISHED，`bus_send/ack/inbound` 齐全，0 死信 |
| CLOUD 全权 | `--mode cloud` | 本地内核零启动（无 turn_start / tool_result），仅流式分片 + 状态 |
| 路由专项 | 自检 `router.never_cloud_for_10_tools` | 10 个独占工具 × 3 种请求组合均不输出 CLOUD |

## 六、验收标准对照（DESIGN.md 14）

| 验收项 | 状态 | 备注 |
| :--- | :--- | :--- |
| 14.1 框架基础（Router 唯一入口、离线降级、CLOUD 零内核、HYBRID 闭环、多会话、总线幂等重试、独立编译） | **已通过** | 多会话并行的压测强度待补 |
| 14.2 路由规则专项（独占工具禁 CLOUD、断网全本地） | **已通过** | 自检全覆盖 |
| 14.3 10 工具全覆盖（本地/云端调用、参数校验、风控、异常兜底） | **已通过（骨架层）** | `browser_use.click/input` 需 Android WebView；真平台权限校验待移植 |
| 14.4 协同链路（云端下发 10 类指令、结果标准化回传、无安全绕过） | **已通过（模拟云端）** | 需与真实云端 Agent 服务联调 |

## 七、剩余缺口与下一步（V1.3 建议顺序）

1. **真实传输层**：把 `SimulatedCloudTransport` 换成 WebSocket / gRPC / HTTP 长轮询，与真实云端 Agent 服务联调（当前为脚本化模拟）。
2. **模型流式**：`OpenAiCompatibleProvider` 接 SSE 增量解析并调用 `onText`，循环层不用改。
3. **Android 平台 Handler**：日历/通知换 `CalendarContract` / `NotificationManager`；`browser_use.click/input` 接 WebView 或无障碍。
4. **隔离强度**：`Sandbox` 换 proot / bwrap / 持久 shell，替换宿主进程实现。
5. **授权态持久化**：`ApprovalGate` 的 ALLOW_SESSION / ALLOW_ALWAYS 落库，审批改 UI 卡片 / IM 回调。
6. **存储与事件**：`SessionStore` 换 SQLite、`EventSink` 换 SSE/WebSocket Hub、`MemoryStore` 换向量检索。
7. **上下文摘要模型化**：当前摘要为规则生成，可换模型摘要回收。
8. **长稳与压测**：多会话并发压测、后台长期保活、断连重连与死信重放。
9. **互操作传输**：MCP HTTP/StdIO Server、A2A 真实跨设备通道。

## 八、Android 移植提示（更新）

`Contracts.kt` 把平台差异隔离在接口之后。移植时替换 **7 个实现**即可，`AgentLoop` 与 10 个工具本身不改动：

| 换什么 | 接口 | 当前实现 | Android 实现 |
| :--- | :--- | :--- | :--- |
| 隔离强度 | `Sandbox` | `LocalProcessSandbox` | proot + 持久 shell |
| 审批通道 | `ApprovalGate` | `ConsoleApprovalGate` | 通知卡片 / IM 回调 + 授权库 |
| 会话存储 | `SessionStore` | `JsonlSessionStore` | SQLite |
| 事件出口 | `EventSink` | `MultiSessionEventSink` | SSE / Hub |
| 模型接入 | `ModelProvider` | `OpenAiCompatibleProvider`（非流式） | SSE 拼装 |
| 长期记忆 | `MemoryStore` | `JsonlMemoryStore` | SQLite / 向量库 |
| 系统能力 | `SystemCapability` | `LocalSystemCapability` | CalendarContract / NotificationManager |
