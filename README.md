# Android 端侧 Agent 框架 · 产品需求与方案设计文档

基于 nanoMuse Android 端源码拆解整理的端侧 Agent 后台框架开发基线文档，V1.2-PRD 版本；配套 **V1.2 Runtime** 五层可运行骨架。

## 目录

- [docs/PRD.md](docs/PRD.md) — **产品需求文档（PRD）**：按 10 章模板反向设计，含产品定位、角色权限、口径裁决、64 条功能需求、26 条非功能需求、AI 质量专项、18 个验收场景、RTM 与需求追溯映射
- [docs/PRD.html](docs/PRD.html) — PRD 网页版（浏览器直接打开，Mermaid 图在线渲染，侧边目录导航）
- [docs/DESIGN.md](docs/DESIGN.md) — 完整方案设计文档（五层架构、3 条主链路顺序图、工具子时序、会话状态机、接口契约、10 个核心工具协议、验收标准、Runtime 落地状态、需求反向追溯与口径统一）
- [docs/REQUIREMENTS-SRS.docx](docs/REQUIREMENTS-SRS.docx) — 需求规格说明书（工程规格：45 条功能需求、17 条非功能需求、8 类外部接口、活动时序与验收）
- [docs/BROWSER-TOOL-DATAFLOW.md](docs/BROWSER-TOOL-DATAFLOW.md) — `browser_use` 浏览器工具数据链路分析（组件架构、调用时序、七动作数据形态、hand_over 接管、标签页状态机、四类数据落点、失败模式、移植形态与 AC-B1~B8 验收）
- [docs/TOOL-RECALL-AND-BROWSER-CASES.md](docs/TOOL-RECALL-AND-BROWSER-CASES.md) — 工具召回机制与浏览器调用案例走查（四道判定、三条召回通路、两个真实 query 的逐层走查、能力矩阵、P0/P1/P2 改造清单、AC-R1~R6）
- [docs/TOOL-RECALL-AND-BROWSER-CASES.html](docs/TOOL-RECALL-AND-BROWSER-CASES.html) — 该文档网页版（3 张 Mermaid 图）
- [docs/nanoMuse-模块架构与数据流.html](docs/nanoMuse-模块架构与数据流.html) — 源项目 16 个模块（M01–M16）架构与数据流原始报告
- [docs/DESIGN.html](docs/DESIGN.html) — 方案设计文档网页版
- [docs/REQUIREMENTS-SRS.html](docs/REQUIREMENTS-SRS.html) — 需求规格说明书网页版
- [docs/BROWSER-TOOL-DATAFLOW.html](docs/BROWSER-TOOL-DATAFLOW.html) — 浏览器工具链路分析网页版（6 张 Mermaid 图）
- [docs/RUNTIME-V1.2.html](docs/RUNTIME-V1.2.html) — V1.2 落地可视化报告

> 网页版由 `tools/build_html.py` 从 Markdown / DOCX 生成；Mermaid 走 jsDelivr CDN，离线环境可退回 Markdown 源文件阅读。

## 上游源码考证（qoderwiki）

- [qoderwiki/README.md](qoderwiki/README.md) — **nanoMuse Android 架构知识库**入口与导航
- [qoderwiki/nanoMuse-android-架构知识库.html](qoderwiki/nanoMuse-android-架构知识库.html) — 单文件自包含网页版（marked + mermaid 已内联，**离线可开、不走 CDN**）：7 章、23 张图、61 张证据表、100 段源码摘录
- `qoderwiki/parts/*.md` — 7 章 Markdown 源：总纲 / 架构与数据流 / Looper 与轮次 / 模型输出与 Provider / Runtime 与工具体系 / 上下文召回与管理 / Browser 调用与界面
- `qoderwiki/verify/` — JVM 验证台，`./run_all.sh` 一键重跑，**19 个测试类 / 145 个测试全绿**，被测类原样零改动，无需 Android SDK

与 `docs/` 的分工：`docs/` 是**面向新框架的前向设计**，`qoderwiki/` 是**面向上游既有实现的后向考证**，为前向设计提供带行号的事实底座（基线 commit `067384b7`）。考证结论与产品口径冲突时，事实以 `qoderwiki/` 的行号证据为准，产品裁决仍以 `PRD.md` 1.6 为准。

## 文档体系与定位

四份文档各司其职，**不互相替代**；口径冲突以 `PRD.md` 1.6「口径裁决表」为单一事实源（共 9 项裁决）：

| 文档 | 视角 | 回答的问题 |
| :--- | :--- | :--- |
| `docs/PRD.md` | 产品需求 | 为什么做、给谁做、做到什么程度、怎么验收、先做哪个 |
| `docs/DESIGN.md` | 方案设计 | 怎么实现（架构 / 时序 / 契约 / 状态机） |
| `docs/REQUIREMENTS-SRS.docx` | 工程规格 | 有哪些功能条目、接口与数据（含上游源码拆解事实） |
| `runtime/COVERAGE.md` | 落地对齐 | 设计里哪条已写成代码、哪条还是缺口 |

> 阅读顺序：PRD 第 2/3 章 → DESIGN 第 2/3 章 → PRD 第 8/9 章与附录 G → COVERAGE.md。
> `PRD.md` 采用**反向设计**：以已定稿的方案为输入，反推产品需求与验收，并用附录 E（RTM）、附录 F（反向设计映射表）把「需求 ↔ 设计 ↔ 代码 ↔ 验收」四端锁死。

## 代码骨架（可运行 runtime，V1.2）

- [runtime/README.md](runtime/README.md) — 五层装配、编译/运行方式、CLI 参数、NDJSON 事件契约、换宿主指南
- [runtime/COVERAGE.md](runtime/COVERAGE.md) — 与五层架构、10 个 P0 工具清单的逐项对齐矩阵、验收对照与剩余缺口
- [runtime/dist/nm-headless-runtime.zip](runtime/dist/nm-headless-runtime.zip) — 可运行打包（源码 + 依赖 jar + 预编译 jar + 冒烟脚本）
- 预编译产物：`runtime/out/nm-headless.jar`（自包含 kotlin-stdlib，JDK 11+，`java -cp` 直接运行）

**V1.2 状态**：五层架构全部落地，10 个 P0 工具全集实现，LOCAL / CLOUD / HYBRID 三条主链路端到端可跑；内置自检 31/31、端到端冒烟 18/18 全绿。

```bash
cd runtime
./tools/build.sh                                  # 编译（自动找 kotlinc 或 Gradle embeddable）
java -cp out/nm-headless.jar:libs/json.jar \
  nanomuse.headless.MainKt --self-test --root ./nm-run     # 31 项自检
python3 tools/smoke.py                            # 18 项端到端冒烟
```

剩余缺口（真实云端传输、SSE 流式、Android 平台 Handler、proot 隔离、授权态持久化）见 `runtime/COVERAGE.md` 第七节；对应排期与风险量化见 `PRD.md` 第 10 章。

## 文档要点

- 五层架构：RootRouter 协同调度 → CloudAgentBus 通信总线 → 会话与队列 → Agent Looper 内核 → 工具中台与风控沙箱
- 三种调度模式：LOCAL 本地执行 / CLOUD 云端全权 / HYBRID 端云协同（命中独占工具禁止 CLOUD）
- 10 个 P0 端侧独占工具：`shell_execute`、`file_read`、`file_write`、`file_edit`、`read_image`、`browser_use`、`memory_write`、`memory_get`、`android_calendar`、`android_notify`
- 统一口径：Android 11（API 30）及以上；工具数量 10 个；架构层数五层；上游 13 个 A2A 动作 / 20 个 App 命令为参考事实，不纳入本产品验收
- 图均为 Mermaid 源码，可在 Typora / Obsidian / Mermaid LiveEditor 中直接渲染

## 版本

| 版本 | 说明 |
| :--- | :--- |
| V1.0 | 纯端侧 Headless 架构 |
| V1.1 | 新增 RootRouter 决策与端云通信总线 |
| V1.1-Final | 10 个 P0 工具全集对齐 |
| V1.1-Final-Dev | 开发落地增强：架构图、顺序图、接口契约、状态机 |
| V1.2-Runtime | 五层架构全部落地 + 10 工具 + 三链路闭环 + MCP/A2A 适配 + 31 项自检 / 18 项冒烟 |
| V1.2-PRD | 新增产品需求文档（10 章模板反向设计）；统一 9 处口径冲突；补需求追溯矩阵与设计缺口说明 |

> 合规声明：本方案为产品设计参考，具体实施请结合公司合规与法务要求确认。
