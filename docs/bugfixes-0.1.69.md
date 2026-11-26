# 0.1.69 上游核心同步至 v0.1.4877

上游 Magpie 稳定版由 `v0.1.4807`（`a8f96beb1c8ea9aa53867676eeb779ca08bd5025`）更新到 `v0.1.4877`（`148b6ca29cf41d7d885b52a4ab1ea1972711c793`），Android 宿主核心为 `0.1.4877-androidhost.4`，应用版本为 `0.1.69-upstream-v0.1.4877`（versionCode 70）。

## Android 宿主适配

- 上游自动同步 PR #6 以三方合并更新 13 个完整源码补丁及 28 个 Android overlay；同步报告确认所有来源文件、manifest hash 和 overlay 均与锁定 revision 对应。
- 保留 Android 原生管理请求头、浏览器编辑会话 proof、loopback 限制、插件 Go/Bun bridge、官方加密备份浏览器下载桥接，以及现有暖纸色、RikkaHub 页面与霞鹜文楷字体。
- 上游 `v0.1.4877` 的发行说明新增 Codex skills 自动链接支持，随完整上游核心纳入。
- 本机不运行 Android、JVM/Kotlin 或 Go 构建与测试；所有验证由 GitHub Actions 执行。

## v0.1.4877 适配候选验证

候选提交 `6bce298537dcc71de81271b9ce0f57d80402cd7a` 的 Validation `45847223712` 全部通过：

- core JVM 668 项与 app JVM 487 项，共 1,155 项，失败／错误／跳过均为 0。
- Android API 35 x86_64 模拟器 32 项，失败／错误／跳过均为 0；`android-errors.txt` 为空。
- Go mobile 53 项、AndroidHost 61 项通过；两个生产模型目录 TLS 报告均通过。
- 官方与社区插件真实集成、provider/chat/gateway/plugin capability 行为和 plugin race 均通过；Android 全量运行 artifact 已归档。

候选签名构建 run `45847229192` 成功，publish 按未授权候选规则跳过。该候选时仍内嵌 0.1.68 版本号，故不用于发布；正式版本号提升到 0.1.69 后，必须对最终提交重新运行完整 Validation 与签名构建，并核验同提交产物。

## 最终提交验收与发布

最终生产提交、同提交完整 Validation、签名候选、Release 附件、Latest 状态与保留 Release 核对将在发布完成后记录于此。
