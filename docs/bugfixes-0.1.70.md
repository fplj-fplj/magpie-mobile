# 0.1.70 上游核心同步至 v0.1.4934

应用版本为 `0.1.70-upstream-v0.1.4934`（versionCode 71），Android 宿主核心为 `0.1.4934-androidhost.4`，固定上游提交 `f834c203a49234efc6ee3df83762700ae124fa24`。

- 提供商配置读取保留上游 `SearchesSet` 状态，以及 Android 宿主对原始文件内容计算的配置修订值。
- 官方 CLI 浏览器入口同时保留上游 `library` 和 Android 宿主 `mobilepolicy` 导入。
- 将上述冲突解决结果从 v0.1.4933 继续三方合并至 v0.1.4934，重新生成 13 个上游文件补丁与 28 个 Android overlay 的清单。Android 原生界面结构未更改。
- 上游 v0.1.4934 更新组路由的未计费失败记录、跳过原因和新会话席位计数。验收以 GitHub Actions 的完整 Validation 和同一提交的签名构建为准。
