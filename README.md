# Magpie-Mobile

把 [usemagpie.ai 的 Magpie](https://github.com/yetone/magpie)（AI 模型网关，MIT）移植为 Android 原生独立 APK：提供后台模型网关、其他 App 接入、应用内聊天与双编辑入口。当前实现范围见 [完整核心与双编辑说明](docs/full-core-dual-editors.md)，长期规划见 [移植计划](plan.md)。

## 当前源码版本：0.1.40-official-plugins（CI/真机验收分开）

- **上游核心**：v0.1.3781（commit `b52c7111`）；
- **新技术路线**：官方完整 CLI 核心 + Android 宿主适配，APK 使用 `nogui,androidhost`，不再使用 `mobile` 构建标签；提供官方浏览器编辑器与原生提供商编辑两种入口。详见 [路线与双编辑说明](docs/full-core-dual-editors.md)；
- **界面更新（P9）**：暖纸色与文楷排版、精简网关首页、二级管理导航、聊天消息分层；修复网络状态与电池设置入口。详见 [界面更新与验收说明](docs/ui-update-0.1.38.md)；
- **官方外部插件**：原生页接入 npm／GitHub／SAF 安装、启停、更新、删除、参数、登录与诊断；提供商／工具、同步中间件和异步 Hooks 保留官方执行机制。详见 [插件接入与运行验收](docs/official-plugin-integration.md)；
- **真机稳定性（P4/P5）**：网络热切换监听、电池优化白名单引导、低内存告警、冷启动遥测（<800ms 门槛）、崩溃事后脱敏审计；
- **功能对齐（P5/P6）**：历史会话重命名/删除、用量洞察四段卡片（聚合/热力图/额度/历史）、开源许可声明查看、密钥可见模型目录；
- **既有能力（P2/P3）**：多提供商安全接入、模型目录发现、Chat Profiles、多会话持久化。路由、号池与 MCP 的高级核心配置由官方网页编辑；旧原生页面的示例尚未全部对接。

## 文档索引

| 文档 | 内容 |
| :--- | :--- |
| [docs/full-core-dual-editors.md](docs/full-core-dual-editors.md) | 完整 CLI 路线、双编辑入口、RikkaHub 提供商结构参考与验收 |
| [docs/official-plugin-integration.md](docs/official-plugin-integration.md) | 官方三类外部插件、原生管理、运行边界与候选发布 |
| [docs/official-plugin-adaptation-plan.md](docs/official-plugin-adaptation-plan.md) | 实施要求、阶段与完整验收矩阵 |
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

CI 执行 Python 应用器、JVM 测试、legacy mobile 与完整宿主 Go 回归、真实 Go+Bun 插件宿主测试和 Android APK 运行测试，再发布固定 v0.1.3781/Go1.26.8 `nogui,androidhost` Android ARM64 PIE 核心 `0.1.3781-androidhost.2`（16KB 页对齐）与固定签名候选 APK。发布等待构建和运行检查通过，附载荷 SHA-256 与签名证书；ARM64 真机证据和个人账号验证分别列出，不由 x86_64 测试推断。本地不运行编译。

官方插件完整通过基线：[`654ce191` Actions](https://github.com/fplj-fplj/magpie-mobile/actions/runs/43677903987) 与[对应签名候选](https://github.com/fplj-fplj/magpie-mobile/releases/tag/v0.1.40-official-plugins-654ce191)。候选 APK 与构建／运行证据对应同一提交；运行证据与剩余项目记录在[插件接入文档](docs/official-plugin-integration.md)。

本地大版本升级基线：v0.1.3622 起步（旧基线 v0.1.3188 的 24 个补丁目标文件零变更，升级流程见技能库与 gap-analysis §5）。
