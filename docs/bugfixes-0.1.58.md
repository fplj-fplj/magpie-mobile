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

## 当前状态

源码待提交到 GitHub Actions。完整 Validation、同提交签名候选及正式发布尚未执行；当前可下载的已验证稳定版本仍为 0.1.57。versionCode 59，备份 appVersion `0.1.58`，核心保持 `0.1.3781-androidhost.2`。

候选只保存在 Actions，完整验证和同提交签名候选通过后才允许正式发布。正式 APK 需要独立核对固定签名、ARM64 载荷、四项 Release 附件、tag、Latest 与下载哈希。ARM64 未经物理设备运行的范围不作扩展声明。
