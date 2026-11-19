# 0.1.54：实时诊断与实际退出码

日期：2026-11-19。versionName `0.1.54-gateway-diagnostics`，versionCode 55，备份 appVersion `0.1.54`。核心保持 `0.1.3781-androidhost.2`。

## 已确认的问题与修复

Controller 原本持续消费 stdout/stderr，但只在状态转换时发布 tail，健康运行期间的日志不会更新诊断页；Runtime 清理又以空 STOPPED 覆盖最终输出。现在由既有 worker 根据安全 tail 的 revision，使用单调时钟默认每 500ms 合并发布，无变化不发。生命周期及最终状态不受日志节流影响，不增加计时线程或无限事件队列。

生产 ServiceReporter 按 phase 与 restartCount 区分生命周期转换，单纯 tail 更新只发布 Runtime。ready 指标、Native connect/suspend、审计、Logcat、通知和 stopSelf 不随日志刷新重复执行。report 与 stop/retire 使用短锁串行化，controller.stop/await 在该锁外调用，避免反向锁等待。

stopping 屏障继续阻止提前重启，允许同 owner 合并最终安全 tail/code，不能改变停止提示或恢复生命周期。实际 awaitStopped 成功后，cleanup 保留诊断；FAILED 原因不被 STOPPED 覆盖。新显式启动清除旧 tail/code，旧 owner 的回调不影响新会话。

Controller 新增 nullable exitCode 字段，直接保存实际观察到的 Process.exitValue。Service 将它传给审计器，不再固定写 -1，也不从 message 解析。新报告及读取的旧报告均将历史 -1 归一为未知，缺失/null 兼容；原退出码位置显示“未知”。137 只表示实际数值，不等同于证明 OOM 或某个信号。

## 流式脱敏边界

- stdout、stderr 各有最多 8192 字符的未完成行 carry；完整行或真实 EOF 末行先经过固定全掩码，再进入共享 8192 字符 tail。
- 密钥跨 reader chunk 或发布窗口时仍不可见；无换行进度在 newline/EOF 前不会显示。超长行整行丢弃到 newline/EOF，只加入固定省略标记，随后恢复读取。
- 每次 cleanup 封闭旧 drain sink，防止无法及时关闭的旧读取线程向下一 attempt 写入。两路及 EOF 边界不会拼成同一敏感字段。
- Runtime 是公开状态流的独立安全出口；审计写入和旧报告读取同样先完整脱敏再限长，不自动重写旧文件或损坏 JSON。
- 新严格方法与原有非流式 redact 共存；识别已承诺的 Bearer、Authorization、sk、password/token/api_key/k 等凭据语法，不声称识别任意无标签秘密。旧 gw/sk 末四位提示在诊断读取时再次全掩码。长引号值使用无回溯重复分支。

## 回归与设备验收

新增测试源码覆盖真实 PipedInputStream 在 RUNNING 后输出、无变化不重复发布、假单调时钟节流、跨窗口凭据、并发输出与超长行、EOF、立即最终状态、旧 reader 隔离、实际 23/137/未知退出码与新 attempt 清空。

App 回归直接使用生产 GatewayServiceReporter 和真实 PostMortemAuditor，核验副作用计数、停止竞态、日志与通知脱敏、真实整数/null/旧 -1/缺失字段及损坏文件读取。诊断 UI 不改布局；审计卡片仍在进入页面时读取，不宣称打开页面后自动重读审计文件。

Android 仪器源码新增两条路径：

1. 实际 Bun Hook 打印受控 marker，经 Go stderr、Controller、Runtime 进入诊断文字；保持核心 RUNNING、restartCount、连接轮次、网关 key 与启动指标不变。正常停止后继续保留最终输出及已观察退出码。
2. 实际 `/system/bin/sh` 受控退出 23 与 137，经 Controller → 生产 ServiceReporter → 审计 JSON，再重入现有诊断页检查退出码。此 fixture 与已停止的实际 Go 核心隔离，不向 App 或核心发送信号，不声称是 ARM64 真机或 OOM 测试。

本地只做 Python 静态契约检查、资源检查与 git diff 检查，没有运行 Gradle、Go、JVM 或 Android 编译／测试。

## 已取得的同提交云端证据

验证源码：`d2db8c6964d39c7cad2c669caa5397ee40aa1a35`。后续仅更新说明的提交不改变已验收 App 源码或正式 tag 的目标。

- [完整 Validation](https://github.com/fplj-fplj/magpie-mobile/actions/runs/44637208011)：regression 与 runtime-emulator 均成功。
- [签名候选，publish_release=false](https://github.com/fplj-fplj/magpie-mobile/actions/runs/44637206873)：成功；候选仅归档到 Actions。
- 已下载报告并核对各 artifact 的 GitHub SHA-256。JVM XML：core 599、app 371，共 970 项，本轮新增 48 项，失败／错误／跳过均为 0。
- 相关 JVM suite：Controller 24、日志缓冲／节流 10、严格脱敏 7、审计 9、Runtime 12、生产 ServiceReporter 8，全部通过；包含长引号值、真实管道和线程互斥场景。
- Go JSON：mobile 35、androidhost 25 项通过；真实官方／社区 provider、tools、middleware、async hooks、PowerContext、npm／GitHub 更新和 race 均通过。
- Android API 35 x86_64：1 项综合测试，XML 总耗时 138.288 秒，0 失败／错误／跳过。测试直接执行 APK 的 Go 与 Bun，保留上一批聊天／目录／提供商／插件重连和实际取消回归。
- 新证据 `gateway-live-diagnostics.txt`：RUNNING 中实际 Bun console 经 Go stderr 自动进入 Runtime 与诊断文字，restartCount、provider epoch、网关 key 和启动指标不变，synthetic 凭据及其末段均不可见。
- 新证据 `gateway-final-diagnostics.txt`：正常停止、清理完成后保留 marker，观察到实际退出码 143。该数值仅作实测记录，不推断退出原因。
- 新证据 `gateway-exit-diagnostics.txt`：受控 Android shell 实际退出 23／137，经 Controller → 生产 Reporter → 真实审计 JSON；重新进入诊断页确实显示退出码 137。
- 已人工检查 `gateway-live-diagnostics.png` 与 `gateway-exit-diagnostics.png`：marker、固定掩码与实际退出码字形可见，原暖纸色、文楷排版和卡片布局保持。
- 候选 APK 实际 v2 证书 SHA-256：`dee1d3f485ee17a6184178eaab318d6333fb8b67e7276146a30a03e1f9ee09d6`；包内 ARM64 PIE 核心 SHA-256：`52f9b2599b120894780162934ce0120f174b28b3ee14db7b73f2aee1b93d5ca5`。ARM64 是签名与载荷校验，没有宣称 ARM64 真机执行。

第一版 `14bb6c1d` 的签名候选成功，但 Validation 未通过，因此未发布：JVM 在下载既有 okhttp 依赖时失败，尚未执行测试；Android 已通过核心日志／退出码断言，但最后查找审计文字时沿错误方向滚动。最终提交只修正仪器测试的滚动定位，同提交完整重跑已通过，没有改依赖版本或跳过检查。

## 后续巡查范围

本批之外的只读审查已确认：模型目录 discovery 尚未遵循 serverPin；重复 STOP 新建空 Service 可能抢走旧进程清理 owner；ChatProfile 尚未完整接入发送参数，预设损坏读取与保存失败也存在数据保留问题。这些没有混入本批，也没有声称已经修复。下一批优先处理目录 TLS 指纹和清理所有权，继续沿用 Actions-only 验证与可用后发布的策略。


## 正式发布核验

- [正式构建与发布](https://github.com/fplj-fplj/magpie-mobile/actions/runs/44638961265)成功，仍是上述验证源码 `d2db8c6964d39c7cad2c669caa5397ee40aa1a35`。
- [Release `v0.1.54-gateway-diagnostics-d2db8c69`](https://github.com/fplj-fplj/magpie-mobile/releases/tag/v0.1.54-gateway-diagnostics-d2db8c69)，ID `452068548`，已核实为 Latest、非草稿、非预发布；正式 tag 精确指向已验证提交。
- 附件恰为 4 项：APK、APK `.sha256`、`build-info.txt`、`mobile-core-build-info.txt`。正式构建信息中的 commit、run ID 与固定证书一致。
- 正式 APK：`magpie-mobile-0.1.54-gateway-diagnostics.apk`，103,503,960 字节，SHA-256 `70e8b10ed6901650414bdbd7a86b36aa9295c2d0e8fb64146124484285c4afa7`。
- 已下载正式构建的签名 artifact，并对其中实际 APK 检查 v2 证书、ARM64 PIE 核心和 APK 哈希；与 Release APK asset digest、附件 checksum 完全一致。不是拿候选哈希替代正式文件。
- 签名候选 APK SHA-256 为 `007d651df42e102ac05f6f7b1c24a7fded9218efb314f1b2566fe8f0abb4529a`，仅作为候选记录；安装文件以正式 APK 为准。
- Release 说明已根据已下载报告补充实际验证结果并读回确认；没有保留构建前“待验证”的过期描述。

已下载并核对 GitHub artifact digest 的证据：

| 证据 | Artifact ID | 所属 run |
| --- | ---: | ---: |
| JVM 报告 | 14584738448 | 44637208011 |
| Android 运行与截图 | 14585411973 | 44637208011 |
| Go／插件／race | 14585840202 | 44637208011 |
| 签名候选 | 14583874406 | 44637206873 |
| 正式签名 APK 与载荷证据 | 14585726390 | 44638961265 |
