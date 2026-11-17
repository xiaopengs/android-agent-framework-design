# 01 · Electron 外壳：一个不含 agent 循环的桌面进程

> 代码基线：`/data/repo`，commit `067384b7e5b3c47a7eeca217895be71493396518`（2026-11-13），versionName 1.0.9。
> 本章覆盖 `harness/desktop/`（src 23 个 .ts，共 5493 行）及其 scripts、tests、electron-builder.yml。
> 所有行号均为仓库根相对路径。文档与源码冲突时以源码为准，冲突本身作为发现列出。

## 本章回答什么问题

1. nanoMuse Desktop 的 Electron 主进程到底做了什么、没做什么？——它**没有实现 agent 循环**。它是「DeepSeek Harness（dsh）+ dsh-nanomuse 插件束 + 一层自写的 Electron 外壳」。循环、工具、技能、压缩、子代理、MCP、Web UI 全部来自 dsh；外壳只做进程托管、窗口、系统权限、屏幕操作的外设层、更新与退出保护。这与 Android 端形成尖锐对比：Android 的循环是 12820 行 `ChatViewModel` 里的一个 suspend 函数，桌面端把整个循环外包给了一个 pin 死版本（0.2.0-rc.2）的上游运行时。
2. Electron 如何把 dsh 拉起来、看着它、关掉它？三个平台的窗口差异在哪里？
3. preload 暴露了什么表面，安全边界画在哪里？哪些表面是死的？
4. macOS 为什么要一个独立的 helper app？Linux 的 glow 为什么要三层防御？Windows 的更新为什么要重写 electron-updater 的 quit？
5. 构建期对 dsh 做了什么？——README 说「carried unmodified」，源码说不是。这本身是本章最重要的文档冲突。

先给结论性的证据。`docs/desktop-muse.md:8-12` 自己承认：

> The desktop app here is **DeepSeek Harness plus the `dsh-nanomuse` bundle** … dsh brings the loop, the tools and a complete web app with seats a plugin can take.

而源码层面的铁证是启动函数：外壳 spawn 的不是自己的 agent，而是 dsh 的 `bin.js`（`harness/desktop/src/main.ts:269-318`，全文见下节）。整个 `harness/desktop/src` 里没有任何一行模型调用、消息循环或工具分发代码——`operator.ts` 移动鼠标，但决定「移到哪」的循环在 dsh 里，经由 Python runtime 的 MCP 工具回到模型。

### 全景：一个窗口、三个进程、两条回路

```mermaid
flowchart TB
    subgraph ElectronMain[Electron 主进程 main.ts]
        Boot[boot 与 startHost]
        Win[主窗口 加载 dsh Web UI]
        Op[operator-server 回环 HTTP]
        Glow[glow 与 capsule 覆盖窗]
        Helper[mac-helper 客户端]
        Upd[WindowsUpdates]
        Exit[ProtectedExit]
    end
    subgraph HostProc[Host 进程 ELECTRON_RUN_AS_NODE]
        DSH[dsh bin.js 循环 工具 技能 压缩 子代理]
        Bundle[dsh-nanomuse 插件束]
        Web[dsh web app 回环端口]
    end
    subgraph Runtime[Python runtime 子进程]
        MCP[nanomuse mcp stdio]
    end
    HelperApp[nanoMuse Computer Use.app 独立 TCC 身份]
    Boot -->|spawn process.execPath| HostProc
    Win -->|loadURL token| Web
    Bundle -->|stdio MCP 客户端| MCP
    MCP -->|NANOMUSE_OPERATOR_URL| Op
    Op -->|macOS 截图与输入| Helper
    Helper -->|open -n -g -a| HelperApp
    Op -->|mark 坐标| Glow
    Exit -->|work gate 与 stop| Bundle
    Upd -->|handoff| Exit
```

注意两条回路：**模型回路**（dsh 循环 → MCP 工具 → runtime → operator → 屏幕）完全绕开 Electron 的渲染进程；**界面回路**（dsh web app → preload 桥 → 主进程）只传权限、偏好、覆盖窗状态，不传模型消息。渲染进程里跑的 UI 是 dsh 的 web client 加上 nanoMuse 的 client bundle（后者在 `src/client/**`，不在本章范围）。

---

## 1. 启动链：从单实例锁到 dsh 的地址行

### 1.1 startHost：外壳最重要的 60 行

`harness/desktop/src/main.ts:269-330`（节选，env 与 spawn 部分）：

```ts
function startHost(): Promise<string> {
  const dshDir = join(resourcesDir(), "dsh");
  const bin = join(dshDir, "node_modules", "@deepseek-ai", "dsh", "lib", "bin.js");
  if (!existsSync(bin)) {
    return Promise.reject(new Error(`the harness is not bundled: ${bin} is missing (run scripts/prepare-dsh.mjs)`));
  }
  const home = harnessHome();
  mkdirSync(home, { recursive: true });
  ensureProfile(home, dshDir, log);
  return new Promise<string>((resolve, reject) => {
    Promise.all([hostPort(home), ensureOperator()])
      .then(([port, operatorServer]) => {
        const env: NodeJS.ProcessEnv = {
          ...process.env,
          ELECTRON_RUN_AS_NODE: "1",
          DSH_HOME: home,
          // One secret per launch, shared by the bundle and the runtime's `nanomuse mcp`
          // server: a hands step the person must agree to is confirmed with a ticket only
          // the bundle can make (after the permission card), never by the model's own word.
          NANOMUSE_MCP_CONFIRM: randomBytes(24).toString("hex"),
        };
        if (operatorServer) {
          env.NANOMUSE_OPERATOR_URL = operatorServer.url;
          env.NANOMUSE_OPERATOR_TOKEN = operatorServer.token;
        }
```

以及真正的 spawn（`main.ts:316-318`）：

```ts
        const args = ["--expose-internals", bin, PROFILE, "--no-open", "--port", String(port)];
        log(`host: ${process.execPath} ${args.join(" ")} (DSH_HOME=${home}${env.NANOMUSE_PY ? `, NANOMUSE_PY=${env.NANOMUSE_PY}` : ""})`);
        const proc = spawn(process.execPath, args, { cwd: homedir(), env, stdio: ["ignore", "pipe", "pipe"], windowsHide: true });
```

逐点拆：

- **`process.execPath` 是 Electron 自己**。`ELECTRON_RUN_AS_NODE=1` 让 Electron 二进制退化为纯 Node，用来跑 dsh 的 `bin.js`。不另装 Node、不另发二进制——安装包只带一个可执行文件。`--expose-internals` 是 dsh 需要的 Node 内部 API 开关。
- **`PROFILE` 是常量 `"nanomuse"`**（`harness/desktop/src/profile.ts:5`）。dsh 以 profile 为单位组合插件树，profile 的构成见 §2。
- **`NANOMUSE_MCP_CONFIRM`**：每次启动一个 24 字节随机 secret，同时进入 Host env（被 dsh-nanomuse 插件读到）和 runtime 的 MCP 子进程（继承 env）。用途：Hands 的一步被人批准后，插件用这个 secret 造一张「确认票」重新派发该步，runtime 只认票不认模型的话。这是「模型不能自己说自己被批准了」的机制化表达（第 02 章 §7 有插件侧的另一半）。
- **`NANOMUSE_OPERATOR_URL/TOKEN`**：operator 的回环地址与 bearer，交给 runtime 的 MCP server 反连主进程（§5）。
- 代理 env（`main.ts:300-305`）：`proxyEnv(prefs.proxy, trustedRelayHosts())` 写 `HTTP_PROXY/HTTPS_PROXY/ALL_PROXY/NO_PROXY/NODE_USE_ENV_PROXY`，Node fetch 与 Python httpx 各取所需。
- `NANOMUSE_PY`（`main.ts:307-315`）：打包的 runtime 可执行文件路径；不存在时**大声写日志**而不是静默降级——「the hands are off」明说。

### 1.2 启动等待：只有退出才算失败

`harness/desktop/src/host-start.ts:17-24`：

```ts
/** After this long without the address the loading page says the start is slow, not failed. */
export const SLOW_MS = 120_000;
/** After this long the shell asks whether to keep waiting; another CAP_MS per "Keep waiting". */
export const CAP_MS = 10 * 60_000;
/** How many of the Host's last lines the loading page and the dialog show. */
export const TAIL_LINES = 8;
/** The line dsh-web-app prints once the web app is served (with the one-time token). */
export const ADDRESS = /dsh web: (http:\/\/127\.0\.0\.1:\d+\/\?token=\S+)/;
```

设计立场写在文件头（`host-start.ts:10-15`）：两分钟没出地址只说「慢」，十分钟才问人「还等吗」，**Host 活着就永不超时失败**——这是 #217（慢机器首启被误判失败）的修复。成功的判据是 stdout 里的一行正则：dsh-web-app 打印的带一次性 token 的回环地址。外壳与 Host 之间的「就绪协议」就是这行文本，没有 IPC、没有握手端口。

### 1.3 端口记忆

`harness/desktop/src/host-port.ts:18,40-55`：默认 38421（connectors 的 OAuth 回环占 38417，见第 02 章），候选顺序 `[上次, 默认, 0]`；**只有端口能长期住下时才写记忆**，fallback 到随机端口时故意保留旧记忆，等占端口的人走了再回来。端口记忆的意义：provider row、cookie、书签都指向一个 origin，端口漂移会让上次写入的配置失效。

### 1.4 启动时序

```mermaid
sequenceDiagram
    participant App as Electron app
    participant Main as main.ts
    participant Prof as profile.ts
    participant Host as dsh Host 进程
    participant Win as 主窗口
    App->>Main: requestSingleInstanceLock 与 whenReady
    Main->>Main: boot 读 prefs 建 splash
    Main->>Prof: ensureProfile 写 manifest 与 junction
    Main->>Main: hostPort 选端口 ensureOperator 起回环服务
    Main->>Host: spawn ELECTRON_RUN_AS_NODE bin.js nanomuse --no-open --port
    Host-->>Main: stdout 打印 dsh web 地址行 带 token
    Main->>Win: loadURL 地址 然后从历史里抹掉 splash
    Note over Main,Host: 120 秒未出地址只提示慢 600 秒才问人
    Host-->>Main: 进程退出
    Main->>Main: hostStopped 自动重启一次 再失败弹对话框
```

单实例与生命周期入口在 `main.ts:1966`（`requestSingleInstanceLock({ version })`，带版本号——旧实例会让位给新版本的启动）与 `main.ts:1989`（`whenReady`）。`boot()` 在 `main.ts:1834`。

---

## 2. profile：外壳如何把插件束「塞进」dsh

dsh 的组合模型：profile 目录下三个小文件（manifest、空根 `cordis.yml`、用户补丁层 `cordis.patch.yml`）加一个链接。`harness/desktop/src/profile.ts:9,18-61`：

```ts
const DEFAULT_BUNDLES = ["@deepseek-ai/dsh-base", "@deepseek-ai/dsh-web-app", "@deepseek-ai/dsh-experimental-schedule-bundle", BUNDLE];
```

`ensureProfile` 每次启动执行：

- manifest 里 `dependencies["dsh-nanomuse"] = "*"`，`dsh.profile.bundles` 只在**从未有过选择**时播种默认四束；空列表和缺失的可选束都被视为用户的 deliberate choice（`profile.ts:33-35` 注释）。这意味着用户在插件管理页关掉 schedule，重启不会被外壳偷偷打开。
- `cordis.yml` 是空数组 `[]`，注释明说「树以补丁方式组合：先每个 bundle 层，再 cordis.patch.yml」。
- 关键一步：`node_modules/dsh-nanomuse` → resources 里那份拷贝的符号链接（Windows 用 junction），每次启动 `relink`（`profile.ts:57-59`）。dsh 的 Loader 按 node_modules 解析找 bundle，这个链接就是「外来插件束」的入口。

`profile-link.ts` 值得单独一提：它是少数在主进程里**同步阻塞等待**的代码。`profile-link.ts:44-51`：

```ts
/**
 * Wait in place. The boot is a synchronous chain, so this wait is too, and `Atomics.wait` is
 * the one sleep that works on the main thread of Node. Under a second here is worth more
 * than a start that fails and needs a second launch.
 */
function pause(ms: number): void {
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
}
```

重试节奏 `[0, 100, 250, 500]` ms（`profile-link.ts:54-55`），针对 Windows 上杀毒/索引器短暂持有句柄导致的 EBUSY/EPERM。`relink`（`profile-link.ts:108-129`）失败两次后把「挡路的是什么」写进错误信息，让人手工删。这是典型的「外壳替上游擦平台屁股」的代码：dsh 假设 node_modules 是 npm 装出来的，外壳要让一个随 app 移动的 resources 目录看起来像 npm 装的。

---

## 3. 窗口与三平台差异

`harness/desktop/src/main.ts:1764-1783`（createWindow 的骨架）：

```ts
function createWindow(): BrowserWindow {
  const win = new BrowserWindow({
    width: 1280,
    height: 840,
    minWidth: 960,
    minHeight: 620,
    title: "nanoMuse",
    icon: process.platform === "darwin" ? undefined : nativeImage.createFromPath(iconPath()),
    backgroundColor: nativeTheme.shouldUseDarkColors ? BASE_DARK : BASE_LIGHT,
    show: false,
    autoHideMenuBar: process.platform !== "darwin",
    // macOS: no title bar, the traffic lights sit over the rail (Muse's window); the
    // web app marks its drag regions once the preload tells it the platform.
    ...(process.platform === "darwin" ? { titleBarStyle: "hiddenInset" as const, trafficLightPosition: { x: 16, y: 18 } } : {}),
    // Windows: the same window, with the system's own caption buttons drawn over the top
    // right corner (the overlay); the page leaves them room. Linux keeps its system bar —
    // every desktop draws it differently and the overlay is not available there.
    ...(process.platform === "win32" ? { titleBarStyle: "hidden" as const, titleBarOverlay: overlayColors() } : {}),
    webPreferences: { contextIsolation: true, nodeIntegration: false, sandbox: true, spellcheck: false, preload: join(__dirname, "preload.js") },
  });
```

三平台的frameless策略一句话：**mac 用 hiddenInset 把红绿灯嵌进 rail 顶部（16,18），Windows 用 hidden + titleBarOverlay 让系统画自己的最小化/关闭按钮（随主题换色，`main.ts:975`），Linux 保留系统标题栏**——注释直说原因：每个桌面环境画的栏都不一样，overlay 在那不可用。`backgroundColor` 跟随系统深色，resize 不闪白。

导航与权限是纯函数化的（为了免 Electron 测试）：

- `navigation.ts:16-23`：同源比较用**解析后的 URL origin**，注释里留着攻击样本——前缀比较曾放过 `http://127.0.0.1:38421@evil.example/`（Host origin 成了别人地址的 userinfo 部分）。
- `navigation.ts:34-38`：`will-navigate` 三分支——Host origin 与自己的 file: 页面放行，http(s) 交系统浏览器，其余丢弃。`setWindowOpenHandler` 一律 deny（`main.ts:1790-1793`）。
- `navigation.ts:41-55`：media 权限只给**同源、仅 audio** 的请求——dictation 用麦克风，摄像头永远不给。
- `window-permissions.ts:4-7`：`clipboard-sanitized-write` 只给主框架同源页面；clipboard 读对页面脚本永远不可用（`window-permissions.ts:9` 注释）。

### preload：唯一的桥，以及一个死表面

`harness/desktop/src/preload.ts:20-107` 定义 bridge 并 `contextBridge.exposeInMainWorld("nanomuseHarness", bridge)`（`:107`）。文件头（`preload.ts:1-6`）说明契约：nanoMuse client bundle 找 `window.nanomuseHarness`，纯浏览器里（dsh 单跑）它不存在，bundle 就不显示任何需要它的东西。表面清单：

| 分组 | 成员 | 主进程落点 |
|---|---|---|
| 平台 | `platform`、`info()` | 常量 / app 版本 |
| 权限 | `permissions()`、`requestPermission`、`openPermissionSettings`、`guidePermissions`、`relaunch` | mac-permissions / mac-helper |
| 窗口 | `setTheme`、`setContentProtection`、`keepAwake`、`openExternal`、`reveal`、`reportBug` | main.ts registerBridge（924-1009） |
| 偏好 | `prefs()`、`setPrefs(patch)`、`restartHost()` | main.ts:977-999 |
| 覆盖窗 | `setOverlay(state)`、`onOverlayAction` | glow / capsule（1011-1553） |
| 更新 | `updates.snapshot/check/download/restart/setAutoDownload/subscribe` | WindowsUpdates |
| 快捷键 | `onQuickChat` | globalShortcut |

渲染进程 `sandbox: true` + `contextIsolation: true`，bridge 全部是 invoke/send 封装，没有暴露任何 Node 能力。另外 preload 在 document 上打 `data-nm-platform` 标记（`preload.ts:114-118`），让 client bundle 自己加红绿灯留白与拖拽区——注释明说不复用 dsh 的 `data-platform`，因为那会期待 dsh 自己的桌面键盘桥。

**死表面（发现）**：`setPrefs` 的类型允许 `relayHosts`（`preload.ts:55` 的 Pick、`:101` 的 `DesktopPrefs.relayHosts?`），但主进程的 `nanomuse:prefs:set` handler（`main.ts:978-997`）只处理 `openAtLogin/menuBar/quickChat/quickChatKey/proxy` 五个键——`relayHosts` 传进去会被静默丢弃。而 `proxy.ts:67-71` 明确拒绝渲染进程输入：

```ts
/** Relay exceptions come from the shell's trusted launch configuration, never renderer input. */
export function trustedRelayHosts(env: NodeJS.ProcessEnv = process.env): string[] {
  const configured = env.NANOMUSE_CLOUD_URL;
  return configured ? [configured] : [];
}
```

即 NO_PROXY 的 relay 例外只认启动 env。preload 里的 `relayHosts` 是一个从未接线的预留口（推断：曾打算让插件把自建 relay 的 host 报给外壳，后来改走 env；类型残留未清）。

---

## 4. Host 的生死：stopHost、自动重启与保护性退出

### 4.1 杀进程树

`main.ts:439-456`：

```ts
function stopHost(): Promise<void> {
  const proc = child;
  if (!proc || proc.exitCode !== null) return Promise.resolve();
  return new Promise((resolve) => {
    const done = () => resolve();
    proc.once("exit", done);
    if (process.platform === "win32" && proc.pid) {
      // the Host's own children (shells, the runtime for the hands) go with it
      spawnSync("taskkill", ["/pid", String(proc.pid), "/T", "/F"], { stdio: "ignore" });
    } else {
      proc.kill("SIGTERM");
      setTimeout(() => {
        if (proc.exitCode === null) proc.kill("SIGKILL");
      }, 3000).unref();
    }
    setTimeout(done, 5000).unref();
  });
}
```

Windows 没有进程组信号，直接 `taskkill /T /F` 连坐子孙（shell、runtime）；POSIX 先 TERM 后 3 秒 KILL。5 秒兜底 resolve——外壳不让自己被一个僵尸 Host 卡死在退出路上。

`hostStopped`（`main.ts:606-642`）：**只自动重启一次**（`restarts === 0`），再挂就弹对话框，detail 是 Host stderr 的最后 1500 字符，三个按钮：重启 / 复制详情 / 退出。崩溃循环不会变成无限重启循环。

### 4.2 保护性退出：先问工作，再问人

退出不是 `app.quit()` 一把梭。`harness/desktop/src/protected-exit.ts:56-58` 定义 `STOP_TIMEOUT_MS = 15_000`、`STOP_ROUNDS = 3`；`requestExit`（`:86-124`）的状态机：

```mermaid
stateDiagram-v2
    [*] --> idle
    idle --> handoff: requestExit 或 requestRestart
    handoff --> gateClosed: work.setGate(false)
    gateClosed --> idle: 无工作 重新开闸 直接退出
    gateClosed --> asking: 有工作 弹确认
    asking --> idle: 人拒绝 开闸 工作继续
    asking --> stopping: 人确认
    stopping --> stopping: 三轮 stopAll 每轮 15 秒上限
    stopping --> complete: 工作清空或三轮用尽
    complete --> [*]: 停 Host 然后退出或装更新
```

（图中「gateClosed」等是状态名；闸门的对端是插件侧 `cloud.ts` 的 `setWorkGate/stopAllWork`，见第 02 章。）

外壳怎么知道 Host 里有没有工作？`host-work.ts`：它**以浏览器身份**调插件的 HTTP API。`host-work.ts:29`（`WORK_PREFIX = "/nanomuse/cloud/work"`）、`:47-64` 用启动 URL 里的一次性 token 换 `dsh-auth-*` session cookie，401 就重铸一次再试。工作四类：`reply | computer | task | approval`（`host-work.ts:31`）。这条「外壳 → Host 的 /nanomuse/cloud/work」回路说明外壳对 Host 的内部状态**零共享内存**，一切经回环 HTTP，与浏览器同级。

`before-quit`（`main.ts:2043-2061`）兜底：系统注销/崩溃路径不走保护性退出，日志明说「did not go through the protected exit」；有 Host/operator/helper 活着就 `preventDefault`，等 `stopHost + stopOperator` 完成再真退。Windows 注销可能跳过 before-quit，所以窗口另有 `session-end` 标记（`main.ts:1808-1809`）。

---

## 5. Hands 的外壳半边：operator、mac-helper、glow

模型决定「点哪」，外壳负责「真的点下去」。这半边的代码量（operator 791 + mac-helper 560 + glow 133 + operator-server 129 ≈ 1613 行）占外壳总量近三成——**外壳最重的资产是外设层，不是 agent 层**。

### 5.1 operator-server：每次启动一个带 bearer 的回环服务

`operator-server.ts:71-75`：token 是每次启动 `randomBytes(24).toString("hex")`；五个路由（`:79-97`）：`GET /info`、`POST /screenshot`、`POST /execute`、`GET /windows`、`POST /window`；`listen(0, "127.0.0.1")`（`:113`）随机端口。请求体上限 2MB（`:28`）。5xx 与 403（macOS 无 Screen Recording）写日志，4xx 不写（`:104-106` 注释：客户端自己的错不进日志）。

调用方是 runtime 的 `nanomuse mcp`（Host 的孙进程），经 `NANOMUSE_OPERATOR_URL/TOKEN` 找到这里。模型坐标是「它看到的那张图的像素」，operator 一次性映射回屏幕像素——缩放屏与_plain_屏同一条路径（docs/desktop-muse.md:274-279）。

### 5.2 operator.ts：UI-TARS 的移植，不是自研

文件头（`operator.ts:1-7`）直白署名：

```ts
// Portions derived from UI-TARS-desktop (https://github.com/bytedance/UI-TARS-desktop),
// © 2025 Bytedance, Inc. and its affiliates, Apache-2.0: `NutJSOperator` and
// `NutJSElectronOperator` (the screenshot through desktopCapturer at the display's size, the
// action switch with moveStraightTo + sleep(100) + click, the hotkey map, the clipboard paste
// for typing), `getScreenSize` (logical × scaleFactor, 1 on macOS) and the marker shapes of
// `setOfMarks` (the point, the action's name beside it).
```

要点：

- 截图预算 `DEFAULT_MAX_PIXELS = 2_000_000`（`operator.ts:70`）：4K 砍半边，1080p 微裁——注释说原因：几张 4K 图曾把 relay 的 body 上限顶爆（413）。
- Wayland 直接拒绝（`isWaylandSession`，`operator.ts:168-171`），没有便携的移动指针方式。
- **永不回退**：helper 在而截图失败时，报 helper 自己的错，绝不落回 desktopCapturer（`operator.ts:285-354`；mac-helper.ts:26-28 注释同文）。回退会掩盖权限问题，让人对着黑图猜。
- 中文输入走剪贴板（`operator.ts:656-678`）：键码没有中文，非 ASCII 一律「存旧剪贴板 → 写新 → Ctrl/Cmd+V → 还原」。Windows 上所有文本都走这条路（UI-TARS 的原样做法）。

### 5.3 mac-helper：一个独立 app 换一行 TCC 权限

这是外壳里最值得读的注释之一（`mac-helper.ts:10-19`）：

> macOS attributes a TCC request to the *responsible process* — the process LaunchServices started, with everything it spawned. A child process of the Electron app is the app … an app bundle launched through `open` (LaunchServices → launchd) is responsible for itself and has its own row … the private `responsibility_spawnattrs_setdisclaim` spawn attribute LLDB and Chromium use is the other way, and Node cannot set it — so: `open`, never `spawn` of the binary. Codex's "Codex Computer Use.app" is the same arrangement.

即：Electron 的子进程在 TCC 眼里就是 Electron 本体；要让「屏幕录制/辅助功能」面板里出现一行独立的 **nanoMuse Computer Use**，必须经 LaunchServices 启动一个独立 bundle。启动方式（`mac-helper.ts:188-195`）：

```ts
/** `open -n -g -a <app> --args …`: LaunchServices starts it, so TCC attributes it to the helper, not to us. */
function openApp(appPath: string, args: string[]): Promise<void> {
  return new Promise((resolve, reject) => {
    const child = spawn("open", ["-n", "-g", "-a", appPath, "--args", ...args], { stdio: "ignore" });
```

配套的一串平台坑（全部写在 `mac-helper.ts:6-46` 与 docs/desktop.md:571-733）：

- 截图走 ScreenCaptureKit（macOS 14+），此前用 CGDisplayCreateImage——**macOS 26/27 上后者即使有权限也返回 nil**（0.1.39 的「no screenshot: noImage」）。
- 下载留下的 quarantine 标记必须先清（`clearQuarantine`，`mac-helper.ts:162-173`），否则 LaunchServices 从 App Translocation 的随机路径启动 helper，路径每次变，Screen Recording 面板里永远不出现它（0.1.38 的实际事故）。
- 协议：bearer token 由外壳写进 0600 文件再启动，helper 把端口写回文件（`mac-helper.ts:326-374`）；重启去重（`:504-517`）。
- ad-hoc 签名（identity: null）每次构建都换签名 → TCC 授权重置，这是发布说明里「Open Anyway 一次」的根源（docs/desktop.md）。

### 5.4 glow：X11 输入区域丢失的三层防御

`glow.ts:1-39` 的注释是一份小型事故报告：Electron 的 `setIgnoreMouseEvents(true)` 在 X11 上靠 XShape 输入区域（1 像素）实现点击穿透，而 **Chromium 在每次窗口 bounds 变化时把输入区域重置为整窗**（`DesktopWindowTreeHostLinux::OnBoundsChanged → … → ShapeMask(Input, None)`）。区域一丢，全屏 glow 吃掉所有点击——包括 Hands 自己的合成点击（指针移到目标上，按下的是盖在目标上的 glow）。作者在 Xephyr + GNOME Shell 3.36、3840×2160@2x 下用 ShapeGetRectangles 实测复现。

三层对策（`glow.ts:23-39`）：

1. 指针动作期间 glow 直接 unmap（`pointerAction`，`glow.ts:44-48`；`GLOW_HIDE_MS = 40`、`GLOW_SETTLE_MS = 150`，`:58-61`）；
2. 每次可能丢区域的事件后按 `[0, 150, 600]` ms 三次重设（`REARM_DELAYS_MS`，`:55`）；
3. 看门狗：点击穿透的窗口**不该收到任何指针事件**，页面一旦收到就是区域丢了，立即重设。但 Chromium 每次 show/rebound 会伪造 mouse-enter/move（aura 的合成事件，坐标是它自己记的旧位置），所以 show 后 `LEAK_GRACE_MS` 内的 enter/move 不算证据，press/wheel 永远算（`glow.ts:33-39`，LeakLog `:89-121`）。

这段代码的启示：**跨平台外壳的成本不在 API 调用，而在每个平台「文档没写的行为」**。Windows 的 0xC0000142（第 02 章）、macOS 的 responsible process、Linux 的 XShape，是同一类问题的三个实例。

---

## 6. Windows 更新：不信任 electron-updater 的退出

`windows-updates.ts`（310 行）只在 `win32 + x64 + isPackaged` 启用（`main.ts:2004-2027`）。核心立场：**保留库的下载与校验，只接管它的 quit**。

```ts
/** Keep the library's updater and transport; only its queued quit is conditional. */
export class RestartNsisUpdater extends NsisUpdater {
  constructor(canQuit: () => boolean, adapter?: AppAdapter) {
    super(null, adapter);
    const quit = this.app.quit.bind(this.app);
    this.app.quit = () => { if (canQuit()) quit(); };
  }
}
```

（`windows-updates.ts:6-13`。）原因写在 `:277-279`：库在异步 spawn 错误到达之前就把 quitAndInstall 的 quit 排进队列；这个 adapter 让「本次尝试已失败」后的排队 quit 被拒绝。`canQuit` 只在 `phase === "installing"` 时为真。

其余防线：

- 双源：`nanomuse.cn/dl/win/` 镜像优先，GitHub releases 兜底（`SOURCES`，`:37-42`）；检查每 6 小时（`:93`）。
- **计量网络闸门**：自动下载前读 Windows 的 ConnectionCost，非 unmetered 一律暂停（`automaticDownload`，`:164-171`）。读数本身 fail-closed：`windows-network-cost.ts:7-17` 用 WinRT 的 `NetworkInformation.GetInternetConnectionProfile()`（不是 Wi-Fi 名字），漫游/超量/接近上限/后台受限都算 metered；API 不可用返回 unknown，unknown 也暂停（`:20-29`）。
- **钉死候选**：`verifiedDownload`（`:245-266`）下载前重新 check，`sameCandidate` 比对版本+sha512+size（`:290-296`），镜像失败换 GitHub 时**仍用同一个版本与摘要**，绝不顺手升到下一个 release；installer 元数据必须恰好一个 exe、名字匹配、带 sha512（`:283-288`）。
- 安装走保护性退出的 handoff（`main.ts:2019-2024`）：先关闸、问人、停工作，才把已验证的包交给安装器。`before-quit` 路径**永不**触发安装（`main.ts:2048-2049` 注释）。

---

## 7. 打包与构建期补丁：README 与源码的正面冲突

### 7.1 electron-builder.yml 的几处「有故事」的配置

- `extraResources`（`:24-38`）：dsh 的 node_modules 以**真实文件**进 resources（Loader 要读原生 addon），runtime 目录可为空。
- `asarUnpack: "**/node_modules/@computer-use/**"`（`:42-43`）：libnut 的 .node 必须从真实路径 require。
- `npmRebuild: false`（`:45`）：预编译二进制，不重编。
- NSIS `preCompressedFileExtensions: []`（`:100`）：注释（`:92-99`）记录了 1.0.0 的事故——默认列表含 .mp4，electron-builder 把这类文件移出 7z 单独写入时**跳过所有 node_modules**，于是 Windows 安装包里龙的四个状态_clip_全丢，聊天上方的脸不会动；Linux/mac 不走 NSIS 所以没事。现在全部进压缩包，`tests/installer.test.mjs` 盯着这一行。
- mac `identity: null` + helper 以 `extraFiles` 进 `Contents/Helpers/`（`:111-120`）：嵌套代码由 package-mac.sh 先签内层再签外层。
- Linux deb 依赖里显式列 `xdotool`（`:70`）：Wayland 之外最后的输入兜底。

### 7.2 prepare-dsh.mjs：安装、打补丁、换 sharp

`harness/desktop/scripts/prepare-dsh.mjs:79-85`：

```js
run(
  npm,
  ["install", "--omit=dev", "--no-audit", "--no-fund", "--no-package-lock", "--ignore-scripts", `--registry=${REGISTRY}`, `@deepseek-ai/dsh@${dshVersion}`, bundle],
  { cwd: out, shell, env: { ...process.env, npm_config_registry: REGISTRY } },
);
patchCapacityDefaults(out);
patchClearedSessionAccess(out);
```

**冲突（本章头号发现）**：`harness/README.md:231` 说 dsh「installed by npm at build time and **carried unmodified**」。源码说：装完立刻打两个 lib 级补丁。

1. `scripts/capacity-defaults.mjs:6-7`：把 `dsh-llm-pi-ai/lib/index.js` 里的
   `const raw = config.providers.get();`
   替换为
   `const configured = config.providers.get(); const raw = ctx.get("nanomuseCloud")?.providerDefaults?.(configured) ?? configured;`
   ——让 Cloud 模型的 contextWindow/maxTokens 在用户没填时继承 relay 的实时值（插件侧的 `inheritCloudCapacity`，第 02 章）。补丁带版本断言：`manifest.version !== "0.2.0-rc.2"` 直接 throw（`:20-22`），且原句必须恰好出现一次，否则 throw「seam changed; review」。
2. `scripts/cleared-session-access.mjs:8-135`：向 `dsh-api-session-controller` 与 `dsh-client-file-upload` 注入约 50 处守卫，阻止「已清除的同步会话」被会话 API 重新打开（dsh 的会话文件 append-only、没有删除； nanoMuse 的 C10 语义要求跨账号不可见）。

两个补丁都是**字符串级外科手术**，pin 版本 + 断言原句，是「上游还没给 seam，先贴着写」的过渡形态（推断：等 dsh 提供正式钩子后应移除；capacity 补丁的注释自称「Compatibility seam for the pinned Harness」）。但 README 的「unmodified」措辞与之矛盾，应改为「除两处声明式兼容补丁外原样携带」。

其余构建动作：`--ignore-scripts` 后手工补 node-pty spawn-helper 的可执行位（`:88-95`）；Linux 上把 sharp 换成 `@img/sharp-wasm32` 并删掉原生 prebuild（`:107-128`）——Electron 的 Linux 构建链接系统 GLib 并泄漏符号，sharp 自带的 libvips 也有一个 GLib，同进程相遇轻则每图一行 CRITICAL、重则首图解码 SIGSEGV 杀死 Host（electron/electron#46323，#274）。代价注释里量化了：wasm 每图约 50ms vs 原生 22ms（2560×1440）。

### 7.3 smoke.mjs：一条真实的端到端自检

`scripts/smoke.mjs` 用真 Electron 跑 `--dump-config`，断言 `patched by dsh-nanomuse` 的行数 ≥3（`:74-82`）；起 Host 等地址行（120 秒，`:104-113`）；验证无 token 401、带 token 进入（`:127-129`）；再验 /status、rooms 路由、admission（live vs cleared）、skill 开关。这是仓库里唯一「外壳+Host+插件」三层联动的自动化检查（需要 Electron 与已 prepare 的 dsh，本次环境未运行，见 §9）。

---

## 8. 其余小文件速览

| 文件 | 行数 | 一句话 |
|---|---|---|
| `main.ts` | 2063 | 以上全部 + 托盘/快捷聊/覆盖窗 IPC（1011-1553）/splash 历史清理 |
| `mac-permissions.ts` | 173 | 权限轮询与 guide 流程（先 Accessibility 后 Screen Recording，逐项说为什么） |
| `private-json.ts` | 94 | 0600 的 JSON 读写（prefs、token 文件），原子替换 |
| `proxy.ts` | 87 | 代理 URL 校验、掩码、NO_PROXY 组装；relay 例外只认 env（§3） |
| `log-rotate.ts` | 39 | desktop.log 到 10MB 让位为 .1（与 runtime 的 logger.py 同一刻度），失败吞掉下次再试 |
| `load-close.ts` | 13 | 窗口已销毁时的 ERR_ABORTED 不算启动失败；活窗口的 ERR_ABORTED 不能装成功 |
| `screenshot-path.ts` | 10 | report-bug 截图同秒两份不重名 |
| `windows-network-cost.ts` | 30 | §6 的 WinRT 读数，fail-closed |
| `overlay-preload.ts` | 24 | 覆盖窗的极简桥（只收状态、发按钮） |
| `host-work.ts` | 136 | §4.2 的 cookie 铸造与 work API 客户端 |

---

## 9. 实测记录（真实输出）

环境：node v22.23.2，全局 tsc 7.0.2（原生编译器），无 Electron、无网络、未 npm install。仓库只读；在 `/tmp/kbtest/harness` 复制件上操作。

编译：`tsc -p tsconfig.json` 报两类错（缺 `@types/node` 的 TS2688；tsc7 移除 `moduleResolution=node10` 的 TS5108），但 23 个 `out/*.js` 全部产出（noEmitOnError 默认 false）。随后：

```
$ node --test tests/*.test.mjs        # harness/desktop，29 个测试文件
# tests 152
# pass 144
# fail 7
# skipped 1
```

7 个失败全部是**文件级依赖缺失**，不是逻辑失败：

- `windows-updates.test.mjs`、`windows-download-policy.test.mjs`：`Cannot find module 'electron-updater'`；
- `windows-install-handoff.test.mjs`、`windows-update-feed.test.mjs`：helpers/update-feed.mjs 需要 `builder-util-runtime`；
- `helper-relaunch.test.mjs`、`splash-history.test.mjs`、`windows-exit-paths.test.mjs`：ESM `import ts from "typescript"` 失败——全局 typescript 是 7.0.2 原生包，无传统 `createSourceFile` JS API（链接后实测 `ts.ScriptTarget` 为 undefined）。

1 个 skip 是 Windows-only 的 shell 引号用例（`prepare-dsh-spaces`）。其余 22 个文件全绿，包括 glow、host-start、host-port、host-work、navigation、protected-exit、proxy、profile-link、mac-helper、installer（读 electron-builder.yml 与 assets）、capacity-defaults、check-hooks、prepare-dsh 等。

**未实测**：`scripts/smoke.mjs`（需要 Electron 二进制与 prepare-dsh 产物，环境无 Electron 且禁止 npm install）；上述 7 个依赖缺失的测试文件。

---

## 证据索引

| 序号 | 结论 | 证据路径:行号 |
|---|---|---|
| 1 | 外壳 spawn 的是 dsh bin.js，ELECTRON_RUN_AS_NODE=1，无自研循环 | harness/desktop/src/main.ts:269-318 |
| 2 | 每次启动一个 MCP 确认 secret 与 operator bearer | harness/desktop/src/main.ts:281-295 |
| 3 | 就绪判据是 stdout 的地址行；120s 提示慢、600s 才问人 | harness/desktop/src/host-start.ts:17-24 |
| 4 | 端口候选 [上次,38421,0]，只记忆能长住的端口 | harness/desktop/src/host-port.ts:18,40-55 |
| 5 | profile 四束默认 + junction 链接外来 bundle | harness/desktop/src/profile.ts:9,18-61 |
| 6 | 主进程同步重试用 Atomics.wait | harness/desktop/src/profile-link.ts:44-55,108-129 |
| 7 | 三平台 frameless 差异与 sandbox:true | harness/desktop/src/main.ts:1764-1783 |
| 8 | 同源按解析 URL 比较，防 userinfo 伪装 | harness/desktop/src/navigation.ts:11-23 |
| 9 | media 仅 audio、clipboard 写仅主框架 | harness/desktop/src/navigation.ts:41-55; window-permissions.ts:4-7 |
| 10 | preload 唯一桥 nanomuseHarness；relayHosts 死表面 | harness/desktop/src/preload.ts:55,101,107; main.ts:978-997 |
| 11 | relay 例外只认启动 env，拒绝渲染输入 | harness/desktop/src/proxy.ts:67-71 |
| 12 | Windows taskkill /T /F，POSIX TERM→3s KILL | harness/desktop/src/main.ts:439-456 |
| 13 | Host 崩溃只自动重启一次 | harness/desktop/src/main.ts:606-642 |
| 14 | 保护性退出：关闸→问人→三轮 stop（15s/轮） | harness/desktop/src/protected-exit.ts:56-58,86-124 |
| 15 | 外壳以浏览器 cookie 调 /nanomuse/cloud/work | harness/desktop/src/host-work.ts:29,47-64 |
| 16 | operator 五路由、随机端口、per-launch bearer | harness/desktop/src/operator-server.ts:71-97,113 |
| 17 | operator 源自 UI-TARS（Apache-2.0），2Mpx 预算 | harness/desktop/src/operator.ts:1-7,70 |
| 18 | helper 失败永不回退 desktopCapturer | harness/desktop/src/operator.ts:285-354; mac-helper.ts:26-28 |
| 19 | TCC responsible process → open -n -g -a 独立 helper | harness/desktop/src/mac-helper.ts:10-19,188-195 |
| 20 | quarantine 不清则 App Translocation 换路径 | harness/desktop/src/mac-helper.ts:30-33,162-173 |
| 21 | X11 输入区域丢失的三层防御与伪造事件甄别 | harness/desktop/src/glow.ts:1-39,55-61 |
| 22 | 更新只接管 quit；候选钉版本+sha512；计量网暂停 | harness/desktop/src/windows-updates.ts:6-13,164-171,245-296 |
| 23 | 网络成本 WinRT 读数 fail-closed | harness/desktop/src/windows-network-cost.ts:7-29 |
| 24 | NSIS preCompressedFileExtensions:[] 的丢 mp4 事故 | harness/desktop/electron-builder.yml:92-100 |
| 25 | 构建期两个 dsh lib 补丁，与 README「unmodified」冲突 | harness/desktop/scripts/prepare-dsh.mjs:84-85; capacity-defaults.mjs:6-7,20-22; cleared-session-access.mjs:8-30; harness/README.md:231 |
| 26 | Linux sharp 换 wasm（GLib 符号冲突） | harness/desktop/scripts/prepare-dsh.mjs:98-128 |
| 27 | smoke 断言 ≥3 行被 bundle 补丁、token 401 | harness/desktop/scripts/smoke.mjs:74-82,104-129 |
| 28 | 更新仅 win32/x64/packaged；before-quit 永不装更新 | harness/desktop/src/main.ts:2004-2027,2043-2061 |

## 未确认问题

| 序号 | 问题 | 缺什么证据 |
|---|---|---|
| 1 | `relayHosts` 死表面的来历：是预留还是接线被拆？ | 需要 git 历史/PR 讨论；本基线只见类型残留（preload.ts:55,101）与 proxy.ts 的拒绝注释 |
| 2 | capacity/cleared 两个 lib 补丁是否有上游 seam 的替代计划？ | 仓库内无 issue 引用；需上游 dsh 的 roadmap 或 nanoMuse 的迁移记录 |
| 3 | smoke.mjs 在本基线是否全绿？ | 未实测：环境无 Electron、禁止 npm install；只读到脚本逻辑 |
| 4 | Windows 更新链在真实 NSIS 安装上的行为（SmartScreen、降级拒绝） | 需要 Windows 实机；windows-updates 相关 5 个测试文件因缺 electron-updater/builder-util-runtime 未跑 |
| 5 | glow 看门狗在 Wayland 下的对应物 | 源码直接拒绝 Wayland（operator.ts:168-171），无 Wayland 路径可查 |
| 6 | helper 的 ScreenCaptureKit 实现细节 | helper 本体在 mac/computer-use/（Swift），不在本次范围（nanomuse/computer/** 与 mac 原生代码被排除） |
| 7 | `--expose-internals` 具体被 dsh 的哪个内部 API 依赖 | dsh 源码不在仓库（npm 依赖），只能从启动参数推断其必要性 |