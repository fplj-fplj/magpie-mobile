# Magpie-Mobile

把 [usemagpie.ai 的 Magpie](https://github.com/yetone/magpie)（AI 模型网关，MIT）移植为 Android 原生独立 APK：提供后台模型网关、其他 App 接入、应用内聊天与双编辑入口。当前实现范围见 [完整核心与双编辑说明](docs/full-core-dual-editors.md)，长期规划见 [移植计划](plan.md)。

## 当前源码版本：0.1.48-streaming（流式接收与取消收尾）

- **上游核心**：v0.1.3781（commit `b52c7111`）；
- **本轮修复**：限制流式读取与正文／思考内存预算，增加跨降级的整轮时限，修复静默连接取消与待定轮次收尾，支持普通降级的纯思考响应；聊天请求禁止自动重发。保持现有界面与字体。新增 65 项 JVM 回归，总计 686 项；完整 GitHub Actions 验证与正式发布均已通过。详见 [0.1.48 修复与验收记录](docs/bugfixes-0.1.48.md)。
- **思考过程**：聊天测试页独立显示可折叠的 `reasoning_content`；生成中实时预览，完成后自动收起。历史中可重新展开，但思考内容不会进入下一轮模型上下文。详见 [0.1.45 思考内容说明](docs/chat-reasoning-ui-0.1.45.md)；
- **页面重排**：提供商添加、模型选择和连通测试参照 RikkaHub 页面结构重新组织；聊天区改为独立消息滚动与底部固定输入框，支持模型搜索、按提供商分组以及三项能力测试。全屏编辑、模型多选与聊天键盘布局已通过实际 Android 回归，详见 [0.1.46 界面与验收记录](docs/rikkahub-pages-0.1.46.md)；
- **流式空值修复**：推理模型在正文前返回的 `delta.content: null` 不再被拼成 `"null"`；流式与非流式正文均只接受 JSON 字符串。详见 [0.1.44 修复说明](docs/chat-null-delta-fix-0.1.44.md)；
- **新技术路线**：官方完整 CLI 核心 + Android 宿主适配，APK 使用 `nogui,androidhost`，不再使用 `mobile` 构建标签；官方浏览器编辑与原生提供商的选择卡片仅在“模型提供商”和“更多”主页显示。原生提供商新增、详情、共享模型选择与连通测试按 RikkaHub 当前页面结构独立实现。详见 [路线与双编辑说明](docs/full-core-dual-editors.md)；
- **屏幕阅读与启停**：全原生界面采用较厚笔画的霞鹜文楷屏幕阅读版，顶部品牌文字保留 Poppins；网关状态卡改为固定位置的单一开关。详见 [本轮界面说明](docs/ui-update-0.1.41.md)；
- **全屏编辑安全区**：提供商编辑与插件编辑统一采用边到边对话框，避开状态栏、手势栏和显示挖孔；高级页固定保存按钮不再被系统手势栏裁切。详见 [0.1.42 修复说明](docs/ui-fix-0.1.42.md)；
- **既有界面（P9）**：暖纸色与文楷排版、精简网关首页、二级管理导航、聊天消息分层；修复网络状态与电池设置入口。详见 [界面更新与验收说明](docs/ui-update-0.1.38.md)；
- **官方外部插件**：原生页接入 npm／GitHub／SAF 安装、启停、更新、删除、参数、登录与诊断；提供商／工具、同步中间件和异步 Hooks 保留官方执行机制。详见 [插件接入与运行验收](docs/official-plugin-integration.md)；
- **真机稳定性（P4/P5）**：网络热切换监听、电池优化白名单引导、低内存告警、冷启动遥测（<800ms 门槛）、崩溃事后脱敏审计；
- **功能对齐（P5/P6）**：历史会话重命名/删除、用量洞察四段卡片（聚合/热力图/额度/历史）、开源许可声明查看、密钥可见模型目录；
- **既有能力（P2/P3）**：多提供商安全接入、模型目录发现、Chat Profiles、多会话持久化。路由、号池与 MCP 的高级核心配置由官方网页编辑；旧原生页面的示例尚未全部对接。

## 文档索引

| 文档 | 内容 |
| :--- | :--- |
| [docs/bugfixes-0.1.48.md](docs/bugfixes-0.1.48.md) | 流式接收预算、整轮时限、静默取消与纯思考降级修复及 Actions 验收 |
| [docs/bugfixes-0.1.47.md](docs/bugfixes-0.1.47.md) | 会话删除、空响应、root 地址、用量与插件状态修复及完整 Actions 验收 |
| [docs/rikkahub-pages-0.1.46.md](docs/rikkahub-pages-0.1.46.md) | RikkaHub 参考页面、全屏编辑、模型多选、聊天键盘与同提交构建验收 |
| [docs/chat-reasoning-ui-0.1.45.md](docs/chat-reasoning-ui-0.1.45.md) | 思考过程折叠卡、独立持久化、上下文边界与聊天页布局 |
| [docs/chat-null-delta-fix-0.1.44.md](docs/chat-null-delta-fix-0.1.44.md) | DeepSeek 推理流空正文导致界面出现大量 `null` 的根因、修复与回归 |
| [docs/release-policy.md](docs/release-policy.md) | 普通构建、手动发布、Latest 标记与 Release 清理规则 |
| [docs/full-core-dual-editors.md](docs/full-core-dual-editors.md) | 完整 CLI 路线、双编辑入口、RikkaHub 提供商结构参考与验收 |
| [docs/official-plugin-integration.md](docs/official-plugin-integration.md) | 官方三类外部插件、原生管理、运行边界与候选发布 |
| [docs/official-plugin-adaptation-plan.md](docs/official-plugin-adaptation-plan.md) | 实施要求、阶段与完整验收矩阵 |
| [docs/ui-update-0.1.41.md](docs/ui-update-0.1.41.md) | 单一启停开关、文楷屏幕版、独立发布流程及实际 Actions 结果 |
| [docs/ui-fix-0.1.42.md](docs/ui-fix-0.1.42.md) | 提供商高级页保存按钮与全屏编辑安全区修复 |
| [docs/ui-update-0.1.38.md](docs/ui-update-0.1.38.md) | P9 界面更新、字体来源与真机验收步骤 |
| [docs/upgrade-guide-0.1.30.md](docs/upgrade-guide-0.1.30.md) | **安装与验证指南**（安装步骤/healthz 判定/新功能清单/故障排查） |
| [docs/p5-gap-analysis.md](docs/p5-gap-analysis.md) | 原版功能对齐缺口分析与终审裁定 |
| [docs/structure-review.md](docs/structure-review.md) | 代码结构评估矩阵 |
| [docs/delivery-p2/p3/p4.md](docs/) | P2/P3/P4 阶段交付报告 |
| [docs/session-storage.md](docs/session-storage.md) | 会话存储契约 |

## 隐私说明

- 所有凭据（提供商 Key、网关 Caller Key）只存于 App 私有目录（受 Android 文件加密保护）；**系统备份关闭**，仅支持 SAF 加密导出（schemaVersion 1，上限 5MB）；
- 网关仅监听 `127.0.0.1`；管理面 3430 与网关面 3425 凭据严格隔离，Key 不进入状态流/Intent/日志/剪贴板；
- 崩溃事后审计（`post_mortem.json`）强制经 SecretRedactor 脱敏并截断，绝无凭据落盘；
- 不申请额外权限：仅 INTERNET / 前台服务 / 通知，无位置/存储/联系人访问。

## 后台限制说明

- 网关以前台服务（`specialUse`）运行，常驻通知可随时停止；用户 Stop/ForceStop 后**不自动保活**；
- Android 14+ 的电池优化白名单需用户手动授权（App 内一键引导），否则 Doze 可能中断长耗时推理；
- 网络切换（Wi-Fi ↔ 蜂窝）由 `NetworkConnectivityMonitor` 实时感知并在 UI 呈现；低内存触发 `TRIM_MEMORY_RUNNING_CRITICAL` 告警。

## 安全边界

完整核心继承 Android 宿主的鉴权与进程管理适配。管理 3430 Cookie 与网关 3425 Bearer 隔离；同 UID 插件不是沙箱。官方配置 API 已开放，退出、重启与原生升级由 Android 宿主负责。原生基础 OpenAI 兼容编辑使用 canonical/HMAC revision/严格锁/原子保存；浏览器和原生写入共用锁与版本检查。

## 验证与操作

`Build signed APK (manual release)` 在 `main` 更新时负责固定源码／补丁、ARM64 核心、APK 编译、签名与载荷校验，并把签名产物保存在对应 Actions run，不自动创建 Release。确认版本可用后，手动运行该工作流并勾选 `publish_release`，才会把同一轮验证产物发布为 Latest Release。核心按上游、Go／NDK 版本和补丁哈希缓存，UI 修改可复用核心。`Validation` 独立运行 JVM、Go、真实插件与 Android 模拟器测试，失败保留报告，不阻止已经成功构建的签名产物归档。文档修改不触发重复编译。本地不运行编译。

当前可用版本：[`0.1.48-streaming` 签名 APK](https://github.com/fplj-fplj/magpie-mobile/releases/tag/v0.1.48-streaming-a0b7db91)，SHA-256 `d48520b37e77ec069b19b165eac70b48c112202ed96bbaf6108141cc34638a88`。[正式构建与发布](https://github.com/fplj-fplj/magpie-mobile/actions/runs/44589075581)和[同提交完整验证](https://github.com/fplj-fplj/magpie-mobile/actions/runs/44587116765)均通过；JVM 686 项、Go、真实官方／社区插件、race 与 Android API 35 模拟器运行结果均为 0 失败。本轮中间候选仅归档到 Actions，完整验证通过后才发布 Release；完整证据与后续巡查范围见 [0.1.48 验收记录](docs/bugfixes-0.1.48.md)。

官方插件完整通过基线仍可通过 [`05072a47` Actions](https://github.com/fplj-fplj/magpie-mobile/actions/runs/43682069460)追溯；对应历史 Release 条目已按清理策略移除，运行证据与剩余项目记录在[插件接入文档](docs/official-plugin-integration.md)。

本地大版本升级基线：v0.1.3622 起步（旧基线 v0.1.3188 的 24 个补丁目标文件零变更，升级流程见技能库与 gap-analysis §5）。
