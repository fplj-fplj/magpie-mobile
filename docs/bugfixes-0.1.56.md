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

验证源码为 `428c0c9b30d81774f0a186e58b574ffcca66eef7`。

- 完整 Validation 的 regression 与 runtime-emulator 均通过。JVM 共 1,002 项，core 607 项，app 395 项，新增 16 项，失败、错误和跳过均为 0。GatewayRuntimeTest 19 项，GatewayServiceLifecycleTest 9 项，GatewayServiceReporterTest 8 项。[Validation](https://github.com/fplj-fplj/magpie-mobile/actions/runs/44650145650)
- Go mobile 47 个顶层测试、53 个通过事件，androidhost 23 个顶层测试、25 个通过事件。两种核心各通过 8 组真实管理 RPC/TLS 验证。真实官方与社区插件集成和 plugin/gui race 回归均通过。
- Android API 35 x86_64 综合测试通过，XML 耗时 108.362 秒，无失败、错误或跳过。`gateway-stop-replay-cleanup.txt` 记录三次实际通知重发与 STOP revision 前进、原租约和诊断保留、Activity 无 Service 停止、受控迟到 shell 进程退出后按原凭证解锁。真实 Go 和 Bun 关闭后无残留进程或监听端口。
- 签名候选通过，发布任务按候选设置跳过。实际候选 APK 为 103,512,764 字节，SHA-256 `a4f694b6cea25388d8bd4fea502353b2ebbb66d8e00b630187cdbce349b7d71a`。下载后核对了 APK v2 固定签名和 ARM64 PIE 核心。[签名候选](https://github.com/fplj-fplj/magpie-mobile/actions/runs/44650174408)

| 已下载并核对摘要的证据 | Artifact ID |
| --- | ---: |
| JVM 报告 | 14590177359 |
| Android 运行与截图 | 14590712529 |
| Go、RPC/TLS、插件及 race | 14590263804 |
| 签名候选 | 14591196302 |

## 正式发布核验

- [正式构建与发布](https://github.com/fplj-fplj/magpie-mobile/actions/runs/44652080580)通过，仍为已验证的源码 `428c0c9b30d81774f0a186e58b574ffcca66eef7`。后续提交仅记录验收，不修改应用源码。
- [Release v0.1.56-gateway-cleanup-lease-428c0c9b](https://github.com/fplj-fplj/magpie-mobile/releases/tag/v0.1.56-gateway-cleanup-lease-428c0c9b)，ID `452149163`，为 Latest、非草稿、非预发布。正式 tag 精确指向上述已验证提交，附件恰为 APK、APK checksum、build-info 和 core build-info 四项。
- 正式 APK `magpie-mobile-0.1.56-gateway-cleanup-lease.apk` 为 103,512,764 字节，SHA-256 `d46cd8fd1b353fc13ef674efe725540ba6d7c1d656f5a96184db8d9095e2f1f0`。已下载并核对正式 artifact `14591557418` 的 SHA-256，实际 APK 字节与 Release asset digest 和 checksum 一致。正式重建哈希不同于候选，以上均为正式文件的核验结果。
- 实际 APK v2 签名证书 SHA-256 为固定 `dee1d3f485ee17a6184178eaab318d6333fb8b67e7276146a30a03e1f9ee09d6`，包内 ARM64 PIE 核心 SHA-256 为 `bf755425a0006c1c4e502c77f5d46c6e5bacc0411f96a6437f8b9ba64b1c3abf`。ARM64 只检查签名／载荷，不宣称真机运行。
- Release 说明已补入实际同提交验证结果，并独立 GET 读回确认正文完全一致，四项附件仍保留。
