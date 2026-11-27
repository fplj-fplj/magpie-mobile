# 0.1.71 上游核心同步至 v0.1.4936

应用候选版本为 `0.1.71-upstream-v0.1.4936`（versionCode 72），Android 宿主核心为 `0.1.4936-androidhost.4`，固定上游 revision `cd6d110eca72feb0a0c71bf870324c25de3e78e3`。

- 上游会话面板改进长会话阅读：显示会话总轮数、按需加载完整内容、突出每轮对话文本并将步骤折叠成单行，首次打开时展开展示步骤。
- 由 v0.1.4934 Android 宿主适配继续三方同步至 v0.1.4936，重新生成 13 个文件补丁及 28 个 Android overlay 清单。完整兼容性和运行验证需由 GitHub Actions 完成。
- 本机未运行编译或测试；只有最终候选完整 Validation 和同提交签名构建通过后才发布。

## 最终提交验收与正式发布

- 最终提交 `b6097dd00e4f576acaad800c89ea19a5180b3a66` 的 [Validation run 45946935424](https://github.com/fplj-fplj/magpie-mobile/actions/runs/45946935424) 成功：regression、Android API 35 x86_64 模拟器与 ARM64 capability job 均成功。已下载 Actions 回归报告，JVM core＋app 共 1,155 项、失败／错误／跳过均为 0；Android 32 项仪器用例无失败。
- 同提交正式签名构建及发布 run [45948834699](https://github.com/fplj-fplj/magpie-mobile/actions/runs/45948834699) 成功，固定证书、重建 ARM64 核心一致性和运行时 payload probe 均通过。公开 Release [`v0.1.71-upstream-v0.1.4936-b6097dd0`](https://github.com/fplj-fplj/magpie-mobile/releases/tag/v0.1.71-upstream-v0.1.4936-b6097dd0) 已成为 Latest，目标提交 SHA 与最终源码相符，包含四项附件。
- APK 为 114,973,396 bytes，SHA-256 `1bad55fbac8df9175af939df94c6158e73936143401bf975becc788f47c0a617`。公开下载的 APK 与 `.sha256` 附件匹配，GitHub APK asset digest 也一致；静态元数据显示 applicationId `cc.fplj.magpiemobile`、versionName `0.1.71-upstream-v0.1.4936`、versionCode `72`，签名证书指纹为固定证书 `dee1d3f485ee17a6184178eaab318d6333fb8b67e7276146a30a03e1f9ee09d6`。
