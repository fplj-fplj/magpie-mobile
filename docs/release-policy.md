# Release 发布规则

`main` 上的每次应用或核心改动都会运行 `Build signed APK (manual release)`。构建 job 完成固定源码、ARM64 核心、APK 编译、固定签名、载荷哈希和运行时探测后，会把签名 APK 与证据保留在对应 Actions run。

普通 push 不创建 GitHub Release。准备发布一个确认可用的版本时，手动运行该工作流并勾选 `publish_release`：

1. 用目标提交运行工作流；
2. 确认 build job 的全部签名与载荷检查通过；
3. 勾选 `publish_release` 后重新运行，或直接以该选项手动触发；
4. publish job 只会使用本轮构建并校验过的产物，创建或更新 `v<versionName>-<sha8>`，并标记为 Latest。

`Validation` 继续独立运行 JVM、Go、插件和 Android 模拟器回归。验证失败会保留报告，但不会删除已经通过签名与载荷检查的 Actions 产物；是否发布由人工确认。

Release 页面只保留当前可用 Latest，以及明确需要长期保留的正式版本。同一功能的中间修复、失败构建和候选 APK 不再保留在 Releases；历史构建仍可从 Actions run 追溯。
