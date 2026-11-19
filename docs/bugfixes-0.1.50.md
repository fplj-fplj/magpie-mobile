# 0.1.50 提供商会话、保存核对与网关恢复

应用提交：`e0e1ddd5a5b5c7cc66b834cae42dcf7759b528f7`。版本 `0.1.50-provider-session`／versionCode 51，备份文档 appVersion `0.1.50`。保持现有暖纸色、RikkaHub 页面结构、霞鹜文楷屏幕阅读版及官方三类插件机制。

## 修复范围

| 触发场景 | 修复与边界 |
| --- | --- |
| 保存排队到 IO 后切换网关，旧草稿取得新会话的管理客户端 | 页面在派发前捕获预期 owner 的管理租约。客户端固定凭据、配置身份和取消信号；每次真实 HTTP 前及返回前再次核验，不会在工作线程重新取当前会话。 |
| 停止或替换会话后，提供商读取、保存和模型发现仍等待静默响应 | 会话生命周期同步撤销已登记租约，取消信号主动关闭实际连接；取消保持 CancellationException，不能误报为目录失败或成功。 |
| 未知保存结果在旋转后被保存前的健康缓存或旧 GET 清除 | 写入前原子登记屏障、使旧读取失效，再保存非秘密恢复标记。只有实际同步 HTTP 退出后释放屏障；恢复核对必须等待该 owner 的写入退出，再发起新 GET。取消观察者不会提前释放写入或取消 VM 共享列表任务。 |
| 迟到的旧 owner 操作切回缓存、取消新会话的 GET | 缓存按 GatewayRuntime 单调服务代次拒绝旧 owner；旧入口不能更改缓存 owner、代次或在途请求。读取返回也核验 owner 与读取代次。 |
| 同 owner 在 BACKOFF 暂停时旧鉴权仍在途，新 RUNNING 被 connecting 标志丢弃 | 连接协调器用会话身份、连接 epoch 和 attempt 控制发布；只保留最新待连接请求，统一 drain 串行鉴权。旧结果与收尾不影响新请求；重复 RUNNING 不重复核对／发放密钥，ERROR 仍须显式重试。 |
| 管理 HTTP 在取消后阻塞或错误状态隐式跟进 | 共用直接 Socket 路径，禁代理、重定向、鉴权重试与连接重试；JSON POST 一次性。网络拦截器在 HTTP 跟进前截获非 2xx，仅保留状态及白名单模型错误头，不读取错误正文。成功响应按声明和实际字节限为 1 MiB，并限制整次调用时间。 |

请求取消不能证明服务端没有提交；未知结果继续只读核对，不自动重放保存。正常完成仅注销租约，不取消已成功的控制信号。管理 Cookie 与网关 Bearer 继续分离；测试只使用合成凭据。

## 验证状态

本地只执行源码审查、Python 静态／资源检查与 `git diff --check`；不运行 Gradle、Go、JVM 或 Android 编译与测试。

新增回归覆盖真实 TCP 静默响应头／正文、取消后 peer EOF／RST、取消能力协商后零 POST、旧 owner 排队操作、旋转中的共享读取与写入核对、新会话 GET 不受旧入口干扰、最新待重连请求以及多线程执行器下鉴权仍串行。Android 仪器测试追加真实管理读取与停机后管理租约失效断言。

[同提交完整 Validation](https://github.com/fplj-fplj/magpie-mobile/actions/runs/44605806283) 与[同提交签名候选](https://github.com/fplj-fplj/magpie-mobile/actions/runs/44605877818) 均通过。已下载实际报告核对：

| 检查 | 结果 |
| --- | --- |
| 核心 JVM | 555 项，0 failure／error／skipped |
| 应用 JVM | 249 项，0 failure／error／skipped |
| Go mobile 回归 | 35 项通过 |
| Go Android 宿主回归 | 25 项通过 |
| Linux 官方／社区插件集成 | 提供商／工具、同步中间件、异步 Hooks、社区插件全部通过 |
| 插件宿主 race | plugin 与 gui 均通过 |
| Android API 35 x86_64 实际安装运行 | 1 个完整仪器测试，0 failure／error／skipped，套件 101.952 秒 |

新增 57 项 JVM 测试方法，总计 804 项。JVM 报告 artifact ID：`14569842883`；核心运行证据：`14570667233`；Android 运行证据：`14569688352`。排队取消测试修正真实 IO 监听器完成后返回测试调度器的推进方式；最终报告保留零 HTTP、零旧结果回调和租约恰好释放一次的断言。

Android 的 `provider-session-cancellation.txt` 确认停机前真实管理 GET 成功，实际停机后管理租约、该客户端与旧 owner 均拒绝使用；既有联通测试取消、三项模型检查、Go＋Bun 登录／工具／中间件／Hooks、全屏编辑及键盘布局回归也通过。`shutdown.txt` 确认核心和 Bun 进程完成回收。

签名候选使用 `publish_release=false`，仅归档到 Actions，artifact ID：`14568284557`。已下载候选核实固定证书 SHA-256 `dee1d3f485ee17a6184178eaab318d6333fb8b67e7276146a30a03e1f9ee09d6`、APK 103,408,944 字节及核心载荷哈希；生产构建的 PIE、16KB 对齐和载荷检查通过。

完整 Validation 与签名候选成功后，才启动[正式签名与发布](https://github.com/fplj-fplj/magpie-mobile/actions/runs/44608204296)。build 与 publish 均成功，[正式 Release](https://github.com/fplj-fplj/magpie-mobile/releases/tag/v0.1.50-provider-session-e0e1ddd5) 已核实为非草稿、非预发布及 Latest，Release ID：`451889922`。

- APK：`magpie-mobile-0.1.50-provider-session.apk`，103,408,944 字节。
- APK SHA-256：`2255f957b6a572a83dacd9d88333a45d4b4e8bae037b46e38c1375debba03d92`；GitHub 附件 digest 与下载的 SHA-256 文件一致。
- Release 包含 APK、SHA-256、build-info 与核心构建来源四项附件；校验附件与 build-info 的 digest、应用提交、正式运行 ID 和固定签名证书均已核实，tag 精确指向同一个已验证的应用提交。
- 候选均仅保留在 Actions；正式发布以同一已验收提交重新构建并签名，其 APK 校验值以上述正式附件为准。

ARM64 生产 APK 的签名与载荷检查、Android API 35 x86_64 模拟器运行分别记录；ARM64 真机执行不在当前验证范围。

## 后续巡查

只读巡查另确认两项界面状态问题：同 Service owner 重连后，列表缓存与旧在途 GET 尚未按连接 epoch 区分；未知保存核对成功后，仍打开的编辑器可能保留提交前 revision，导致下一次保存冲突。核心的版本检查和新建唯一 ID 防线仍有效，不会自动重放写入。本轮先交付已经完整验收的修复，下一轮将分别增加受控时序回归，再修复缓存 epoch 和核对后的编辑状态。
