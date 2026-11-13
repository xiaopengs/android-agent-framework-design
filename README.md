# Android 端侧 Agent 框架 · 方案设计文档

基于 nanoMuse Android 端源码拆解整理的端侧 Agent 后台框架开发基线文档，V1.1 Final-Dev 版本。

## 目录

- [docs/DESIGN.md](docs/DESIGN.md) — 完整方案设计文档（五层架构、3 条主链路顺序图、工具子时序、会话状态机、接口契约、10 个核心工具协议、验收标准）
- [docs/REQUIREMENTS-SRS.docx](docs/REQUIREMENTS-SRS.docx) — 需求规格说明书（功能列表、模块列表、功能说明、活动时序、非功能与验收）
- [docs/DESIGN.html](docs/DESIGN.html) — 方案设计文档网页版（浏览器直接打开，Mermaid 图在线渲染，侧边目录导航）
- [docs/REQUIREMENTS-SRS.html](docs/REQUIREMENTS-SRS.html) — 需求规格说明书网页版（内嵌原图，同套排版外壳）

> 网页版由 `tools/build_html.py` 从 Markdown / DOCX 生成；Mermaid 走 jsDelivr CDN，离线环境可退回 Markdown 源文件阅读。

## 代码骨架（可运行 runtime）

- [runtime/README.md](runtime/README.md) — 无界面后台 runtime 的编译、运行方式与 NDJSON 事件契约
- [runtime/COVERAGE.md](runtime/COVERAGE.md) — 与五层架构、10 个 P0 工具清单的逐项对齐矩阵、缺口与补齐路线
- [runtime/dist/nm-headless-runtime.zip](runtime/dist/nm-headless-runtime.zip) — 最小可运行打包（源码 + 依赖 jar + 预编译 jar + 冒烟脚本）
- 预编译产物：`runtime/out/nm-headless.jar`（JDK 11，`java -cp` 直接运行）

当前骨架为 Layer4 内核 + Layer5 本地执行/风控的纯 LOCAL 垂直切片，已实现 4 个基础工具；协同路由、通信总线、平台能力工具与 MCP/A2A 适配的缺口见 COVERAGE.md。

## 文档要点

- 五层架构：RootRouter 协同调度 → CloudAgentBus 通信总线 → 会话与队列 → Agent Looper 内核 → 工具中台与风控沙箱
- 三种调度模式：LOCAL 本地执行 / CLOUD 云端全权 / HYBRID 端云协同
- 10 个 P0 端侧独占工具：`shell_execute`、`file_read`、`file_write`、`file_edit`、`read_image`、`browser_use`、`memory_write`、`memory_get`、`android_calendar`、`android_notify`
- 图均为 Mermaid 源码，可在 Typora / Obsidian / Mermaid LiveEditor 中直接渲染

## 版本

| 版本 | 说明 |
| :--- | :--- |
| V1.0 | 纯端侧 Headless 架构 |
| V1.1 | 新增 RootRouter 决策与端云通信总线 |
| V1.1-Final | 10 个 P0 工具全集对齐 |
| V1.1-Final-Dev | 开发落地增强：架构图、顺序图、接口契约、状态机 |
