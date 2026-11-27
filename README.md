# Magpie-Mobile

把 [usemagpie.ai 的 Magpie](https://github.com/yetone/magpie)（AI 模型网关，MIT）移植为 Android 原生独立 APK：提供后台模型网关、其他 App 接入、应用内聊天与双编辑入口。当前实现范围见 [完整核心与双编辑说明](docs/full-core-dual-editors.md)，长期规划见 [移植计划](plan.md)。

## 最新上游适配：0.1.71-upstream-v0.1.4936

- 候选锁定上游 Magpie [`v0.1.4936`](https://github.com/yetone/magpie/releases/tag/v0.1.4936)（commit `cd6d110e`），应用 versionCode 72。上游会话面板新增长会话轮数、按需加载及对话文本展示。宿主改动与候选版本仍由 GitHub Actions 完整验证后发布。

## 当前正式版本：0.1.70-upstream-v0.1.4934

- 上游核心锁定 [`v0.1.4934`](https://github.com/yetone/magpie/releases/tag/v0.1.4934)（commit `f834c203`），Android 宿主核心 `0.1.4934-androidhost.4`，应用 `versionCode = 71`。
- 最终提交 `1e87bcc34a8c540e1c6926fc05b2fcd5cc075423` 的 GitHub Actions [Validation `45940919896`](https://github.com/fplj-fplj/magpie-mobile/actions/runs/45940919896) 与正式签名发布构建 [`45943337172`](https://github.com/fplj-fplj/magpie-mobile/actions/runs/45943337172) 均成功。Regression 和 Android API 35 模拟器通过；签名证书、ARM64 核心载荷与 APK 运行时探测通过。Release 已成为 Latest，APK 为 114,961,588 bytes，SHA-256 `da490efbacde7360cfeb8aced8fefa85bb89935af5f3c88691608f0b4db6db22`，与公开 checksum 和 GitHub digest 一致。详见 [0.1.70 验收记录](docs/bugfixes-0.1.70.md)。

## 前一正式版本：0.1.68-upstream-v0.1.4807

- 上游核心锁定于 [`v0.1.4807`](https://github.com/yetone/magpie/releases/tag/v0.1.4807)（commit `a8f96beb`），Android 宿主核心 `0.1.4807-androidhost.4`，应用版本 `0.1.68-upstream-v0.1.4807`（`versionCode = 69`）。
- 三方合并 13 个上游整文件补丁与 28 个 Android overlay，在保留宿主管控、浏览器编辑会话 proof 与加密备份桥接的基础上，同步上游至 2026-11-25 发布的稳定版本。界面与官方三类插件的 Android 宿主机制保持不变。详见 [0.1.68 适配记录](docs/bugfixes-0.1.68.md)。
- 最终提交 `72ee90650c16b72388d37123e6b81bc1168ee9b0` 的 GitHub Actions [Validation `45661210610`](https://github.com/fplj-fplj/magpie-mobile/actions/runs/45661210610) 与正式签名发布构建 [`45663541021`](https://github.com/fplj-fplj/magpie-mobile/actions/runs/45663541021) 均成功。JVM 1,155 项、Android API 35 模拟器 32 项、Go mobile 53 项、AndroidHost 61 项及官方／社区插件与 race 验收全部通过。Release 已成为 Latest；签名 APK 为 114,210,220 bytes，SHA-256 `b0b1cf7f68ce50e4dd9b8cbb00864b9e4bd374b1faaa9dad83da3508df1f082f`，并与公开四项附件、固定证书与载荷逐项核对。详见 [完整验收记录](docs/bugfixes-0.1.68.md)。

## 上一正式版本：0.1.66-update-center

0.1.66 是 0.1.68 之前的正式版本，目前已按 Release 保留规则清理其 Release 页面，Git tag `v0.1.66-update-center-7890bf89` 仍保留。该版上游固定于 v0.1.4759；应用内更新器、上游适配、JVM、Android 模拟器、Go／插件／race 验收均通过。此前公开的签名 APK 为 114,082,824 bytes，SHA-256 `01292c5b8814bc48c9ea5738b4f14e037a6488d7fea24ceb394dab8ead307e42`。详见 [0.1.66 验收记录](docs/bugfixes-0.1.66.md)。

- Android 宿主管理 API 改用原生请求头和浏览器 session proof，避免管理密钥通过跨端口共享的 Cookie 暴露。
- 修复浅色对比度、短屏历史弹窗、提供商页签及模型分组读屏状态；插件登录敏感字段的复制会标记为敏感。
- Release 附件上传后重新核验同提交 Validation，旧版本恢复默认不替换 Latest，增加受保护的 Release 清理脚本。
- 上游核心锁定于 v0.1.4759（commit `95e6a418`），由三方适配同步并经 GitHub Actions 验证；每日自动检查已启用，候选 PR 仍须验证后合并。“更多”页可检查本项目 Release，核对 APK 后交给 Android 安装器。

## 前一已验证版本：0.1.64-reasoning-aliases

- **流式思考别名**：部分 OpenAI 兼容中转把思考增量放在 `delta.reasoning` 而不是 `delta.reasoning_content`。聊天与连通测试现在兼容两种字段；同一事件中优先使用非空的 `reasoning_content`，避免重复显示。别名沿用原有思考长度限制，思考与正文分开保存，不会进入下一轮上下文。`a589171` 的 JVM 1,137 项、Android 23 项及完整 Go 与插件验收通过，正式签名包已与公开 Release 逐字节核对，详见 [0.1.64 验收记录](docs/bugfixes-0.1.64.md)。

## 已验证历史版本：0.1.63-browser-backup-bridge

- **官方加密备份文件桥接**：Android 宿主的官方网页导出直接返回官方 Seal 生成的密文字节，由真实 Chrome 保存到公共 Downloads；系统文件选择器选回同一文件后，错误口令保留配置 B，正确口令恢复 A。最终 `f187e8f` 的 JVM 1,127 项、Android 23 项及完整 Go／插件验收通过，真实浏览器往返、正式签名、载荷、tag、Latest 与四项附件均已核对。详见 [0.1.63 验收记录](docs/bugfixes-0.1.63.md)。
- **历史版本**：0.1.62-chat-session-clipboard-fixes 仍保留为正式 Release。删除或重命名会话时保留聊天旧页和草稿，跨会话恢复只使用目标会话内容；密钥复制核对连接轮次并带系统敏感标记，提供商页面 ART 崩溃及操作收尾已修复。详见 [0.1.62 验收记录](docs/bugfixes-0.1.62.md)。
- **历史版本**：0.1.61-session-editor-fixes。同提交 JVM 1,114 项、Android 19 项及完整 Go 与插件验收通过，详见 [0.1.61 记录](docs/bugfixes-0.1.61.md)。
- **提供商模型摘要**：已保存 `models[].on` 决定“启用 E / 目录 D”，明确区分未保存草稿。0.1.60 的 JVM 1,080 项、Android 10 项及完整 Go／插件验收通过，详见 [0.1.60 验收记录](docs/bugfixes-0.1.60.md)。
- **自动选择模型的连通测试**：原生候选按已保存列表的实际 `models[].on` 识别，空显式 `chosen` 不再误报无模型。停用、未就绪和明确禁用继续不可测，未保存草稿与发现目录不进入候选，保留真实网关路由核验。详见 [0.1.59 修复与验收记录](docs/bugfixes-0.1.59.md)。
- **提供商保存与外部返回**：保存期间锁定手动模型输入并拒绝旧回调，外部返回保留草稿、合并待办，关闭编辑或当前请求退出后自动补读最新列表；未确认前阻止旧配置保存。0.1.58 的 JVM 1,071 项及完整 Actions 验收通过，详见 [0.1.58 修复记录](docs/bugfixes-0.1.58.md)。
- **聊天预设与停止清理**：预设参数进入真实发送，失败保留表单，存储失败保留原文件；网关停止使用独立清理凭证，重复通知与迟到启动不提前解除屏障。详见 [0.1.57 预设验收](docs/bugfixes-0.1.57.md)及 [0.1.56 停止验收](docs/bugfixes-0.1.56.md)。
- **实时诊断与退出码**：运行日志有界合并更新，不重复启动连接或通知；停止保留最终日志和实际退出码，完整行先脱敏再截断。详见 [0.1.54 验收记录](docs/bugfixes-0.1.54.md)。
- **已发布 APK 的上游核心**：0.1.68 Release 使用 `v0.1.4807`（commit `a8f96beb`）；之前的 0.1.66 Release 使用 `v0.1.4759`（commit `95e6a418`）。
- **聊天与模型目录**：聊天与模型目录在排队前绑定完整连接轮次，同一服务重连也撤销旧请求。模型选择及发送使用已验证目录的连接标识，点击时再核对有效性，覆盖重绘之前的旧按钮窗口；失败保留草稿，成功保存以 Room 的实际提交为准，不自动重发。新增 47 项 JVM 回归，总计 922 项，完整 Actions、真实 Android 核心重连、签名候选与正式发布均通过。详见 [0.1.53 修复与验收记录](docs/bugfixes-0.1.53.md)。
- **插件登录与取消**：插件读取、编辑、市场及登录绑定完整连接轮次；Start 返回前的取消意图会保留，取得 ID 后只在原连接取消一次，并只读核对真实终态。未知结果保留恢复提示，重建后不接管旧任务，文件导入也校验复制前的连接。0.1.52 新增 47 项 JVM 回归，总计 875 项，完整 Actions、真实 Android 登录取消、签名候选与正式发布均通过。详见 [0.1.52 验收记录](docs/bugfixes-0.1.52.md)。
- **提供商重连**：提供商页面按完整连接轮次刷新，同一服务重连也不复用旧缓存或旧请求；新轮次仍等待旧写入真正退出。未知保存核对成功后清除旧编辑／新建状态，提示从列表重新打开，普通刷新保留草稿。0.1.51 的 828 项 JVM 回归、完整 Actions、签名候选与正式发布均通过。详见 [0.1.51 验收记录](docs/bugfixes-0.1.51.md)。
- **提供商保存与网关恢复**：提供商操作绑定发起会话并主动取消真实连接，未知保存使用写入屏障与新 GET 核对，BACKOFF 恢复保持最新请求和串行密钥核对。0.1.50 的 804 项 JVM 回归、完整 Actions、签名候选和正式发布均通过。详见 [0.1.50 验收记录](docs/bugfixes-0.1.50.md)。
- **联通测试取消**：目录、普通、流式和工具检查在关闭或会话变化时主动取消，各项与整轮分别计时；目录限制为 4 MiB／六十秒，底层 Socket 绕过系统 HTTP／SOCKS 代理。0.1.49 的 747 项 JVM 回归及完整 Actions 均通过。详见 [0.1.49 验收记录](docs/bugfixes-0.1.49.md)。
- **聊天接收与收尾**：流式正文／思考内存预算、跨降级整轮时限、静默连接取消与待定轮次收尾、纯思考普通降级及禁止自动重发已于 0.1.48 完成验证。详见 [0.1.48 修复与验收记录](docs/bugfixes-0.1.48.md)。
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
| [docs/bugfixes-0.1.71.md](docs/bugfixes-0.1.71.md) | 上游稳定版同步至 v0.1.4936 与会话面板更新 |
| [docs/bugfixes-0.1.70.md](docs/bugfixes-0.1.70.md) | 上游稳定版同步至 v0.1.4934 与 Android 宿主适配 |
| [docs/bugfixes-0.1.68.md](docs/bugfixes-0.1.68.md) | 上游稳定版同步至 v0.1.4807 与精确提交验收 |
| [docs/bugfixes-0.1.69.md](docs/bugfixes-0.1.69.md) | 上游稳定版同步至 v0.1.4877 与精确提交验收 |
| [docs/bugfixes-0.1.67.md](docs/bugfixes-0.1.67.md) | 上游稳定版同步至 v0.1.4801、宿主兼容性核对与 Actions 验收 |
| [docs/bugfixes-0.1.66.md](docs/bugfixes-0.1.66.md) | 上游稳定版同步、GitHub 更新器、完整 Actions 验收与正式签名发布 |
| [docs/bugfixes-0.1.65.md](docs/bugfixes-0.1.65.md) | 管理凭据隔离、敏感剪贴板、界面可访问性与发布防护候选 |
| [docs/bugfixes-0.1.64.md](docs/bugfixes-0.1.64.md) | 流式 `reasoning` 别名、重复思考防护、预算与上下文边界 |
| [docs/bugfixes-0.1.63.md](docs/bugfixes-0.1.63.md) | 官方加密备份的浏览器下载桥接、真实文件往返与正式发布 |
| [docs/bugfixes-0.1.62.md](docs/bugfixes-0.1.62.md) | 聊天分页与跨会话草稿隔离、系统敏感剪贴板及完整验收／正式发布 |
| [docs/bugfixes-0.1.61.md](docs/bugfixes-0.1.61.md) | 插件连续保存、紧凑会话菜单、密钥连接隔离与完整验收／正式发布 |
| [docs/bugfixes-0.1.60.md](docs/bugfixes-0.1.60.md) | 已保存启用／目录摘要、草稿文案与十项 Android 用例及正式发布 |
| [docs/bugfixes-0.1.59.md](docs/bugfixes-0.1.59.md) | 自动选择模型的原生连通测试、已保存启用集合及真实网关验收 |
| [docs/bugfixes-0.1.58.md](docs/bugfixes-0.1.58.md) | 保存期间模型输入保护、外部返回刷新与真实页面验收 |
| [docs/bugfixes-0.1.57.md](docs/bugfixes-0.1.57.md) | 聊天预设实际请求、失败草稿保留与原子存储验收 |
| [docs/bugfixes-0.1.56.md](docs/bugfixes-0.1.56.md) | 重复停止、独立清理凭证与实际 Android 通知重发验收 |
| [docs/bugfixes-0.1.55.md](docs/bugfixes-0.1.55.md) | 模型目录证书指纹、三态请求与真实核心 TLS 验收 |
| [docs/bugfixes-0.1.54.md](docs/bugfixes-0.1.54.md) | 运行中安全日志、停止后最终诊断与实际退出码 |
| [docs/bugfixes-0.1.53.md](docs/bugfixes-0.1.53.md) | 聊天与模型目录完整连接轮次、实际取消与 Room 收尾 |
| [docs/bugfixes-0.1.52.md](docs/bugfixes-0.1.52.md) | 插件完整会话、实际请求取消、延迟登录取消及恢复记录边界 |
| [docs/bugfixes-0.1.51.md](docs/bugfixes-0.1.51.md) | 同服务重连的连接轮次、跨轮次写入屏障与保存核对后编辑状态 |
| [docs/bugfixes-0.1.50.md](docs/bugfixes-0.1.50.md) | 提供商会话绑定、保存核对屏障、缓存代次与网关恢复修复及 Actions 验收 |
| [docs/bugfixes-0.1.49.md](docs/bugfixes-0.1.49.md) | 联通测试主动取消、会话租约、目录大小与时限及 Actions 验收 |
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

- 持久化凭据（提供商 Key、网关 Caller Key）保存在 App 私有目录；**系统备份关闭**。原生 SAF 导入导出入口尚未接入，现有 schemaVersion 1 元数据格式不等于官方加密完整备份，暂勿依赖该占位页迁移数据；
- 网关仅监听 `127.0.0.1`；管理面 3430 与网关面 3425 凭据隔离。凭据不写入 Intent、诊断日志或界面偏好；原生显式复制密钥或含密钥 cURL 时设置 Android 敏感剪贴板标记，外部浏览器复制由浏览器处理；
- 崩溃事后审计（`post_mortem.json`）先经 SecretRedactor 脱敏再截断；
- 不申请额外权限：仅 INTERNET / 前台服务 / 通知，无位置/存储/联系人访问。

## 后台限制说明

- 网关以前台服务（`specialUse`）运行，常驻通知可随时停止；用户 Stop/ForceStop 后**不自动保活**；
- Android 14+ 的电池优化白名单需用户手动授权（App 内一键引导），否则 Doze 可能中断长耗时推理；
- 网络切换（Wi-Fi ↔ 蜂窝）由 `NetworkConnectivityMonitor` 实时感知并在 UI 呈现；低内存触发 `TRIM_MEMORY_RUNNING_CRITICAL` 告警。

## 安全边界

完整核心继承 Android 宿主的鉴权与进程管理适配。Android 宿主管理 API 使用独立原生请求头与浏览器 session proof；网关 3425 只使用 Bearer。同 UID 插件不是沙箱。官方配置 API 已开放，退出、重启与原生升级由 Android 宿主负责。原生基础 OpenAI 兼容编辑使用 canonical/HMAC revision/严格锁/原子保存；浏览器和原生写入共用锁与版本检查。

## 验证与操作

`Build signed APK (manual release)` 在 `main` 更新时负责固定源码／补丁、ARM64 核心、APK 编译、签名与载荷校验，并把签名产物保存在对应 Actions run，不自动创建 Release。确认版本可用后，手动运行该工作流并勾选 `publish_release`；发布门禁还要求同提交最新可信 `Validation` attempt 的 `regression` 与 `runtime-emulator` 均已完成并成功。附件先上传至草稿并核对名称、大小和 SHA-256，再公开为 Latest，已公开版本不覆盖附件或移动 tag。核心按上游、Go／NDK 版本和补丁哈希缓存，UI 修改可复用核心。`Validation` 独立运行 JVM、Go、真实插件与 Android 模拟器测试，失败保留报告，不阻止已经成功构建的签名产物归档。文档修改不触发重复编译。本地不运行编译。

[下载 GitHub Latest 签名 APK](https://github.com/fplj-fplj/magpie-mobile/releases/latest)。历史 0.1.66 正式 APK 为 114,082,824 bytes，SHA-256 `01292c5b8814bc48c9ea5738b4f14e037a6488d7fea24ceb394dab8ead307e42`；历史 0.1.64 正式 APK 为 103,617,320 bytes，SHA-256 `c3b8c92bfb5172dc773447109d776ff9476ac49a32985138eddbfb4c6c89dc8a`；固定签名与 ARM64 载荷均已核对。

官方插件完整通过基线仍可通过 [`05072a47` Actions](https://github.com/fplj-fplj/magpie-mobile/actions/runs/43682069460)追溯；对应历史 Release 条目已按清理策略移除，运行证据与剩余项目记录在[插件接入文档](docs/official-plugin-integration.md)。

本地大版本升级基线：v0.1.3622 起步（旧基线 v0.1.3188 的 24 个补丁目标文件零变更，升级流程见技能库与 gap-analysis §5）。
