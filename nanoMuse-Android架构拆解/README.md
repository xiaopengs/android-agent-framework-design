# nanoMuse Android 端架构拆解知识库

从架构师视角对开源个人智能体 **nanoMuse**（浙江大学 APRIL 实验室，GPL-3.0-or-later）Android 端实现的源码级拆解：整体架构 → 数据流转 → 各模块实现逻辑，覆盖 Agent 主循环（Looper）、沙箱运行时（Runtime）、工具的定义/召回/执行、Browser 工具调用与界面、上下文召回与管理、模型输出与工具执行、轮次管理。

- **分析对象**：`github.com/nano-muse/nanoMuse` main 分支，快照日期 **2026-11-17**（1.0.x 之后的 Unreleased 状态）
- **方法**：全部结论以源码为准（文中标注文件路径与函数名），官方文档与论文（arXiv:2610.08699）交叉印证
- **关键前提**：当前 Android 应用是**原生 Kotlin 实现**（基于 OpenMinis 1.13），早期 Python 路线已归档；仓库内 `nanomuse/` Python 包服务于桌面/Web/服务端，仅作对照

## 目录

| 章 | 内容 | 你会得到 |
|---|---|---|
| [01 整体架构与数据流转](01-整体架构与数据流转.md) | 代码分层、模块地图、一条消息的全链路 | 架构全景图与四条核心不变量 |
| [02 Looper：Agent 主循环](02-Looper-Agent主循环.md) | 200 轮预算、流事件状态机、三层容错、四级熔断、五种终止、排队注入 | 轮次管理的完整状态机与事故驱动的性能设计 |
| [03 Runtime：沙箱与桥接](03-Runtime-沙箱与桥接.md) | PRoot+Alpine、会话 Shell 标记协议、NativeOffload execve 桥、bash 自愈 | 手机上的完整 Linux 运行时如何搭起来 |
| [04 工具系统](04-工具系统.md) | 8 个原生工具、三层渐进披露、七道执行工序、RiskGate 六级风险守护 | 工具定义/召回/执行/权限的完整闭环 |
| [05 Browser 工具专项](05-Browser工具专项.md) | WebView 后端、动作集、双铁律判定、hand_over 人机交接、cookie 隐私 | 浏览器调用链与界面呈现的每个细节 |
| [06 上下文召回与管理](06-上下文召回与管理.md) | 缓存布局、记忆注入、三层度量、五阶段裁剪、v2 锚点压缩 | 上下文工程的可移植方案 |
| [07 模型输出与工具执行](07-模型输出与工具执行.md) | Provider 抽象、协议适配、参数修复、持久化链路 | 多厂商差异如何被收敛在边界内 |
| [08 可重现指南](08-可重现指南.md) | 构建、测试地图、调试标签、源码索引、复用建议 | 亲手验证每个结论的路径 |

配套长文：[技术博客：nanoMuse 安卓端架构深度拆解](技术博客-nanoMuse安卓端架构深度拆解.md)（图文并茂版，含 HTML 渲染件）。

## 三分钟速览

1. **Looper** = `ChatViewModel.runAgentLoop()`：200 轮上限；每轮"裁剪请求视图 → 上下文闸 → 流式模型调用 → 工具七道工序 → 结果入历史"；瞬态错误 1/2/4s 重试、限流直接 fallback；ToolLoopDetector 四级熔断；五种可恢复终止路径。
2. **Runtime** = PRootKernel（全局单例）+ 每会话 PersistentShell（标记协议读退出码，超时不杀 shell）+ NativeOffload（guest execve 经 abstract socket 桥到宿主 Kotlin，输出写回 /tmp 再改写为 cat）。
3. **工具** = 8 个原生工具 + CLI 长尾（android-*/minis-*/minis-mcp-cli）；技能与 MCP 走"披露元数据、按需读全文"的渐进召回；执行前经参数修复、熔断、preflight 三道闸。
4. **权限** = RiskGate 单卡片审批队列（180s 无应答即拒）；六级风险类；warning 永不豁免；授权绑定具体对象；付钱记住授权需屏幕锁。
5. **Browser** = 应用内 WebView（PRoot 起不了 Chromium）；click/type 前 JS 探测元素：密码/验证码字段拒绝并交接，付款/发送/删除按钮过审批卡；hand_over 把同一 WebView 交给用户，Done 后继续。
6. **上下文** = 历史不可变、请求视图可裁剪；水位线 50/75/90%；五阶段确定性裁剪（卸载可 file_read 召回）；≤3 次摘要压缩，v2 锚点模型，锚点失效降级完整历史。
7. **模型 I/O** = thinking 钳制做进接口结构；文本块 monolithic/ordered 双语义；tool id 去重与跨协议清洗；reasoning_content 不透明回传；参数三策略修复（截断的写操作直接拒绝）。

## 许可与归属

- nanoMuse 源码：GPL-3.0-or-later（含 OpenMinis 上游，GPL-3.0）。本知识库为独立撰写的分析文档，引用源码片段仅作评论/研究目的；若直接复制源码到你的项目，需遵守 GPL。
- 论文：Liu et al., *nanoMuse: An Open-Source Personal Agent for Every Device You Own*, arXiv:2610.08699。