# Android 端侧 Agent 框架 · 方案设计文档

基于 nanoMuse Android 端源码拆解整理的端侧 Agent 后台框架开发基线文档，V1.1 Final-Dev 版本。

## 目录

- [docs/DESIGN.md](docs/DESIGN.md) — 完整方案设计文档（五层架构、3 条主链路顺序图、工具子时序、会话状态机、接口契约、10 个核心工具协议、验收标准）
- [docs/REQUIREMENTS-SRS.docx](docs/REQUIREMENTS-SRS.docx) — 需求规格说明书（功能列表、模块列表、功能说明、活动时序、非功能与验收）

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
