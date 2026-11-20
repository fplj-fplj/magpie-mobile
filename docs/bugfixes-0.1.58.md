# 0.1.58 提供商保存与外部返回刷新

## 已确认的问题与修改

手动模型 ID 输入框原先在保存期间仍可编辑。保存点击已经固定请求 A，随后输入 A+B 会留在页面，保存成功关闭编辑后却丢失。现在保存点击同步设置 busy，输入框禁用，旧 onValueChange 回调也检查同一状态，防止重绘前的回调修改草稿。已知失败保留 A 并恢复编辑，用户修改后才能再次显式保存。

外部编辑返回原先只在 externalRevision 变化时尝试刷新；原生编辑器打开或读取繁忙时跳过，关闭编辑、请求结束后没有补读。现在按完整 session、epoch 和返回 revision 记录读取尝试与健康确认。等待期间的新事件合并，只有实际开始读取才消耗尝试；关闭编辑或当前请求退出后补读最新 revision。外部变化不覆盖原生草稿，页面提示关闭编辑后刷新。

外部 revision 未确认前，暂停基于旧列表的打开、添加、保存与连通测试。列表读取保留既有 operationSequence、缓存 generation、写入屏障与恢复核对；新 revision 开始前等待缓存失效，避免 force 读取加入旧请求。旧 revision 的结果不能恢复最新列表的可编辑状态。失败只记录尝试，等待用户明确重试，既不循环 GET，也不重放 POST。普通刷新和保存后的恢复读取可以满足同一刷新待办。

内部连接入口统一提供观察状态、即时 session 与管理请求 lease。默认仍委托 NativeCoreSession；测试注入同样使用生产 lease、管理客户端和真实 loopback HTTP。公开 ProvidersPanel 参数、暖纸色、RikkaHub 布局、霞鹜文楷屏幕阅读版及插件机制保持原样。

## 验证范围

本地只做源码、资源、补丁和 Python 静态检查。不在本地运行 Gradle、Kotlin／JVM、Go 或 Android 编译及执行测试。

新增 11 项 JVM 测试直接使用生产刷新状态、列表缓存、管理客户端与恢复逻辑，覆盖 open／busy 等待、连续 revision 合并、补读期间的新变化、旧缓存 generation、失败与文件异常不自旋、session／epoch 切换，以及真实 POST 503 后等待实际写入 worker 退出才补读，始终只有一笔 POST。

新增 3 项 Android Compose 页面测试使用完整 ProvidersPanel 与真实 HTTP。保存测试在一次主线程任务中先点击真实保存回调，再调用事先捕获的 SetText 回调，检查输入框禁用及文本未变；随后验证已知失败保留输入、显式修改重试及实际请求内容。外部返回测试按生产顺序先等待缓存失效，再发布 revision，覆盖打开草稿与被门闩阻塞的旧列表 GET。

既有实际 Go／Bun 综合 Android 测试增加真实 Activity 所属 ExternalEditorViewModel 和 Dashboard ON_RESUME 验证：获取真实一次性外部链接但不记录或启动浏览器；通过真实管理接口修改核心，再受控切换 STARTED→RESUMED。检查原生草稿保留、待刷新提示和保存门禁，关闭编辑后自动更新列表，重新打开取得新名称。此项验证实际返回处理链，不声称进行了浏览器 UI 导航。留存页面截图与文本证据。

## 首轮云端验证与脚本加固

首轮源码 `c7a009e34305cfa0282c1e7168cdebe4a5080a25` 已推送。签名候选 `44661923117` 成功并核对了实际 APK 固定证书及 ARM64 核心；Validation `44661883861` 的 JVM 1,071 项通过（645 core＋426 app，新增 11 项，零失败／错误／跳过）。Android 新增 3 项 Compose 测试与真实外部返回处理均通过，但综合测试随后在既有预设表单保存步骤超时，因此未发布。

首轮 Android 日志显示温度从 abc 改为 0 后，脚本仅过 9ms 就点击保存；两张截图显示错误 supportingText 移除后按钮已上移，旧点击位置落在按钮下方，属于高置信的布局同步问题。脚本现等待输入回显、错误消失、可点击区域至少 40dp 且连续三次轮询位置稳定，重新查询按钮后只点击一次；保存成功断言保持原样。失败时先记录表单截图及最小 VM 状态，再清理对话框。生产预设逻辑未改变。

## 同提交完整验收

2026-11-20，脚本加固后源码 `db6798933b46c279fe50414443709eb0875835bf` 的 [Validation 44663473898](https://github.com/fplj-fplj/magpie-mobile/actions/runs/44663473898) 与 [签名候选 44663516548](https://github.com/fplj-fplj/magpie-mobile/actions/runs/44663516548) 均已成功。逐项下载实际报告并核对 artifact digest，没有用“APK 编译成功”替代运行验证。

| 验证 | 实际结果 |
| --- | --- |
| JVM core／app | 645＋426＝1,071 项，新增 11 项，零失败／错误／跳过 |
| Go mobile／androidhost | 47／23 个顶层用例，含子用例共 53／25 个通过事件，无失败 |
| 两种实际核心的预设参数转发 | 各 4 组 JSON 证据通过，系统消息、零值采样、输出上限及路由未回归 |
| 实际 RPC／TLS | 两种核心各 8 组握手、指纹与凭据边界通过 |
| 官方／社区插件及 race | 三类插件、真实 MCP、Hooks、SSE、社区包与插件／GUI race 均通过 |
| Android API 35 x86_64 | 4 项全部通过，零失败／错误／跳过，总计 161.095 秒 |

Android 综合用例耗时 148.359 秒，保留实际 Go／Bun、网关与插件、真实聊天、预设参数及 Room 隔离、停止清理等验证；新增外部返回证据 `provider-external-return.txt` 及三张截图均齐全。3 项 Compose 页面测试分别耗时 6.169、3.106、1.416 秒，核验保存期间旧回调被拒、HTTP 400 后明确重试、打开草稿时合并刷新，以及被阻塞旧 GET 完成后仅补读最新列表。预设表单加固后完成实际保存并经 Go 发出正确请求。

下载报告 artifact：JVM `14596367643`，Android `14596343380`，核心集成 `14596971762`，签名候选 `14597255590`。候选 APK 为 103,561,832 字节，SHA-256 `45dabd6c2f0f04f79b23f50b298626f95c3f115a300ffd4a26cb32118b692291`。实际 APK v2 证书 SHA-256 为固定值 `dee1d3f485ee17a6184178eaab318d6333fb8b67e7276146a30a03e1f9ee09d6`；内嵌 ARM64 PIE 核心 SHA-256 为 `bf755425a0006c1c4e502c77f5d46c6e5bacc0411f96a6437f8b9ba64b1c3abf`。

## 正式发布

同提交正式流程 [44664738056](https://github.com/fplj-fplj/magpie-mobile/actions/runs/44664738056) 在完整验收后触发且成功；已发布 [v0.1.58-provider-refresh-db679893](https://github.com/fplj-fplj/magpie-mobile/releases/tag/v0.1.58-provider-refresh-db679893)，Release ID `452216797`。已核实 Latest、非草稿／非预发布、tag 直接指向完整验收源码，以及四项附件。

正式签名 artifact `14596808570` 的 ZIP SHA-256 为 `2729f454f2354cec993367b20b5a693438005aec132a461cf4b77079692dbd8f`。下载后读取实际 APK：103,561,832 字节，SHA-256 `47e70afc012d9bf007d5d4394a9be7c4c561dfa29978c4370900e49e91aec2d3`，与 Release APK asset digest 和 `.sha256` 附件一致。实际 v2 证书及内嵌 ARM64 PIE 核心哈希与上述固定值一致；build-info 绑定源码 `db6798933b46c279fe50414443709eb0875835bf` 和正式 run `44664738056`。

正式四项附件 ID：APK `770220131`，checksum `770220133`，build-info `770220136`，mobile-core-build-info `770220132`。Release 说明回写同提交实际测试与验证边界后再独立 GET 核对，保持四项附件不变。首轮失败候选与第二轮已验证候选均只保存在 Actions；只有完整验收后的正式流程创建 Release，正式 APK 哈希独立核对。versionCode 59，备份 appVersion `0.1.58`，核心保持 `0.1.3781-androidhost.2`。

ARM64 仅验证固定签名、PIE 和载荷哈希；实际 Android 运行验证为 API 35 x86_64 模拟器，不声称 ARM64 真机执行通过。外部返回采用真实生产处理链和受控生命周期切换，不声称已操作浏览器 UI。

## 后续巡查

持续巡查仍在进行。下一项源码审计发现：官方兼容配置 `models=[] / noModels=false` 可以通过 `models[].on=true` 暴露自动选择的模型，但原生连通测试只取显式 chosen，会误判为空。该问题尚未做设备复现或纳入本版修复；后续应从已保存且实际启用的集合取候选，继续保留实时路由核验，明确禁用的配置仍不能测试。它与本轮的保存输入保护和外部刷新待办分开验收。
