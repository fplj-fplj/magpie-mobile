# 0.1.70 上游核心同步至 v0.1.4934

应用版本为 `0.1.70-upstream-v0.1.4934`（versionCode 71），Android 宿主核心为 `0.1.4934-androidhost.4`，固定上游提交 `f834c203a49234efc6ee3df83762700ae124fa24`。

- 提供商配置读取保留上游 `SearchesSet` 状态，以及 Android 宿主对原始文件内容计算的配置修订值。
- 官方 CLI 浏览器入口同时保留上游 `library` 和 Android 宿主 `mobilepolicy` 导入。
- 将上述冲突解决结果从 v0.1.4933 继续三方合并至 v0.1.4934，重新生成 13 个上游文件补丁与 28 个 Android overlay 的清单。Android 原生界面结构未更改。
- 上游 v0.1.4934 更新组路由的未计费失败记录、跳过原因和新会话席位计数。验收以 GitHub Actions 的完整 Validation 和同一提交的签名构建为准。

## 最终提交验收与发布

- 最终提交 `1e87bcc34a8c540e1c6926fc05b2fcd5cc075423` 的 [Validation run 45940919896](https://github.com/fplj-fplj/magpie-mobile/actions/runs/45940919896) 成功：`regression` 和 Android API 35 x86_64 `runtime-emulator` 均成功；ARM64 capability job 按 push 规则跳过。构建／核心回归报告及 Android runtime evidence 已由 Actions 生成。
- [正式发布构建 run 45943337172](https://github.com/fplj-fplj/magpie-mobile/actions/runs/45943337172) 成功，固定签名证书、APK 内 ARM64 核心与 CI 重建载荷一致性及运行时 payload probe 均通过。最初候选构建遇到 Maven Central HTTP 429；同一提交重跑成功，未因此绕过任何验证。
- Release [`v0.1.70-upstream-v0.1.4934-1e87bcc3`](https://github.com/fplj-fplj/magpie-mobile/releases/tag/v0.1.70-upstream-v0.1.4934-1e87bcc3) 已公开并成为 Latest，目标 SHA 与最终提交一致，包含四项发布附件。APK 114,961,588 bytes，SHA-256 `da490efbacde7360cfeb8aced8fefa85bb89935af5f3c88691608f0b4db6db22`；真实公开下载与 checksum 附件相符，GitHub asset digest 也相符。
- 公开 APK 下载后仅做静态元数据读取以确认包名、版本与签名；本机未运行编译或测试。
- Release 清理后仅保留 0.1.70 Latest 与长期保留的 0.1.62 Release object，0.1.68 的 Git tag 保留。0.1.68 APK 与 checksum 已下载核对（114,210,220 bytes，SHA-256 `b0b1cf7f68ce50e4dd9b8cbb00864b9e4bd374b1faaa9dad83da3508df1f082f`）。
