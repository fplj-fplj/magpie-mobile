# 0.1.66 GitHub 更新中心与上游同步

本候选增加两个更新路径：构建侧同步 `yetone/magpie` 稳定 Release 并应用 Android 宿主适配；应用侧从本仓库 Latest Release 检查并获取完整签名 APK。候选上游固定为 v0.1.4759（commit `95e6a4180866afde186e0f1a25369388c7fd7e7f`），尚未通过 GitHub Actions 完整验收或发布。

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

本机不运行 Gradle、Kotlin/JVM、Go、Android、Node 或测试。当前代码仍为待验收候选；完整 Go/JVM/模拟器回归、签名构建与 APK 检查必须经 GitHub Actions 通过后，才可发布。
