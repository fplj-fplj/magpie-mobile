# 0.1.53 聊天与模型目录连接隔离

应用提交：`19a3fdb1c8212dff4bc26ccec7f3e7572edd2aed`。版本 `0.1.53-chat-session`／versionCode 54，备份文档 appVersion `0.1.53`。保持暖纸色、RikkaHub 页面结构、霞鹜文楷屏幕阅读版和官方三类插件的执行机制。

## 修复范围

| 触发场景 | 修复与边界 |
| --- | --- |
| 同一服务重新鉴权，页面没有观察到中间断线 | 目录按完整 `(owner, epoch)` 识别连接，重新读取真实目录，不能沿用健康旧缓存的有效标记。 |
| 刷新或断线期间仍显示旧模型 | 保留名称及当前选择作为未确认缓存，清除目录验证标识，禁用模型选择和发送；新目录成功证明模型已移除时才清空选择。读取失败不自动换模型或重写偏好。 |
| 新连接已就绪，但界面重连 effect 尚未执行 | 点击发送携带目录原本的完整 key，由 Native 同步拒绝旧 key，不能在 IO 中取得新连接凭据。选择和迟到偏好恢复也检查实际完整 key。按钮回调另从 ViewModel 即时核对目录有效性与模型成员，覆盖同 key 手动刷新尚未重绘的窗口，以及新目录已确认但旧模型面板回调迟到的窗口。 |
| 旧目录请求迟到，或旧 finally 在新请求后执行 | 使用完整 key 和请求序号核验结果、错误、选择及清理；不覆盖新目录或取消新 loading。旧模型面板按打开时的连接标识关闭。 |
| 目录或聊天仍在队列中、ViewModel 已清理 | 启动前捕获的租约也有 completion 释放，实际 IO 前再次校验；失效任务不开始 HTTP，聊天不新增 Room 轮次。 |
| HTTP 响应头或 SSE 正文静默 | Native 暂停、重连、停止以及 ViewModel 清理撤销同一请求 control，主动关闭 Socket。目录、流式及允许的一次普通降级均不换连接或自动重发。 |
| 本地 begin 已提交，取消使调用方未取得 turn ID | wrapper 已知 ID 时 best-effort 记录该轮失败，不留下可自动重放的待定发送。失败收尾不受失效网络租约阻挡。 |
| 收到完成事件后，存储与取消竞速 | `complete()` 入口前校验取消；存储调用一旦进入且成功返回，其成功结果为准。内存提交标记覆盖 IO 返回 Main 时的 prompt cancellation，避免成功记录却保留可误重发的草稿。 |

不改 Room schema，不持久化草稿或连接身份，不把失败 partial／思考内容纳入下一轮上下文。正常旋转继续保留同一个 ViewModel 和付费请求。`complete()` 调用入口是现有存储提交边界，不宣称取消能回滚已经进入的事务或撤销上游推理。

## 验证安排与状态

本地仅源码审查、Python 静态／资源检查及 `git diff --check`。不在本地运行 Gradle、Go、JVM 或 Android 编译与测试。

JVM 回归覆盖完整 key、同 owner 新 epoch、控制队列、迟到结果／偏好、实际静默 TCP 取消、未启动任务释放、Room begin／complete 提交边界、草稿与降级控制。本轮新增 47 项：AppShell 20 项、目录 Controller 11 项、Chat ViewModel 13 项、gateway 租约 3 项；另补 Native 单例的旧 key 拒绝断言。

Android 仪器回归使用已安装 APK 中的实际 Go 核心、生产 Chat／Catalog ViewModel 与真实 Room：在受控 SSE 返回部分内容后保持静默，同 owner 重鉴权，核验上游 EOF／reset、失败记录、草稿保留和上下文排除；刻意不通知测试目录 VM，旧目录点击要求零新请求／零 Room 写入；读取新目录后只有下一次显式发送才保存成功。同时核验旧 gateway 租约取消、旧 key 拒绝与新目录 GET。

[同提交完整 Validation](https://github.com/fplj-fplj/magpie-mobile/actions/runs/44628144849) 与[签名候选](https://github.com/fplj-fplj/magpie-mobile/actions/runs/44628257642) 均已成功。已经下载实际报告和产物，并逐项核对 GitHub artifact 的 SHA-256 digest：

| 检查 | 结果 |
| --- | --- |
| 核心 JVM | 566 项，0 failure／error／skipped |
| 应用 JVM | 356 项，0 failure／error／skipped |
| Go mobile 回归 | 35 项通过 |
| Go Android 宿主回归 | 25 项通过 |
| Linux 官方／社区插件集成 | 提供商／工具、同步中间件、异步 Hooks、社区插件全部通过 |
| 插件宿主 race | plugin 与 gui 均通过 |
| Android API 35 x86_64 实际安装运行 | 1 个完整仪器测试，0 failure／error／skipped，套件 111.374 秒 |

JVM 总计 922 项，比 0.1.52 新增 47 项。JVM artifact ID：`14580357456`；核心运行证据：`14581181900`；Android 运行证据：`14579314517`。

Android 的 `chat-catalog-same-owner-reconnect.txt` 确认：实际 Go 网关的静默 SSE 上游发生 EOF／reset，旧轮次记录 FAILED，没有 RUNNING 残留，草稿留在存活 VM 中，partial 未进入上下文；界面尚未通知的旧目录点击没有创建 worker、HTTP 或 Room 写入；新目录确认后只有一次显式重试，保存为成功轮次。测试还在同一个 Main 回调中启动目录刷新并立即校验，覆盖重绘之前的旧按钮窗口；新 epoch 目录确认后继续拒绝旧模型面板 key。

既有提供商／插件重连、实际停止及进程回收证据继续通过，`android-errors.txt` 为空。已检查 `chat-composer-portrait.png`、`chat-composer-keyboard.png` 和 `chat-model-picker.png`：模型选择、输入和发送均在可见安全区，保留暖纸色、文楷屏幕阅读版与顶部品牌字体，没有裁切或重叠。

签名候选使用 `publish_release=false`，仅归档至 Actions，artifact ID：`14579139306`。实际 APK 为 103,488,620 字节，候选 SHA-256 为 `a08199738a438a0b290f530ea33957a7cdda7825804faef502ce3609ffbd234a`；已检查实际 APK v2 证书 SHA-256 `dee1d3f485ee17a6184178eaab318d6333fb8b67e7276146a30a03e1f9ee09d6`、CI 的签名验证结果及包内 ARM64 PIE 核心哈希 `52f9b2599b120894780162934ce0120f174b28b3ee14db7b73f2aee1b93d5ca5`。生产构建的 16KB 对齐与载荷检查通过。正式发布会重新构建，正式 APK 使用其发布附件中的校验值。

最初候选 `e85804c2` 的 920 项 JVM 与 Android 回归已通过；终审补上同 key 刷新和旧面板回调的即时校验后，推送最终 `19a3fdb1`，旧 Validation 按并发策略自动取消，没有发布。最终提交重新执行全部验证，新增两项回归，没有删除断言、跳过测试或扩大超时。

完整验证及签名候选通过后，运行[同提交正式签名与发布](https://github.com/fplj-fplj/magpie-mobile/actions/runs/44630304385)。build 与 publish 均已成功，[正式 Release](https://github.com/fplj-fplj/magpie-mobile/releases/tag/v0.1.53-chat-session-19a3fdb1) 已核实为非草稿、非预发布及 Latest，Release ID：`452018572`。

- APK：`magpie-mobile-0.1.53-chat-session.apk`，103,488,620 字节。
- 正式 APK SHA-256：`f5aa95cc730b0f904972b7d9fbd6f281735be26c0f72d97e5536cd9cd6a90f6b`，与 GitHub 附件 digest、下载的 SHA-256 文件及正式构建 artifact 内实际 APK 一致。
- 四项附件为 APK、SHA-256、build-info 与核心构建来源。已下载并核对校验附件和 build-info 的 digest、正式运行 ID、应用提交及固定签名证书，tag 精确指向上述已验收提交。
- 正式签名 artifact ID：`14580779153`，已下载并核对其 digest；实际 APK v2 证书与包内 ARM64 PIE 核心哈希同上。
- 候选仅归档 Actions；仅在同一提交的完整验证与签名候选通过后，才正式构建、签名和发布。验收日期：2026-11-19（UTC）。

ARM64 生产 APK 的签名／载荷检查与 x86_64 模拟器执行分别记录，本轮未执行 ARM64 真机测试。

## 后续巡查

运行日志实时发布和异常退出码单独处理；本轮不混入这些改动，也不扩大其他工具页面的连接迁移。
