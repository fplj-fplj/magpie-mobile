# 0.1.56：网关停止清理凭证

日期：2026-11-20 UTC。versionName `0.1.56-gateway-cleanup-lease`，versionCode 57，备份 appVersion `0.1.56`。固定上游和核心保持不变。

## 确认的问题与修复

旧 Service 在 onDestroy 返回后，其控制器仍可能等待迟到的 ProcessBuilder 或执行进程清理。此时重发已捕获的通知 STOP PendingIntent 会新建空 Service；旧实现的 onCreate 无条件替换 owner，空控制器又立即完成清理，提前允许重启并丢失原控制器的最终诊断。Activity 同时把 stopService(false) 当作清理证明，也可能单独解除仍在回收的控制器屏障。

Runtime 现在把实际运行的 ServiceLease 与普通命令接收实例分开。只有已接受的显式启动、没有已有租约且不在停止中的状态才能原子认领；仅处理 STOP、空、未知或重投命令的 Service 不认领 Runtime／Native 会话。onCreate 仍及时提升前台，失败先留在该实例，只有真正获准启动后才发布失败诊断。

requestStop 有租约时只建立屏障；没有租约时在同一锁内完成停止并撤销尚未认领的启动。Activity 忽略 stopService 的布尔返回值。onDestroy 仅替自己的租约停止控制器并异步等待；只有对象身份相同的清理凭证才能完成，不能通过 owner 编号伪造。FAILED 但租约仍未清理也不能启动，完成后的旧 owner 不再能发布状态或重新触发效果。最终安全日志、实际退出码与 FAILED 原因保持。

## 验证设计与边界

本地只做 Python 静态、资源、补丁哈希检查与 git diff --check，不本地编译或运行 Gradle、JVM、Go、Android。完整运行结果以同提交 GitHub Actions 证据为准。

- Runtime 测试覆盖重复 STOP、真实租约与同编号伪造对象、旧凭证重放、清理中拒绝认领、FAILED 未清理拒绝重启、无 owner 停止撤销排队启动，以及 stop／claim 的并发线性化。
- Robolectric 真实 Service 生命周期函数覆盖只处理 STOP／无效命令的新实例，验证创建、处理与销毁不能改变未清理运行的所有者、Native 状态或诊断。MainActivity 测试直接调用实际停止回调，验证 Android 确实返回 false 的路径仍保留屏障。前台失败结果由测试设置本地字段模拟，不冒充系统真实异常。
- Android API 35 综合测试从实际运行的网关通知捕获停止 PendingIntent。正常停止后，用受控控制器把迟到 launch 固定在未完成边界，再重发三次真实通知操作。每次等待意图 revision 持久化证明命令已执行、等待实际 Service 销毁，然后检查原租约和清理屏障。通过 Activity 实际停止回调再验证无 Service 路径。释放 gate 后真的创建并清理 Android shell Process，控制器结束后才使用原凭证解除屏障。
- 该 Android 回归是“真实新 Service／通知重发 + 受控旧控制器迟到启动”，不声称人为暂停了原实际 Go Service 的进程回收；实际 Go／Bun 正常启停与插件集成继续由既有综合路径验证。ARM64 正式 APK只核验签名与载荷，不宣称真机执行。

## 后续范围

本批只处理停止清理所有权。ChatProfile 请求参数与失败草稿保留、提供商外部返回刷新与保存中手动模型输入等已确认问题仍在后续范围。

## 云端验收

待同提交完整 Validation、签名候选与正式发布核验完成后补充真实结果；候选不提前上传 Release。
