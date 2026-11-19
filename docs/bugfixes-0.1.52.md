# 0.1.52 插件会话与登录取消

应用提交：`d5eee979652d76adf18c0224401dded6a1ac14df`。版本 `0.1.52-plugin-session`／versionCode 53，备份文档 appVersion `0.1.52`。保持现有暖纸色、RikkaHub 页面结构、霞鹜文楷屏幕阅读版及官方三类插件机制。

## 修复范围

| 触发场景 | 修复与边界 |
| --- | --- |
| 插件操作排队后，IO 执行时重新取得当前客户端 | 在调用线程同步捕获完整 `(owner, epoch)`、固定凭据与请求租约。每个实际请求与结果再次检查身份；同 owner 的新 epoch 也不能被旧操作使用。 |
| 暂停、停止或重连后，旧插件请求仍等待静默响应 | Native 生命周期撤销已登记租约并关闭实际连接。预调度取消和在途取消都释放资源；取消异常不被包装为读取失败或未知写入，成功关闭只注销租约。 |
| 登录 Start 返回任务 ID 前点击取消 | 保留取消意图，不因关闭 Start 连接丢失迟到 ID。原 Start 有界返回后，在同一个捕获操作中只发送一次 Cancel，并 GET 同一 ID 以核对真实终态。双击不重复 POST。 |
| 取消与实际完成竞速，或取消 HTTP 成功但状态未知 | 以实际 GET 的终态为准；迟到 `done` 显示完成，只有实际 `canceled`／`cancelled` 才显示核心已确认取消。取消未知只允许读取，不自动重复 POST。 |
| Start 或回执结果未知、Activity／进程重建 | 仅持久化提供商 ID、阶段和取消意图。任务 ID、链接、输入、密钥与数值连接身份均不持久化；恢复为 DETACHED，不用新连接接管旧任务。已登录提供商列表不能证明本次未知 Start 的结果。 |
| 希望解除本机未知状态 | “结束本地跟踪”需二次确认，并等所有实际本地登录请求退出。仅清除本机观察记录，不撤销服务端任务，也不自动 Start／Cancel；说明旧任务仍可能继续。 |
| SAF 文件复制期间重连 | 复制前记录完整连接 key，提交前要求仍为该连接；拒绝把已过期的导入提交给替换核心。 |

插件读取仍使用九十秒预算，写入仍为六分钟。官方核心没有按提供商寻找未知登录 ID 的公开接口，因此无 ID 的未知 Start 无法由列表推断完成或取消。正常旋转保留 ViewModel 与原操作。

## 验证状态

本地只做源码及跨模块静态审查、Python 静态／资源检查与 `git diff --check`；不运行 Gradle、Go、JVM 或 Android 编译与测试。

新增 47 项 JVM 方法：核心请求取消 11 项、应用租约 4 项、操作控制 13 项、ViewModel 新增 19 项。受控时序覆盖完整连接 key、排队编辑／Start 的零 HTTP、防止连续操作跨连接、静默响应的实际 Socket 取消、预调度释放、登录迟到 ID／完成／取消状态、未知结果、重建隔离、回执未知后的提交限制、固定轮询预算及新流程清除旧成功提示。运行总数以 Actions 实际报告为准。

Android 仪器回归使用实际 Go+Bun 插件，在授权函数中等待本机门闩，确认 Start 尚未返回时两次点击取消不会发出重复请求；释放后核验一次 Cancel 和同 ID 的真实取消状态。UI 另外检查等待中的恢复卡、结束本地跟踪二次确认、继续核对和实际取消状态，并保存截图。既有真实同 owner 重鉴权及停止步骤也检查插件租约撤销。

[同提交完整 Validation](https://github.com/fplj-fplj/magpie-mobile/actions/runs/44620941935) 与[签名候选](https://github.com/fplj-fplj/magpie-mobile/actions/runs/44620944506) 均已成功。已下载实际报告及产物，并核对 GitHub artifact digest：

| 检查 | 结果 |
| --- | --- |
| 核心 JVM | 566 项，0 failure／error／skipped |
| 应用 JVM | 309 项，0 failure／error／skipped |
| Go mobile 回归 | 35 项通过 |
| Go Android 宿主回归 | 25 项通过 |
| Linux 官方／社区插件集成 | 提供商／工具、同步中间件、异步 Hooks、社区插件全部通过 |
| 插件宿主 race | plugin 与 gui 均通过 |
| Android API 35 x86_64 实际安装运行 | 1 个完整仪器测试，0 failure／error／skipped，套件 112.642 秒 |

JVM 总计 875 项，本轮新增 47 项。插件请求取消 11 项、租约 4 项、操作控制 13 项与 ViewModel 34 项均通过。JVM artifact ID：`14576539604`；核心运行证据：`14578730047`；Android 运行证据：`14577956172`。

Android 的 `plugin-pending-start-cancel.txt` 确认真实延迟授权尚未返回时，双取消保留意图，取得迟到 ID 后同一操作只发一次 Cancel，再读取真实取消终态。`plugin-same-owner-reconnect.txt` 确认同一运行核心重新鉴权后撤销旧插件租约、拒绝旧 key／客户端、新连接 GET 成功；`plugin-session-cancellation.txt` 和 `shutdown.txt` 确认实际停止撤销插件请求并回收 Go／Bun 进程。

已经检查 `native-plugin-login-recovery.png`、`native-plugin-login-end-confirmation.png` 和 `native-plugin-login-canceled.png`：恢复提示、二次确认及实际取消状态可见，无裁切或重叠，沿用暖纸色与文楷屏幕阅读版。既有提供商、三项联通测试、插件工具／中间件／Hooks／SSE、全屏编辑和键盘布局继续通过。

首轮新增 UI 串联测试遗漏了从列表重新进入插件详情，导致查找第二种登录方式失败；[该轮报告](https://github.com/fplj-fplj/magpie-mobile/actions/runs/44618980724) 留存于 Actions，未发布 Release。修复测试导航步骤后重新执行上述最终提交的完整验证；没有删除断言、跳过场景或扩大超时。

签名候选使用 `publish_release=false`，仅归档至 Actions，artifact ID：`14575693872`。已核对实际 APK v2 证书 SHA-256 `dee1d3f485ee17a6184178eaab318d6333fb8b67e7276146a30a03e1f9ee09d6`、CI 的签名验证结果、103,473,404 字节 APK 及包内 ARM64 PIE 核心哈希 `52f9b2599b120894780162934ce0120f174b28b3ee14db7b73f2aee1b93d5ca5`。生产构建的 16KB 对齐及运行载荷检查通过。候选 APK SHA-256 为 `9e348899b03aa9329f2adf6b699622642889cb54721abaa2b42529904a29bad8`；正式发布重新构建，使用正式附件的校验值。

完整验证与签名候选通过后，运行[同提交正式签名与发布](https://github.com/fplj-fplj/magpie-mobile/actions/runs/44622931319)。build 与 publish 均已成功，[正式 Release](https://github.com/fplj-fplj/magpie-mobile/releases/tag/v0.1.52-plugin-session-d5eee979) 已核实为非草稿、非预发布及 Latest，Release ID：`451975280`。

- APK：`magpie-mobile-0.1.52-plugin-session.apk`，103,473,404 字节。
- 正式 APK SHA-256：`07c66d75e43ae09cdba5155eaaaa690462c2e3734b2cd0038a3a39929a87783e`。GitHub 附件 digest 与下载的 SHA-256 文件一致。
- 四项附件为 APK、SHA-256、build-info 与核心构建来源。已下载并核对校验附件和 build-info 的 digest、正式运行 ID、应用提交与固定签名证书，tag 精确指向上述已验收提交。
- 候选只归档到 Actions；仅在完整验证与签名候选均通过后，才以同一提交正式构建、签名和发布。

ARM64 生产 APK 的签名与载荷检查、Android API 35 x86_64 模拟器运行分别记录；本轮不宣称已完成 ARM64 真机执行。

## 后续巡查

聊天和模型目录的完整连接轮次、静默请求取消、存储提交边界与旧按钮回调已在 [0.1.53](bugfixes-0.1.53.md) 单独完成修复及云端验收。运行日志发布与异常退出码审计仍留待后续批次，未混入本轮。
