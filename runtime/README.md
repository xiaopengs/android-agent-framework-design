# nm-headless：nanoMuse 端侧 runtime 的无界面抽取

把 nanoMuse Android 端「一个任务怎么跑完」的那部分单独拎出来：回合循环、工具表、静态闸、执行沙箱、会话与事件。
没有 UI、没有通知、没有 WebView、没有无障碍；输入一个任务，输出一条 NDJSON 事件流。

在本机已用 **Kotlin 2.0.21 + JDK 11** 编译为可执行 jar，并用脚本化模型端点跑通端到端冒烟。

> **方案对齐度**：当前为 Layer4 Agent 内核 + Layer5 本地执行/风控的最小可运行垂直切片（纯 LOCAL、无 UI）；五层架构与 10 个 P0 工具的逐项核对、缺口与补齐路线见 [COVERAGE.md](COVERAGE.md)。

---

## 一、它和 Android 端的对应关系

| 本仓库文件 | Android 原实现 | 说明 |
|---|---|---|
| `core/Contracts.kt` | —（原代码散落在 VM 与各 repository 里） | 六个接口：`ModelProvider` / `Sandbox` / `ApprovalGate` / `EventSink` / `SessionStore` / `Tool` |
| `core/AgentLoop.kt` | `ui/chat/ChatViewModel.kt` 的 `runAgentLoop()` | 200 轮上限、tool_call 回灌、打转检测、撞上限收尾 |
| `tools/BuiltinTools.kt` | `tools/{FileRead,FileWrite,FileEdit}Tool.kt` + shell | 4 个基础工具，spec 与执行分离 |
| `guard/ShellGuard.kt` | `guard/ShellGuard.kt` + `RiskGate.kt` + `Grants.kt` | 静态四问 + 阻塞审批（超时即拒绝） |
| `host/LocalProcessSandbox.kt` | `sandbox/{PRootKernel,PersistentShell,ExecutionCoordinator,ShellExecutor}.kt` | 用宿主进程替代 proot；超时强杀、输出带回 |
| `host/Stores.kt` | `data/repository/ChatRepository.kt`（SQLite） | JSONL 追加，一行一条消息 |
| `host/OpenAiProvider.kt` | `provider/openai/OpenAIProvider.kt`（SSE） | 非流式调用；`onText` 已留，补 SSE 即可 |
| `Main.kt` | `debug/HeadlessChatRunner.kt`（先例） | CLI 入口 |

**抽出来的是七样**：循环、工具、闸、provider、存储、事件、装配。
**换掉的是三样**：proot 的 execve 钩子、Android 系统能力（24 个 handler）、WebView。

---

## 二、布局

```
headless-runtime/
├── src/main/kotlin/nanomuse/headless/
│   ├── core/Contracts.kt        # 接口与数据模型
│   ├── core/AgentLoop.kt        # 回合循环
│   ├── tools/BuiltinTools.kt    # shell / file_read / file_write / file_edit
│   ├── guard/ShellGuard.kt      # 四问 + 审批
│   ├── host/LocalProcessSandbox.kt
│   ├── host/Stores.kt           # JSONL 会话库 + NDJSON 事件
│   ├── host/OpenAiProvider.kt
│   └── Main.kt                  # CLI
├── tools/fake_model.py          # 脚本化 OpenAI 端点，用于冒烟
└── out/nm-headless.jar          # 已编译产物（含 kotlin-stdlib）
```

运行期产物（可删）：

```
nm-run/
├── workspaces/<session>/        # 工具的工作目录（对应 /var/minis/workspace）
├── sessions/<session>.jsonl     # 会话消息 + meta
└── events/<session>.ndjson      # 事件流
```

---

## 三、编译与运行

```bash
# 编译（需要 kotlinc + json.jar）
kotlinc src/main/kotlin -cp libs/json.jar -include-runtime -d out/nm-headless.jar

# 1) 起一个脚本化模型（不需要真 key）
python3 tools/fake_model.py 8787

# 2) 跑一条任务
java -cp out/nm-headless.jar:libs/json.jar nanomuse.headless.MainKt \
  --task "写一个 hello.txt 再读回来" \
  --root ./nm-run --session smoke-1 --auto-approve
```

接真模型：

```bash
export NM_BASE_URL=https://api.openai.com/v1
export NM_API_KEY=sk-...
export NM_MODEL=gpt-4o-mini
java -cp out/nm-headless.jar:libs/json.jar nanomuse.headless.MainKt \
  --task "统计 workspace 里有多少行代码" --root ./nm-run --session real-1
```

需要接自己的网关时，把 `NM_BASE_URL` 指到任意 OpenAI 兼容端点（`POST /chat/completions`）。

---

## 四、事件契约

`events/<session>.ndjson`，一行一个 JSON，`type` 区分：

| type | 何时 | 关键字段 |
|---|---|---|
| `turn_start` | 任务开始 | `session`, `text` |
| `turn` | 每轮开始 | `turn`（1-based） |
| `text` | 模型吐字 | `delta` |
| `tool_start` | 工具调用前 | `id`, `name`, `args` |
| `tool_result` | 工具返回 | `id`, `name`, `ms`, `error`, `preview` |
| `approval` | 过闸 | `risk`, `outcome` |
| `loop_detected` | 同一调用重复 ≥3 次 | `tool`, `repeat` |
| `done` | 收尾 | `turns`, `stopped_by`, `prompt_tokens`, `completion_tokens` |
| `error` | 异常 | `message` |

`stopped_by` 三态：`done` / `turn_limit`（撞 200 轮，语义是“可继续”，不是失败）/ `loop_detected`。
上层要做 SSE/WebSocket，只需要把这条 NDJSON 转出去，内核不用动。

---

## 五、换宿主：只改三个实现

| 想换 | 改哪里 | 怎么改 |
|---|---|---|
| 隔离强度 | `Sandbox` 实现 | 换成 bwrap / `sandbox-exec` / 容器；`exec(argv, cwd, timeoutMs)` 的语义不变 |
| 审批通道 | `ApprovalGate` 实现 | 换成 HTTP 回调、IM 卡片、数据库队列；超时必须能触发 |
| 存储 | `SessionStore` | 换成 SQLite / Postgres；表结构照搬 messages + parts |
| 事件去向 | `EventSink` | 换成 SSE / Kafka / WebSocket |
| 设备能力 | 新增 `Tool` | 把宿主命令包成工具，等价于 `android-*` CLI |

**不要改** `AgentLoop`：它是被验证过的那部分；改它等于重新引入 UI 耦合。

---

## 六、已知取舍

- **非流式**：`OpenAiProvider` 一次拿完整 message。要逐字吐字就把 `onText` 接到 SSE 解析处，循环不用动。
- **没有持久 shell**：每次 `exec` 一个新进程，环境变量靠传入。proot 那套“同会话一条 `/bin/sh`、`cd` 保留”没有复刻，需要时给 `Sandbox` 加一个长驻会话。
- **审批无 UI**：命令行读 stdin，180s 无输入按 `TIMEOUT`（拒绝）。
- **无打转之外的预算控制**：没有上下文裁剪与自动压缩（Android 在 `io.github.nanomuse.chat` 里，2,159 行），大任务需要自己接。
- **工具只有 4 个**：`read_image` / `browser_use` / `memory_*` 都依赖平台，按需以 `Tool` 形式加回来。

---

## 七、冒烟记录

| 场景 | 结果 |
|---|---|
| 写文件 → shell 读回 → 收尾 | 3 轮，`stopped_by=done`，360 prompt / 90 completion tokens，243ms |
| 同上但第 2 轮换成 `rm -rf` | 闸判 `DESTRUCTIVE` → 无人应答 `TIMEOUT` → 工具返回错误，命令未执行 |
