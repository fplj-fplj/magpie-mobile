# 0.1.68 上游核心同步至 v0.1.4807

0.1.68 将锁定的上游核心由 `yetone/magpie` `v0.1.4801` 升级至 `v0.1.4807`（commit `a8f96beb1c8ea9aa53867676eeb779ca08bd5025`），Android 宿主核心版本更新为 `0.1.4807-androidhost.4`，应用版本升为 `0.1.68-upstream-v0.1.4807`（`versionCode = 69`）。上游 `v0.1.4807` 于 2026-11-25 发布，修改了最多可容纳的模型目录及模型行标签；其它上游内容按经校验的同步清单纳入。

## 宿主兼容与回归

- 通过三方同步合并 13 个整文件补丁与 28 个 Android overlay。原生管理 API 请求头、一次性浏览器编辑 session proof、loopback 监听、官方插件运行桥接及官方加密备份浏览器下载桥接保持不变。
- RikkaHub 页面结构、暖纸色主题与霞鹜文楷屏幕阅读版保持不变，官方 npm/GitHub/SAF 插件仍由上游 Go 与 Bun runtime 执行。
- 不在本地运行 Gradle、Kotlin/JVM、Go 或 Android 编译与测试。所有构建和回归验证均通过 GitHub Actions 执行。

## 已通过的上游适配候选

上游自动同步 PR #4：`automation/upstream-v0.1.4807`，适配提交 `61d2136295cc87e25a32a34148c2fba66341d548`，合并提交 `fd412911177c50ec5ae291703feef9272559ac4f`。

- Validation `45657927523`：regression、runtime-emulator、runtime-arm64-capabilities 均 success。JVM core 668 项、app 487 项，共 1,155 项通过；Android API 35 x86_64 模拟器 32 项全部通过，失败／错误／跳过为 0，`android-errors.txt` 为空。
- Core artifact `15045110356`：Go mobile 53 项、AndroidHost 61 项；宿主合成上游、真实目录 TLS、官方与社区插件、参数转发及 plugin race 均通过，官方插件 smoke 使用 `0.1.4807-androidhost.4`。
- 签名候选 run `45657931530`：build success，publish 按 `publish_release=false` 预期 skipped。APK 为 114,210,220 bytes，SHA-256 `934aeb26415246b44a5bdd0392304143418d728d084bfb8f8c2980239edd2f06`；固定证书、包名 `cc.fplj.magpiemobile`、ARM64 核心 `0.1.4807-androidhost.4` 与 Bun 载荷均静态核对通过。它是上游适配候选包，并非 0.1.68 正式版本包。
- 对 0.1.67 旧版本首次 Validation `45652393923` 中固定 GitHub 社区插件安装请求曾返回 HTTP 400；同一精确 SHA 的复验 `45656141044` 后来全套通过，固定 GitHub archive 可下载。随后 4807 适配的完整插件集成和模拟器测试也全部通过；未更改插件逻辑或略过用例。

## 0.1.68 最终提交验收与 Release

最终生产提交尚待设置应用版本、提交发布记录并推送；该精确提交的完整 Validation 与签名候选构建仍待 GitHub Actions 完成后记录。未完成最终验证前不发布 Release。
