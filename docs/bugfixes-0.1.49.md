# 0.1.49 联通测试取消与会话切换

应用提交：`ea75d207d8e37d4362a084c54c4f2d7e04f9c8c1`。版本 `0.1.49-capability`／versionCode 50，备份文档 appVersion `0.1.49`。保持现有暖纸色、RikkaHub 页面结构、霞鹜文楷屏幕阅读版及官方三类插件机制。

## 修复范围

| 触发场景 | 修复与边界 |
| --- | --- |
| 关闭测试窗口后，静默连接仍等待读取超时 | 界面控制器同步获取请求租约，关闭、离开页面、父协程取消时通知共享取消信号并关闭真实连接；协程正文尚未开始也会注销请求。 |
| 核心切换后旧测试继续发送下一项，或配置来自另一会话 | 会话标识、配置与取消登记在核心锁内原子捕获；替换、重连、暂停或断开会话时立即取消已登记请求。结果仍按界面轮次和 owner 二次检查。 |
| 单项超时与整轮取消混在一起 | 目录和每项检查各有六十秒时限，整轮四分钟。单项检查超时记失败后可以继续；整轮取消、owner 失效或整轮到期立即终止，不产生后续请求。 |
| 模型目录无界读取或慢滴流 | 成功响应按声明和实际字节限制为 4 MiB，总时限六十秒，超限不返回截断目录。缺失／显式 null 元数据保留未知语义，畸形结构使用固定错误且不携带原始响应。 |
| GET 503 Retry-After:0 隐式跟进或错误正文长时间不结束 | 非 2xx 在网络拦截器内保留真实状态码并取消连接，不改写响应头，不读取错误正文；普通与工具 POST 使用一次性请求体，全部禁止自动重定向和重发。 |
| OkHttp 禁用代理后，Java 底层 Socket 仍查询系统 SOCKS 选择器 | 共用传输显式创建 `Socket(Proxy.NO_PROXY)`，同时禁用 HTTP 与 SOCKS 系统代理；目录、普通聊天、流式及能力检查共用这条直接连接路径。 |

三项能力检查仍按普通、流式、工具的顺序运行；路由必须同时精确匹配实时目录中的 `owned_by` 与 `<providerId>/<modelId>`。工具参数只验证，不执行。正文与思考预览、各项接收预算和现有界面保持原有行为。

## 验证状态

本地仅执行源码审查、Python 静态／资源检查与 `git diff --check`；不执行 Gradle、Go、JVM 或 Android 编译与运行测试。

[同提交完整 Validation](https://github.com/fplj-fplj/magpie-mobile/actions/runs/44598215421) 已通过。下载报告核实如下：

| 检查 | 结果 |
| --- | --- |
| 核心 JVM | 535 项，0 failure／error／skipped |
| 应用 JVM | 212 项，0 failure／error／skipped |
| Go mobile 回归 | 35 项通过 |
| Go Android 宿主回归 | 25 项通过 |
| Linux 官方／社区插件集成 | 提供商／工具、同步中间件、异步 Hooks、社区插件全部通过 |
| 插件宿主 race | 通过 |
| Android API 35 x86_64 实际安装运行 | 1 个完整仪器测试，0 failure／error／skipped，套件 86.11 秒 |

本轮新增 61 项 JVM 测试方法，总计 747 项。JVM 报告 artifact ID：`14567880249`；核心运行证据：`14566863684`；Android 运行证据：`14565493920`。

[同提交签名候选构建](https://github.com/fplj-fplj/magpie-mobile/actions/runs/44598243277) 已通过，`publish_release=false`，仅归档到 Actions。签名证书 SHA-256 `dee1d3f485ee17a6184178eaab318d6333fb8b67e7276146a30a03e1f9ee09d6`；核心载荷哈希、PIE 与 16KB 检查通过。候选 artifact ID：`14566143894`。

Android 报告确认普通、流式和工具三项检查均通过；真实 Go＋Bun 提供商登录、工具执行、中间件及异步 Hooks 均通过。`capability-session-cancellation.txt` 确认实际停机取消已登记请求并拒绝过期 owner，`shutdown.txt` 确认核心和 Bun 进程完成清理。既有全屏编辑、保存按钮及键盘布局断言也通过。

完整 Validation 与签名候选全部通过后，才启动[正式签名与发布流程](https://github.com/fplj-fplj/magpie-mobile/actions/runs/44599887923)。build 与 publish 均成功；[正式 Release](https://github.com/fplj-fplj/magpie-mobile/releases/tag/v0.1.49-capability-ea75d207) 已核实为非草稿、非预发布及 Latest，Release ID：`451837638`。

- APK：`magpie-mobile-0.1.49-capability.apk`，103,367,776 字节。
- APK SHA-256：`2efe43707267738dca0379c2604dc15366953069c187c1fcb3c0d8b239789db3`，GitHub 附件 digest 与下载的 SHA-256 文件一致。
- Release 包含 APK、SHA-256、build-info 与核心构建来源四项附件；下载的校验文件及 build-info digest、应用提交、正式运行 ID 与固定签名证书均已核实，tag 精确指向已验证的应用提交。
- 本轮候选未创建 Release；正式版本使用完整验证通过的同一应用提交。

新增回归通过本机合成 HTTP／真实 ServerSocket 验证静默响应头、静默响应体、peer EOF／RST、取消后请求数量、单项与整轮时限、预启动清理、会话切换和精确字节边界，不使用历史真实 API 密钥。Android 实际网关停机另验证租约取消及拒绝过期 owner。

ARM64 生产 APK 签名与载荷检查、Android API 35 x86_64 模拟器运行分别报告；ARM64 真机运行不在当前已验证范围。

## 后续巡查

只读巡查另记录了原生提供商保存与核心重连的时序问题：派发到 IO 后才取得管理客户端，可能跨过会话切换；未知保存状态在 Activity 重建后可能被提交前缓存清除；同 owner 的 BACKOFF 恢复可能丢失新的安全连接请求。上述问题已在 [0.1.50 修复与验收记录](bugfixes-0.1.50.md) 中分别修复并通过完整 Actions 验收。
