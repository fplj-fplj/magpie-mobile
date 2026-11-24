# 0.1.66 GitHub 更新中心与上游同步

0.1.66 增加两个更新路径：构建侧同步 `yetone/magpie` 稳定 Release 并应用 Android 宿主适配；应用侧从本仓库 Latest Release 检查并获取完整签名 APK。上游固定为 v0.1.4759（commit `95e6a4180866afde186e0f1a25369388c7fd7e7f`）。完整 Actions 验收和签名候选均已通过；本记录所处最终提交仍需重复验证与签名构建，然后才发布。

## GitHub Release 应用更新

- “更多”页新增更新卡片，显示当前 App、内核和源码信息；可查看 Release 说明、检查稳定版、下载进度及核验结果。
- 客户端只查询固定公开仓库和 Latest Release，要求完整的四附件协议与机器构建信息；拒绝 draft、prerelease、降级、异常摘要、外域地址或不完整资产。
- 下载在应用私有缓存中流式计算 SHA-256。Android 再校验 APK 包名、版本名与 versionCode、固定签名证书、production 标志和 ARM64 Go/Bun PIE 载荷。
- 网关拥有进程或停止清理、聊天/提供商/插件操作仍在执行时禁止安装。安装只交给 Android 系统安装器并要求用户确认；应用不热替换核心二进制。
- 安装请求只在“更多”页处于前台时交给系统；重复安装期间按钮禁用。进入与离开系统页面均重新核对网关清理屏障和操作状态。APK 哈希与 ZIP 校验响应协程取消，下载结束后取消也会清理 `.part`，相同目标路径不能被并发下载覆盖。

## 上游核心同步

- 单一锁文件固定仓库、稳定 tag、完整 commit、Go 与 Android host 核心版本；普通构建从锁检出并将宿主适配应用到干净的 CI 工作树。
- 定时/手动同步从上游稳定 Release 检查新版本，通过三方适配生成候选分支并执行完整 Actions 验证；冲突和失败报告留在 CI，不改写锁、不自动发布。
- 只有同步 PR 经审阅、完整验证与签名发布门禁后才进入正式 APK。

静态适配核对：4759 manifest 为 13 个上游整文件补丁和 28 个 Android overlay，干净上游应用检查共 41 个文件；公开 `chatPageOpen` 继续受 Android 路由策略拦截，备份导出保留 `usage` 字段和 Android 下载桥，原生 updater 在启动副作用前关闭。同步器只自动合并已明确限定的 import 和 updater 护栏冲突，语义冲突仍要求人工处理。上述静态核对不代替 Actions 验证。

本机不运行 Gradle、Kotlin/JVM、Go、Android、Node 或测试；所有构建和测试仅由 GitHub Actions 执行。

## Actions 收尾记录

候选 `c64c1b415c214a2a5f21dae55b376d79d235872c` 的签名构建 `45447667303` 成功，未发布 Release。Validation `45447591342` 的 JVM core 668＋app 487 共 1,155 项通过；Go 回归和 Android 运行验收仍发现问题，因此该提交没有发布。

- 上游 `testenv` 在测试退出时检查包级正则初始化，Android overlay 的三处 `regexp.MustCompile` 触发失败。已改用上游 `lazyre.New`，保持原匹配模式及 `MatchString` 调用，并更新 manifest 的文件哈希。正则检查修复后，Go 又发现官方备份导出请求包含上游新增 `usage: true` 字段，而 fixture 期望未更新；生产桥本来就保留该字段，现同步补齐预期。
- Android API 35 x86_64 完成 32 项，31 项通过。`PackageManager.getPackageArchiveInfo()` 对当前 APK 返回的 application flags 为 0，因此 instrumentation 不再把该读取结果当作 Debug 包证据；仍会检查候选签名不是正式证书，并验证更新器拒绝该归档。生产 APK 的不可调试要求保持不变。
- 提供商用例在选择第二个模型后等待底部页关闭超时，同阶段日志记录单帧停顿约 9.3 秒。改为在 Compose 帧推进循环之外读取语义树，给予 30 秒退场预算；仍要求选择页实际消失并验证已选模型及未保存草稿边界。

`1200556ad587297bb6199db3ef2c14727dd21342` 的完整 Validation `45458451072` 已通过：JVM core 668＋app 487 共 1,155 项；Android API 35 x86_64 的 32 项零失败、错误与跳过；Go mobile／AndroidHost、安全参数转发、真实 TLS、官方与社区插件、插件 race 全部成功。Actions 签名构建 `45458459475` 成功，APK 静态核对确认包名、版本、固定证书、ARM64 ELF PIE 核心、Bun、版本与上游核心均匹配。此验证记录提交尚未发布；最终文档提交将再次完整验证和签名，准确发布证据随后补入。
