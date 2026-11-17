# ios · nanoMuse iOS 端架构知识库

从架构师视角对 **nanoMuse iOS 端**做的源码级拆解。与 [`../qoderwiki/`](../qoderwiki/)（Android）、[`../web/`](../web/)（桌面）同一方法论：一切结论带 `路径:行号`，文档与源码冲突时以源码为准，并把冲突本身作为发现写出来。

**代码基线**：nanoMuse `067384b7e5b3c47a7eeca217895be71493396518`（2026-11-13），versionName 1.0.9 / versionCode 52。全部行号引用以此 commit 为准。

**分析条件已如实声明**：纯静态分析，无 Xcode / 无 macOS / 无真机；`android/deps/ish` 子模块未 checkout，内核内部行为标注为「转述」自 app 侧注释，不冒充实测。

## 头号事实：iOS 的沙箱是进程内用户态内核，不是 ptrace

| | Android | iOS |
| :--- | :--- | :--- |
| 沙箱 | PRoot + Alpine **3.21.3** aarch64 | iSH **ARM64 fork** + Alpine **3.21.0** fakefs |
| 执行方式 | ptrace 拦截，**原生执行** | **进程内用户态内核仿真** |
| 进程模型 | 独立 proot 进程 | **app 自己成为 PID 1**（`ISHKernel.m:599`） |
| 可逆性 | 可停可重启 | **boot 不可逆**（`RootfsManager.swift:21-32`） |
| 内存 | 无专门守卫 | fork 带 **Jetsam 内存守卫**（`ISHKernel.m:627-639`） |

**iSH 是 ARM64 fork，不是上游 x86 iSH**（`android/deps/build_ish.sh:6-9` 指向 `OpenMinis/ish-arm64`、pbxproj `:3370,3442` `GUEST_ARM64=1`、`RootfsManager.swift:46` `currentArch="aarch64"`）。两端 guest 都是 aarch64 Alpine，差异在**执行机制**而非指令集。

## 第二条头号事实：offload 的血缘方向与直觉相反

**iOS 是原版，Android 是移植。**

- iOS：`native_offload_add_handler` 按 `argv[0]` basename **在进程内直接分发 C 函数**，guest 侧是**零字节 stub**（`NativeOffloadUtils.m:248-270`），`noff_dispatch_main_sync` 回主队列，`dprintf` 写 guest stdout fd。**同一进程，不需要 IPC。**
- Android：abstract unix socket + `execve` 重写为 `/bin/cat <tmpfile>`。`NativeOffload.kt` 的注释自认 **"Mirrors iOS"**。
- 注册数：iOS **28**（`ISHKernel.m:656-687`）vs Android **25**（`MinisApp.kt:560-597`），02 章含完整双向映射表。

**可复用原则：桥的复杂度由隔离边界决定，不由功能决定。** 同一个功能，在进程内只需要函数指针，跨进程就需要 socket + 协议 + 鉴权 + 生命周期管理。Android 那套复杂度不是过度设计，是隔离边界的必然代价——但也正因为跨了进程，它引入了 iOS 不存在的攻击面（abstract socket 无文件系统权限位、协议无鉴权字段）。

## 入口

- **[nanoMuse-ios-架构知识库.html](nanoMuse-ios-架构知识库.html)** — 推荐阅读入口。单文件自包含（marked + mermaid 已内联），**离线双击即开，不需要服务器、不需要 CDN**。2 章、11 张图、32 张表，带侧边分章目录、全文搜索、图表点击放大。
- `parts/*.md` — Markdown 源，可在 GitHub 上直接阅读（Mermaid 图 GitHub 原生渲染）。

| 章节 | 内容 |
| :--- | :--- |
| [01-ios-architecture.md](parts/01-ios-architecture.md) | iOS 架构与数据流：iSH ARM64 沙箱、依赖构建链、进程内 offload 分发、agent 循环与并发工具、Provider 九种、三套同步并存 |
| [02-ios-capabilities.md](parts/02-ios-capabilities.md) | 能力全清单：11 个能力域、**Android↔iOS 双向差集**、权限与 entitlements、offload 28↔25 映射表、测试与 CI、休眠汇总 |

## 已确立的关键事实（带行号）

- **`maxAgentTurns = 200`，注释自证对齐 Android**（`AIChatViewModel.swift:73`；对照 `ChatViewModel.kt:412`），触顶走可恢复的 finalize。
- **8 个原生工具双端同集合**：5 个无条件 + 2 个记忆门控 + `read_image` 视觉门控（`ToolDefinitions.swift:30-193`）。`NanoMusePromptDiet` 只瘦身 `browser_use` 的 schema。
- **iOS 工具并发，Android 串行**：`maxConcurrentTools = 10`，TaskGroup fan-out 后**按 tool_use 原序回填**（`ConcurrentTools.swift:25`、`AIChatViewModel.swift:5912-5960`）。
- **双层上下文治理与 Android 同构**：上游 `ContextPolicy` 四档（`ContextPolicy.swift:24-53`）+ `NanoMuseContextBudget` compact **32k** / offload **28k** / target **22k**（`NanoMuseContextBudget.swift:18-38`）。**offload 在 iOS 是活的**（stub + `file_read` 召回，`Offloading.swift:526,543`），而 Android 的 `ContextOffload` 休眠。
- **iOS 无本地 RiskGate 等价物。** 只有上游 `OffloadPermissionManager` 的三档 19 命令表、**30 秒**超时（`OffloadPermissionManager.swift:124-147,264-271`），且 basename 匹配、注释自认 `sh -c` 可绕过（`:205-209`）。Android 是 6 级 RiskClass / 3 档 RiskTier / **180 秒**。注意 **180s 在 iOS 只出现于远程审批路径**（`NanoMuseRemoteApproval.swift:186-187`），不可与本地 30s 混淆。
- **两端隐私能力默认都是放行。** iOS `defaults.integer(forKey:)` 未设置返回 0，而 `bypass = 0`（`OffloadPermissionManager.swift:7,165-168`）；Android 注释明写默认 BYPASS 是 "to mirror iOS"。**「iOS 会问、Android 不问」是错的。** 真正差异是 Android 多一层 RiskGate 动作分级 + 多一个默认拒绝的 INTEGRATIONS 类别。
- **`ToolLoopDetector` 阈值处理是设计分歧**：默认值两端相同（10/10/20/30，historySize 30），但 iOS 用 `max()` 逐级**静默自愈**（`ToolLoopDetector.swift:45-55`），Android 用 4 条 `require()` **fail-fast 抛异常**（`ToolLoopDetector.kt:49-56`）。静默自愈会掩盖配置错误，代价与收益都要写清。
- **例程只能前台跑**：`BGAppRefreshTask` handler 只发提醒（`NanoMuseScheduler.swift:252`），后台续命靠 ~30s grace + location/silent-audio（`BackgroundKeepAliveManager.swift:26-46,216-270`）。**app 睡眠时没有 alarm-exact 触发。**
- **Hands 在 iOS 无对位物**：iOS 不允许一个 app 驱动另一个 app，**App Intents / Shortcuts 是那边的门**（`NanoMuseFirstRun.swift:8,51,174,280` 四处注释自证首跑没有 Hands 页）。
- **三套同步并存**：iCloud CloudKit v1、Sync V2 共享区 + LAN、NanoMuseSync relay REST（tail=300）+ hub WSS（`NanoMuseSync.swift:206-207`、`NanoMuseHub.swift:157-180`）。
- **iOS 独有**：FileProvider 三域扩展、Live Activity / 灵动岛（16.2+，`dlopen` 探测防 iPadOS 16 崩溃）、9 个 AppIntents + 1 个 LiveActivityIntent、9 个 Apple 生态 offload、Action Button 语音入口（`VoicePromptIntent.swift:29`，30s 请求过期）。
- **Android 独有**：本地 RiskGate 全套、nanomuse-hands / 无障碍、Shizuku、通讯录、真后台例程。
- **权限表缺 bluetooth / nfc**（19 命令表无此二项），Android 侧存在 —— offload 权限覆盖缺口。

## 休眠代码与文档冲突（8 条，02 章 §15）

| 类型 | 事实 |
| :--- | :--- |
| 死文件 | `ISHCommandExecutionExample.swift` 在 pbxproj 零引用，不参与编译 |
| 休眠 | `CookieAuditLogger.swift:47-48` 注释 "Call once at app launch"，**全仓零调用方** |
| 声明未接 | `liveactivity-refresh` BGTask id 声明但无注册代码；`remote-notification` background mode 声明但 entitlements 无 `aps-environment` |
| **注释与代码矛盾** | `BackupExporter.swift:8-14` 头注释称「未加密」，实际是 PBKDF2-SHA256 600k + HKDF + AES-GCM（`BackupCrypto.swift:19-23`），且导出器强制加密（`:133-140`）、真实调用在 `:981` |
| 文档滞后 | `docs/ios.md` 整体不覆盖沙箱层（移植对位表 `:976-1010` 是例外，逐行复核全部属实） |
| 能力缺口 | bluetooth/nfc 权限缺失 + `sh -c` 绕过 |
| **能力存在但未接线** | **AlarmKit 系统闹钟 offload 存在，例程调度器却没用**：`apple-alarm` 双闸 iOS 26+（编译闸 `AlarmOffload.m:180` + 运行期 `:249` 直接报错，iOS 16–25 无降级路径），注册无条件（`ISHKernel.m:667`），但 `NanoMuseScheduler.swift` / `NanoMuseRoutineWakes.swift` 全文零 AlarmKit 引用 |

最后一条是本轮最有价值的发现类型：**「例程无 alarm-exact」不是平台绝对无门，是调度器没接这个能力。**（已标推断：AlarmKit 响铃 ≠ 后台执行代码，接线后仍只能提醒 + 用户点开。）

`BackupExporter` 那条则反过来提醒：**读注释会描述出一个不存在的系统。** 协调方一度想据头注释把「备份加密」判为休眠，全量搜索后证明子研究员是对的、注释是陈旧的。

## 可重现

### 1. 重建 HTML

```bash
cd ios
./vendor/fetch_vendor.sh          # 首次：拉 marked + mermaid（不入库）
python3 tools/build_site.py       # 产出 nanoMuse-ios-架构知识库.html
```

实测：本目录已提交的 HTML 与用上述命令重建的产物**逐字节相同**。

### 2. 校验每张图真的渲染出来

```bash
pip install playwright && playwright install chromium
python3 tools/check_mermaid.py parts/*.md
python3 tools/verify_render.py
```

`verify_render.py` 实测输出：

```
chapters in DOM: 2
  ch0: diagrams=9 rendered_svg=9 errors=0  [OK]
  ch1: diagrams=2 rendered_svg=2 errors=0  [OK]
TOTAL rendered SVG: 11   render failures: 0
console/page errors: 0
```

### 3. 上游测试的真实执行情况（这是一条工程决策，不是纯疏漏）

`.github/workflows/ios-check.yml:42-58` **不跑 xctest**，而是把被测 Swift 文件与 `tests/ios/` 下的检查文件一起用 `swiftc` 编成独立可执行文件再运行，共三个：`NanoMuseCrashEvidence` / `NanoMuseAnthropicPrompt` / `NanoMuseDeviceConversations`。另有 `NanoMuse/` 零警告门（`:113-117`）。

**这与我们给 Android 端做的 JVM 验证台是同一个模式**：绕开平台 SDK，只把纯逻辑类抽出来独立编译运行。

- **收益**：CI 可在 Linux runner 上跑，不需要 macOS runner 的排队与计费，检查快、无模拟器依赖。
- **代价**：55 个 XCTest 文件里**只有 3 个模块的逻辑被 CI 覆盖**，其余（含 `BackupCryptoTests.swift` 这种密码学正确性测试）只在有人本地开 Xcode 时才跑。**密码学实现的回归保护不在 CI 里。**
- **重建要点**：纯逻辑抽取验证值得抄，但必须同时保证平台侧测试有一条定期执行的通路。**判定「测试覆盖」要以 CI 实际执行清单为准，不以测试文件数为准。**

本目录自身未运行任何 Swift 测试（无 macOS / 无 Xcode），已如实声明。

## 相关目录

- [`../qoderwiki/`](../qoderwiki/) — Android 端架构知识库（7 章 + 可重跑 JVM 验证台，19 类 / 145 测试全绿）
- [`../web/`](../web/) — 桌面端架构知识库（5 章，dsh + 插件 bundle + Electron 外壳）
- `../requirements/` — 三端反向需求拆解（从既有实现反推需求与验收标准），另一次提交