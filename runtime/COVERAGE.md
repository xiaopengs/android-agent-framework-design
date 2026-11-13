# 代码与方案对齐度矩阵

> 对照 `docs/DESIGN.md`（V1.1 Final-Dev）五层架构与 10 个 P0 工具清单，逐项核对 `runtime/` 内现有 Kotlin 骨架的实现状态。
>
> **总体结论**：现有代码是 **Layer4 Agent 内核 + Layer5 本地执行/风控** 的最小可运行垂直切片，纯 LOCAL 形态、无 UI、可端到端冒烟跑通；Layer1 协同调度、Layer2 通信总线、Layer3 队列并发、平台能力工具与 MCP/A2A 适配尚未实现，缺口见文末路线图。

## 一、状态说明

| 标记 | 含义 |
| :--- | :--- |
| 已实现 | 代码可运行，行为与方案契约一致 |
| 部分实现 | 核心链路在，方案中的子能力缺失或为简化版本 |
| 未实现 | 代码库中暂无对应模块 |

## 二、五层架构覆盖矩阵

| 方案层 / 模块 | 代码位置 | 状态 | 说明与缺口 |
| :--- | :--- | :--- | :--- |
| **Layer1 RootRouter** 协同调度 | — | 未实现 | 骨架无路由入口，任务直接进入 Looper；方案 6.1 的网络检测、工具依赖识别、LOCAL/CLOUD/HYBRID 决策、traceId 生成、离线降级均待补 |
| **Layer2 CloudAgentBus** 通信总线 | — | 未实现 | 无信封编解码、ACK、重试、死信、流式分片；`OpenAiCompatibleProvider` 仅直连 OpenAI 兼容端点，非总线协议 |
| **Layer3 SessionManager** 会话隔离与持久化 | `host/Stores.kt`、`host/LocalProcessSandbox.kt#SessionWorkspace` | 部分实现 | 已有会话工作目录隔离、JSONL 历史消息与 turn meta 落盘；缺会话注册表、会话状态机恢复与断点续跑 |
| **Layer3 TaskQueue** 任务排队 | — | 未实现 | 单任务同步执行，无队列、无任务启停控制 |
| **Layer3 ConcurrencyController** 并发上限 | — | 未实现 | 无并发会话数/在途任务数上限控制 |
| **Layer4 AgentLooper** 推理循环 | `core/AgentLoop.kt` | 已实现 | 200 轮上限、tool_call 回灌、同一调用重复 3 次打转检测、撞上限按 `turn_limit` 收尾；与方案 6.4 循环接口对齐 |
| **Layer4 StateMachine** 会话状态机 | `core/AgentLoop.kt#Outcome` | 部分实现 | 终止态 `done / turn_limit / loop_detected` 三态已有；无持久化 `SessionState` 与断点续跑 |
| **Layer4 ContextManager** 上下文裁剪 | — | 未实现 | 无历史压缩、超长截断与摘要回收 |
| **Layer5 ToolDispatchCenter** 工具统一分发 | `core/AgentLoop.kt#byName / executeOne` | 部分实现 | 按名路由、参数 JSON 解析、未知工具/非法参数兜底在循环内完成；无独立分发中心与统一入参校验层 |
| **Layer5 ShellGuard** 静态风控 | `guard/ShellGuard.kt` | 已实现 | 四问口径：destructive / outbound / install / money 正则判定，对应方案风控规范 |
| **Layer5 RiskGate** 审批授权 | `guard/ShellGuard.kt#ConsoleApprovalGate` | 已实现 | 阻塞审批、180s 超时按拒绝处理；`autoApprove` 仅用于冒烟；会话级/永久授权态未持久化 |
| **Layer5 OffloadGate** 卸载降级闸 | — | 未实现 | 本地能力不足时卸载到云端的判定闸未实现 |
| **Layer5 SandboxRuntime** 执行沙箱 | `host/LocalProcessSandbox.kt` | 已实现 | 工作目录隔离、超时强杀（退出码 124）、stdout/stderr 带回与输出截断；隔离强度为宿主进程，方案中的 proot/bwrap 为可替换实现 |
| **Layer5 SystemCapability** 系统能力 | — | 未实现 | 日历、通知、设备能力 handler 未接 |
| **Layer5 MemoryStore** 记忆持久化 | — | 未实现 | 长期记忆读写存储未实现 |
| **ModelProvider** 模型接入 | `host/OpenAiProvider.kt` | 部分实现 | OpenAI 兼容 `/chat/completions` 非流式调用；`onText` 回调已留，补 SSE 解析即可增量吐字，循环层无需改动 |
| **EventSink** 事件出口 | `host/Stores.kt#JsonlEventSink` | 已实现 | NDJSON 一行一事件，可直接转 SSE/WebSocket；事件类型见 `runtime/README.md` 第四节 |

## 三、10 个 P0 工具覆盖矩阵

| # | 方案工具 | 代码类 | 状态 | 说明 |
| :--- | :--- | :--- | :--- | :--- |
| 1 | `shell_execute` | `tools/BuiltinTools.kt#ShellTool` | 已实现 | `/bin/sh -c`，900s 默认超时，过闸后执行，stdout/stderr/退出码带回 |
| 2 | `file_read` | `tools/BuiltinTools.kt#FileReadTool` | 已实现 | 工作目录内读取，行号、offset/limit、2MB 上限 |
| 3 | `file_write` | `tools/BuiltinTools.kt#FileWriteTool` | 已实现 | 原子写（临时文件 + ATOMIC_MOVE），路径不得逃逸工作目录 |
| 4 | `file_edit` | `tools/BuiltinTools.kt#FileEditTool` | 已实现 | 唯一匹配替换，找不到/多义即报错，不做模糊匹配 |
| 5 | `read_image` | — | 未实现 | 依赖平台图像解码，按 `Tool` 接口新增即可 |
| 6 | `browser_use` | — | 未实现 | 浏览器自动化未接 |
| 7 | `memory_write` | — | 未实现 | 依赖 MemoryStore |
| 8 | `memory_get` | — | 未实现 | 依赖 MemoryStore |
| 9 | `android_calendar` | — | 未实现 | 系统日历能力，Android 移植时接平台 Handler |
| 10 | `android_notify` | — | 未实现 | 本地通知能力，Android 移植时接平台 Handler |

## 四、接口契约对齐

`core/Contracts.kt` 定义的六个平台无关接口，与方案第 6 章接口契约的对应关系：

| 契约接口 | 方案对齐点 |
| :--- | :--- |
| `Tool` / `ToolSpec` | 与方案 6.3 工具统一入口对齐：`function.name` / `function.parameters`，执行返回 `ToolResult(is_error)` |
| `ToolCall` | 与方案 TOOL_CALL / MCP 对齐：`id` / `name` / `arguments(JSON 字符串)` |
| `Sandbox` | 方案 SandboxRuntime 抽象：`exec(argv, cwd, timeoutMs, env)` + `workspace` 根目录 |
| `ApprovalGate` | 方案 RiskGate 抽象：阻塞请求 → `ALLOW_ONCE / ALLOW_SESSION / ALLOW_ALWAYS / DENY / TIMEOUT` |
| `SessionStore` | 方案会话持久化抽象：`append / history / saveTurn` |
| `EventSink` | 方案事件流出口抽象：`emit(type, payload)` |

## 五、冒烟验证记录

在仓库 `runtime/` 目录下，用 `tools/fake_model.py` 脚本化端点跑通端到端：

| 场景 | 结果 |
| :--- | :--- |
| 写文件 → shell 读回 → 收尾 | 3 轮，`stopped_by=done`，prompt 360 / completion 90 tokens，约 190ms，事件流 `turn_start / turn / tool_start / tool_result / text / done` 齐全 |
| 破坏性命令（`rm -rf`） | ShellGuard 判 `DESTRUCTIVE` → 无人应答超时 `TIMEOUT` → 工具返回错误，命令未执行（见 `runtime/README.md` 第七节） |

## 六、缺口补齐路线（建议顺序）

1. **Layer3 接入层**：任务队列、并发上限、会话状态持久化与断点续跑。
2. **Layer4 上下文管理**：历史压缩、超长截断、摘要回收。
3. **补齐平台工具**：`read_image` / `browser_use` / `memory_write` / `memory_get` / `android_calendar` / `android_notify`，含对应 MemoryStore 与 SystemCapability 实现。
4. **Layer5 OffloadGate**：本地能力不足时的卸载判定与降级。
5. **Layer1 RootRouter + Layer2 CloudAgentBus**：LOCAL / CLOUD / HYBRID 三模式决策与信封总线。
6. **互操作适配**：MCP 客户端、A2A 跨设备交通道、App Function 适配层。

## 七、Android 移植提示

`Contracts.kt` 已把平台差异隔离在接口之后：移植到 Android 时，只需替换 `Sandbox`（proot/持久 shell）、`ApprovalGate`（卡片/IM 回调）、`SessionStore`（SQLite）、`EventSink`（SSE/Hub 事件流）、`ModelProvider`（SSE 拼装）五个实现；`AgentLoop` 与工具本身不改动。
