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

本地只做 Python 静态契约检查、资源检查与 git diff 检查，没有运行 Gradle、Go、JVM 或 Android 编译／测试。完整云端运行结果和签名候选仍待 GitHub Actions；通过前不发布此版 Release。
