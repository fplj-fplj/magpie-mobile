# 0.1.51 提供商重连与保存核对

应用提交：`a24174f5610e61f3d056328df2420cc34c68b4c4`。版本 `0.1.51-provider-reconnect`／versionCode 52，备份文档 appVersion `0.1.51`。保持现有暖纸色、RikkaHub 页面结构、霞鹜文楷屏幕阅读版及官方三类插件机制。

## 修复范围

| 触发场景 | 修复与边界 |
| --- | --- |
| 同一 Service owner 在 BACKOFF 后重新就绪，页面复用健康旧缓存或加入正在取消的旧 GET | 提供商会话使用非秘密的 `(owner, epoch)`。完整 key 同 READY 一起发布，页面直接观察；新 epoch 分离旧缓存和在途读取，即使中间断线状态被合并也会刷新。旧入口、迟到结果和收尾不能影响新轮次。 |
| 旧轮次的保存尚未退出，新轮次尝试核对 | 写入屏障继续按 owner 保存。新 epoch 的普通读取及恢复读取均等待旧 HTTP 实际退出，恢复只使用之后发起的新 GET。观察者取消不提前释放屏障。 |
| 未知编辑保存已经提交，核对后旧编辑器仍持有提交前 revision | 将恢复核对与普通刷新分开。健康的新恢复读取只在 key、UI 操作序号与恢复标记仍匹配时解除阻塞，并关闭旧编辑器、清除旧可提交状态，提示从当前列表重新打开；不从列表摘要构造 editable revision。 |
| 未知新建已经提交，旧编辑器仍处于新建状态 | 同样退出旧新建表单，避免继续提交同 ID。失败、配置文件异常和取消保留恢复标记与阻塞状态；普通刷新保留未保存草稿。 |

管理请求在取得凭据前及实际使用时都校验完整连接 key、binding 和 config 身份。重复 RUNNING 不创建新轮次，Activity 重建也不更换已验证连接。任何未知保存仍只读核对，不自动重放。

## 验证状态

本地仅执行源码及跨模块审查、Python 静态／资源检查与 `git diff --check`；不运行 Gradle、Go、JVM 或 Android 编译与测试。

新增受控时序回归覆盖同 owner 跨 epoch 的缓存、在途请求、旧结果、写入屏障、可观察 READY、排队旧操作，以及未知新建／编辑后的恢复。Android 仪器回归在真实 Go 核心中修改提供商名称，暂停并重新鉴权同一运行中的核心，核验 Service owner 不变、epoch 递增、旧 key／客户端拒绝、健康旧缓存被实际新 GET 替换。此步骤不重启核心进程；既有测试继续覆盖实际停机取消和进程回收。

[同提交完整 Validation](https://github.com/fplj-fplj/magpie-mobile/actions/runs/44611302886) 与[同提交签名候选](https://github.com/fplj-fplj/magpie-mobile/actions/runs/44611501541) 均已通过。已下载实际报告核对：

| 检查 | 结果 |
| --- | --- |
| 核心 JVM | 555 项，0 failure／error／skipped |
| 应用 JVM | 273 项，0 failure／error／skipped |
| Go mobile 回归 | 35 项通过 |
| Go Android 宿主回归 | 25 项通过 |
| Linux 官方／社区插件集成 | 提供商／工具、同步中间件、异步 Hooks、社区插件全部通过 |
| 插件宿主 race | plugin 与 gui 均通过 |
| Android API 35 x86_64 实际安装运行 | 1 个完整仪器测试，0 failure／error／skipped，套件 104.261 秒 |

本轮新增 24 项 JVM 方法，总计 828 项。提供商缓存 21 项、操作控制 18 项、连接轮次 7 项与恢复状态 8 项均通过。JVM artifact ID：`14572473730`；核心运行证据：`14573132736`；Android 运行证据：`14572882227`。

Android 的 `provider-same-owner-reconnect.txt` 确认实际管理编辑完成后，同一运行核心重新鉴权、连接轮次变化、旧 key／客户端拒绝及新的真实列表 GET；`provider-session-cancellation.txt` 与 `shutdown.txt` 确认实际停机取消与 Go／Bun 进程回收。既有三项联通测试、官方插件登录／工具／中间件／Hooks／SSE、全屏编辑及键盘布局也通过，已检查提供商和网关实际截图。

签名候选使用 `publish_release=false`，仅归档到 Actions，artifact ID：`14573575479`。已下载并核实归档 digest、固定证书 SHA-256 `dee1d3f485ee17a6184178eaab318d6333fb8b67e7276146a30a03e1f9ee09d6` 的 CI 检查、APK 103,431,200 字节及包内核心哈希与构建记录一致。生产构建的 ARM64 PIE、16KB 对齐和载荷检查通过。

完整验证与签名候选通过后，启动[正式签名与发布](https://github.com/fplj-fplj/magpie-mobile/actions/runs/44613300312)。build 与 publish 均成功，[正式 Release](https://github.com/fplj-fplj/magpie-mobile/releases/tag/v0.1.51-provider-reconnect-a24174f5) 已核实为非草稿、非预发布及 Latest，Release ID：`451920232`。

- APK：`magpie-mobile-0.1.51-provider-reconnect.apk`，103,431,200 字节。
- APK SHA-256：`0e209554d48a33a7d6cba87ddffd946040a293aece71737f7b1e0a412a92a46c`；GitHub 附件 digest 与下载的 SHA-256 文件一致。
- Release 包含 APK、SHA-256、build-info 与核心构建来源四项附件；下载的校验附件与 build-info digest、正式运行 ID、应用提交及固定签名证书均已核实，tag 精确指向同一个已验证的应用提交。
- 候选仅保留在 Actions；正式发布以同一已验收提交重新构建并签名，正式 APK 的校验值以上述附件为准。

ARM64 生产 APK 的签名与载荷检查、Android API 35 x86_64 模拟器运行分别记录；ARM64 真机执行不在当前验证范围。

## 后续巡查

源码巡查定位的插件排队写入在 IO 阶段获取当前客户端、登录 Start 响应返回前取消的任务核对，已在后续 [0.1.52 修复与验收](bugfixes-0.1.52.md) 中完成，包含完整连接租约、一次取消与真实终态读取、未知状态恢复及 47 项新增 JVM 回归。聊天／模型目录尚未使用完整连接轮次的问题仍留待后续独立批次，不计入 0.1.51 或 0.1.52 的已完成范围。
