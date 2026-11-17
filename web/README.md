# web · nanoMuse 桌面端架构知识库

从架构师视角对 **nanoMuse 桌面端**做的源码级拆解。与 [`../qoderwiki/`](../qoderwiki/)（Android 端）同一方法论：一切结论带 `路径:行号`，文档与源码冲突时以源码为准，并把冲突本身作为发现写出来。

**代码基线**：nanoMuse `067384b7e5b3c47a7eeca217895be71493396518`（2026-11-13），versionName 1.0.9 / versionCode 52。全部行号引用以此 commit 为准。

> 目录名叫 `web` 是按需求约定的落点。**注意仓库里还有一个 `web/` 目录属于上游源码，那个不是桌面 UI**（见下文「四个必须先辨析的概念」第 1 条）。本目录是知识库，不是应用代码。

## 头号事实：桌面端 nanoMuse 写了零行 agent 循环

桌面端 = **DeepSeek Harness（dsh，上游 MIT）+ `dsh-nanomuse` 插件 bundle + 自家 Electron 外壳**。loop / tools / skills / compaction / sub-agents / MCP / web UI 骨架全是 dsh 的，nanoMuse 只提供 account / face / Hands / Reach / rooms 等插件与一层 UI 化妆。

三重代码证据（不是引用 README）：

1. `harness/dsh-nanomuse/presets/nanomuse.patch.yml:11-162` 是 dsh Standard 预设的**逐字克隆**，仅 `:166-199` 追加 5 行 nanoMuse 插件。
2. delegate 的执行就是 `harness/dsh-nanomuse/src/task.ts:205` 的 `agent.followup(createUserMessage(frame(...)))` —— 循环由 dsh 跑；rooms 批处理同理（`rooms.ts:1723`）。
3. Electron 外壳只做一件事：用 `ELECTRON_RUN_AS_NODE` 拉起 dsh 的 `bin.js`（`harness/desktop/src/main.ts:269-330`，spawn 在 `:316-318`），再用正则等 stdout 里的 web URL 就绪。

对照 Android：循环是 12,820 行 `ChatViewModel` 里的一个 suspend 函数 `runAgentLoop`。**把 Android 的心智模型平移到桌面端会全面误判工作量与可控边界**，这是本目录存在的第一个理由。

## 入口

- **[nanoMuse-desktop-架构知识库.html](nanoMuse-desktop-架构知识库.html)** — 推荐阅读入口。单文件自包含（marked + mermaid 已内联），**离线双击即开，不需要服务器、不需要 CDN**。5 章、19 张图、40 张表、128 段源码摘录，带侧边分章目录、全文搜索、图表点击放大。
- `parts/*.md` — Markdown 源，可在 GitHub 上直接阅读（Mermaid 图 GitHub 原生渲染）。

| 章节 | 内容 |
| :--- | :--- |
| [00-overview.md](parts/00-overview.md) | 总纲：归属总表、全局架构图、四个概念辨析、**199 行 preset 注册表逐行清理**（29 行 dsh + 5 行 nanoMuse）、Android↔桌面对照 |
| [01-electron-shell.md](parts/01-electron-shell.md) | Electron 外壳：启动流程、preload 暴露面、三平台窗口差异、mac 权限链、Windows 更新与退出保护 |
| [02-host-plugins.md](parts/02-host-plugins.md) | dsh 宿主插件层：账号/relay、hub 动作面、Reach 七工具、delegate 审批回传、rooms、风控与准入、Windows 沙箱三件套 |
| [03-client-ui.md](parts/03-client-ui.md) | 浏览器侧 UI：seat 占用台账（54 行）、76 个组件全清单、接管态状态机、设置导航、两条 SSE 通道 |
| [04-hands-runtime.md](parts/04-hands-runtime.md) | Hands 与 Python runtime：真实 schema 原文、MCP 四段接入链、**确认票据两档信任级别**、坐标唯一转换点、副屏边界 |

## 四个必须先辨析的概念

不先钉死这四条，整份分析都会跑偏：

1. **三个 "web" 不是同一个东西。** 上游仓库的 `web/` 是 `nanomuse serve` 的移动/浏览器控制台（决定性证据：`web/vite.config.ts:13-17` 的 `outDir: "../nanomuse/server/static"`），**不是桌面 UI**；真正的桌面 UI 是 dsh 自带 web 客户端（上游 npm 包，骨架）+ `harness/dsh-nanomuse/src/client/**`（nanoMuse 的占座化妆层）。两套 URL 空间不相交，功能面却高度重叠（hands/holds/approvals/goals/coding/avatar 各有两套 UI），是**两套并行维护的前端**。
2. **两个 "runtime" 不是同一个东西。** Python runtime 曾是宿主（0.1.19–0.1.29），现在**只服务 Hands**；桌面端的宿主进程是 dsh 的 Node 进程。
3. **Windows 沙箱归 dsh，不是 nanoMuse 自研。** `windows-pwsh-sandbox.ts` 是 `class NanomuseWindowsPwshSandbox extends SandboxPwshExecutor`（import 自 `@deepseek-ai/dsh-pwsh-sandbox`），ACL 降权策略本身未改；nanoMuse **只加了一个 Electron 跳板**。误判这条会得出「桌面端可以独立演进自己的沙箱」的错误结论。详见 `parts/00-overview.md` §4.3（该节含一处作者自我更正）。
4. **两个 "Hands" 不是同一份实现。** 宿主侧 `hands-tools.ts` 走 MCP 挂 Python 工具；Python 侧 `nanomuse/computer/` 是手/眼/坐标/窗口。同一份工具代码在两个宿主下**行为不同**（见下文实测事实第 3 条）。

## 已确立的关键事实（带行号，非读文档推断）

- **preset 是全量拷贝 → 真实维护债。** cordis 的 `patch` 语义是**整行替换 config**，上游新增字段会被静默丢弃；每次 dsh 升版必须人工 diff。另 `harness/README.md:231` 声称 dsh "carried unmodified"，但 `scripts/prepare-dsh.mjs:84-85` 构建期打了两个 lib 补丁（capacity-defaults、cleared-session-access）——**文档与源码冲突**。
- **四处有意关闭**（`presets/nanomuse.patch.yml:119,127,141,160`）。其中禁用 `tool-plugin-manager` 是为了**防止模型给自己发权限**，是有意的安全边界，不是遗漏。
- **Hands 确认门有两档信任级别，取决于启动方式。** 设了 `NANOMUSE_MCP_CONFIRM`（桌面端每次启动一个随机 secret 与插件共享）时，`confirmed` 必须是 HMAC-SHA256 票据，模型自写 `true` 被拒（`nanomuse/bridge/mcp_server.py:116-117`）；**没设该变量时模型的 `confirmed: true` 被采信**（`:114-115`），闸门退化为宿主 approval policy。**安全性不可从工具名推断，只能从启动环境推断。**
- **`computer_task` 有意不暴露。** 理由写在 `mcp_server.py:7-8`：宿主自己有循环，再暴露会循环套循环。具体形态可证：它必需依赖 operator（`nanomuse/tools/computer.py:499-500`），而那个 operator **带自己的模型**（`nanomuse/app.py:505-516`）。但两个已暴露工具的 description 仍在劝模型用 `computer_task`（`computer.py:78,129`）→ **悬空推荐，会诱发真实无效调用**。
- **桌面路径上 Python 侧同时失去三件事。** `hands_tools()` 不传 `holds`/`app_gate`（`mcp_server.py:49-52`），于是 `hand_over` 恒返回「not available here」、`wait_hold` 恒返回空串、按应用询问失效；真正的等待与授权在宿主（`cloud.ts:1120,1127-1139,1144`）。运行时自己的装配**两个都传**（`app.py:499,518-521`），形成干净对照。`surface.py:127-147` 那段完整 C1 实现在桌面路径上是死路径。
- **拒绝语义靠字符串协议。** `refusals.ts:268-287` 把 16 类拒绝映射成 `nanomuse/<kind>` 使 dsh 永不重试，但宿主识别拒绝靠文本前缀（`cloud.ts:586`），且 mcp 1.x 分支丢 `isError`（`mcp_server.py:277`）。结构化错误通道缺失。
- **hub 实际应答 23 个动作**（文档说 9 个）；**Reach 恰好 7 个工具**，`docs/every-device.md:53` 列的 `device_get`/`device_put` 在源码里不存在。
- **副屏看不到也点不到**：整屏模式只取主屏（`screen.py:131`、`operator.py:18`），`scaleFactor` 与 `display{id,bounds}` 被 `operator_capture` 丢弃（`operator.py:272-283`）。文档未提。
- **`ELECTRON_RUN_AS_NODE` 会向孙进程传播**，跳板两侧都要清（`windows-pwsh-sandbox.ts:38-66` 归一化为 1，`windows-acl-runner.ts:14-18` 进上游 runner 前再清一次）。这是可复用的 Electron 打包踩坑经验。

休眠/死代码与文档冲突合计：02 章 §13 九行汇总、03 章 §13 九行冲突 + 九行休眠、04 章 §13 九行冲突 + 八行死代码。典型：Sentinel 实为 phase 4 且不在 bundle、GrantTier 的 notice 档休眠、`preload.ts` 的 `relayHosts` 是死面、`web/src/desktop.ts` 死桥（`isDesktopApp()` 恒 false）、live stage 已死（`shell.overlay` 改由 Trajectory 占用）、设置导航 order 被 PRIMARY 白名单架空。

## 可重现

### 1. 重建 HTML（无需网络，若已有 vendor）

```bash
cd web
./vendor/fetch_vendor.sh          # 首次：拉 marked + mermaid（不入库）
python3 tools/build_site.py       # 产出 nanoMuse-desktop-架构知识库.html
```

实测：本目录已提交的 HTML 与用上述命令重建的产物**逐字节相同**。

### 2. 校验每张图真的渲染出来（不是只做语法检查）

```bash
pip install playwright && playwright install chromium
python3 tools/check_mermaid.py parts/*.md      # 逐块真渲染
python3 tools/verify_render.py                 # 整站真渲染 + 控制台错误 + 导航
```

`verify_render.py` 实测输出：

```
chapters in DOM: 5
  ch0: diagrams=1 rendered_svg=1 errors=0  [OK]
  ch1: diagrams=3 rendered_svg=3 errors=0  [OK]
  ch2: diagrams=5 rendered_svg=5 errors=0  [OK]
  ch3: diagrams=6 rendered_svg=6 errors=0  [OK]
  ch4: diagrams=4 rendered_svg=4 errors=0  [OK]
TOTAL rendered SVG: 19   render failures: 0
console/page errors: 0
```

### 3. 上游测试的真实执行情况

在 `/tmp` 副本里跑 `node --test`，**未装 Electron、未联网、未 npm install、未改动仓库**：

| 套件 | tests | pass | fail | skip |
| :--- | ---: | ---: | ---: | ---: |
| `harness/desktop/tests` | 152 | 144 | 7 | 1 |
| `harness/dsh-nanomuse/tests` | 313 | 308 | 1 | 4 |

8 个失败**全部是依赖缺失**（electron-updater、builder-util-runtime、`@deepseek-ai/dsh-llm-pi-ai`、typescript 原生版无经典 JS API），非逻辑失败；skip 为 Windows-only。**fences 跨平台逐字节相等**（补齐 Android/iOS fixture 后）、sync、hub、persistent-turns 全绿，是章节的正面证据。

Python 侧纯逻辑复刻实测：坐标模块 30/30、macOS 窗口模块 37/37 通过（真实值如 `picture_size(3840,2160,1600)=(1596,896)`）。

**未实测并已写明原因**：`task.test.mjs`（运行时 import `@deepseek-ai/dsh-llm` 失败）、`windows-pwsh-sandbox.test.mjs`（Windows-only）、全部前端测试、真实 Electron / dsh Host / `nanomuse mcp` 进程。没有伪造任何输出。

## 方法论（与 qoderwiki 一致）

- 一切结论带 `路径:行号`，摘录真实源码，不编造。
- 文档与源码冲突时以源码为准，**并把冲突本身作为发现写出来**——这是最高价值的产出类型。
- 文档说「已做」但代码里找不到调用方的，按休眠处理；反过来，注释说「未做」但代码里做完了的（如 iOS 备份加密），也要以代码为准。
- 每章结尾两张表：**证据索引表**与**未确认问题清单**（说清缺什么证据，不用推测填空）。
- 未实测的推断显式标注「推断」。

## 相关目录

- [`../qoderwiki/`](../qoderwiki/) — Android 端架构知识库（7 章 + 可重跑 JVM 验证台，19 类 / 145 测试全绿）
- [`../ios/`](../ios/) — iOS 端架构知识库（2 章，iSH ARM64 沙箱与能力全清单）
- `../requirements/` — 三端反向需求拆解（从既有实现反推需求与验收标准），另一次提交