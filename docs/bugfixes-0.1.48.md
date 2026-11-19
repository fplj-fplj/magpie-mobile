# 0.1.48 流式接收与取消收尾

应用提交：`a0b7db91c481e5be550f07d28c8b44f56497e745`。版本 `0.1.48-streaming`／versionCode 49，备份文档 appVersion `0.1.48`。保持现有暖纸色、RikkaHub 页面结构、霞鹜文楷屏幕阅读版及官方三类插件机制。

## 修复范围

| 触发场景 | 修复与边界 |
| --- | --- |
| 上游发送无换行长行或持续无效事件 | 字节扫描器在读取时限制单行 4 MiB、累计 64 MiB；注释、事件标记与分隔符同样计入预算。CR、LF、CRLF 与跨读取 UTF-8 均支持。 |
| 正文或思考无限增长 | 默认正文、思考各 256 Ki UTF-16 单元，合计 512 Ki；完整事件先校验两通道及合计预算，再追加与派发。超限失败只携带此前已接受的有界增量，不保存截断成功。 |
| 心跳、角色事件或慢滴流绕过读取超时 | 每轮默认十分钟单调时钟截止，覆盖响应头、静默读取和忙流；允许的 HTTP 405／501 降级共享原截止时间与文本预算。普通请求自身也有总时限。 |
| ViewModel 清理时静默连接仍占用线程 | 协程取消通过独立 watcher 通知共享 control，使用 OkHttp Call.cancel 关闭连接；读取与失败收尾保留取消语义。正常旋转保留 ViewModel，不取消或重复请求。 |
| 取消后待定轮次仍 RUNNING 或被记成功 | begin 后保留轮次身份以便 fail；在进入 complete 存储事务前检查取消，失败仅回滚当前待定 user。已进入并成功提交的事务保持成功。 |
| HTTP 重试或关闭响应体时继续等待 | 禁用自动重试、重定向与鉴权重试，请求体标为一次性；真实 503／408 Retry-After:0 和 307 不重发。错误状态、DONE 和失败路径先取消连接，避免关闭响应体时继续排空连接。 |
| 普通降级拒绝纯思考响应 | 有效 reasoning_content／reasoning 可配空正文；思考独立保存，不进入后续正文上下文。形状错误使用固定安全诊断。 |
| 连通测试接收超量流或界面把资源错误显示成 HTTP 200 | 流式能力测试采用单行 256 KiB、总字节 4 MiB、正文与思考合计 16 Ki UTF-16 单元、六十秒预算；超限与超时显示明确原因，其他检查可继续。 |

接收预算独立于模型的 token 配置。超限、超时、取消或普通网络失败均不触发自动重发；只有真实 HTTP 405／501 允许原有的一次普通请求降级。

聊天传输固定为 OkHttp `4.12.0`（传递依赖 Okio `3.6.0`），保留配置的连接／读取超时，明确禁用系统代理。许可证按对应上游 tag 随 APK 打包于 `assets/licenses/apache-okhttp.txt` 与 `apache-okio.txt`。

## 验证与发布状态

本地仅执行源码审查、Python 静态／资源检查与 `git diff --check`，不执行 Gradle、Go、JVM 或 Android 编译与运行测试。

[同提交完整 Validation](https://github.com/fplj-fplj/magpie-mobile/actions/runs/44587116765) 已通过，下载报告核实如下：

| 检查 | 结果 |
| --- | --- |
| 核心 JVM | 488 项，0 failure／error／skipped |
| 应用 JVM | 198 项，0 failure／error／skipped |
| Go mobile 回归 | 35 项通过 |
| Go Android 宿主回归 | 25 项通过 |
| Linux 官方／社区插件集成 | 提供商／工具、同步中间件、异步 Hooks、社区插件全部通过 |
| 插件宿主 race | 通过 |
| Android API 35 x86_64 实际安装运行 | 1 个完整仪器测试，0 failure／error／skipped，套件 90.045 秒 |

本轮新增 65 项 JVM 测试方法，总计 686 项。JVM 报告 artifact ID：`14560964161`；核心运行证据：`14562780898`；Android 运行证据：`14560869840`。

Android 报告确认普通、流式与工具三项能力检查通过，真实 Go＋Bun 插件登录、工具执行、中间件及异步 Hooks 通过，停止后核心和 Bun 进程完成清理。全屏编辑器坐标 `Rect(0, 0 - 1080, 2400)`；保存按钮 `Rect(550, 2180 - 1038, 2306)`；键盘顶部 y=1517，发送按钮底边 y=1462，连续三次稳定采样通过。

[同提交签名候选构建](https://github.com/fplj-fplj/magpie-mobile/actions/runs/44587228840) 已通过，`publish_release=false`，只归档到 Actions。固定签名证书、核心载荷哈希、PIE 与 16KB 载荷检查通过。签名候选 artifact ID：`14562140443`。

完整 Validation 与签名候选全部通过后，才启动[正式签名与发布流程](https://github.com/fplj-fplj/magpie-mobile/actions/runs/44589075581)。build 与 publish 均成功；[正式 Release](https://github.com/fplj-fplj/magpie-mobile/releases/tag/v0.1.48-streaming-a0b7db91) 已核实为非草稿、非预发布及 Latest，Release ID：`451759980`。

- APK：`magpie-mobile-0.1.48-streaming.apk`，103,337,652 字节。
- APK SHA-256：`d48520b37e77ec069b19b165eac70b48c112202ed96bbaf6108141cc34638a88`，GitHub 附件 digest 与下载的 SHA-256 文件一致。
- Release 包含 APK、SHA-256、build-info 与核心构建来源四项附件；下载的 build-info digest、应用提交、正式运行 ID 与固定签名证书均已核实。
- 本轮候选未创建 Release，正式版本使用完整验证通过的同一应用提交。

新增回归使用合成本机 HTTP／ServerSocket、内存 Room 和受控 latch，不使用历史真实 API 密钥或公共模型推理。覆盖解析预算原子性、精确字节与 UTF-16 边界、静默取消、首个停止原因、注销竞争、跨降级截止、持久化提交边界与纯思考上下文隔离。

Android API 35 x86_64 模拟器运行与 ARM64 生产 APK 的签名／载荷静态检查分别报告；ARM64 真机运行仍不属于已验证结果。


## 后续巡查

本轮聊天页 Job → socket 取消桥不等于所有能力测试均具备即时取消。只读巡查发现，提供商连通测试关闭时只取消 Job 并隔离结果，目录／普通／工具 HTTP 与 SSE 的局部 control 尚未接入 UI 的共享取消；核心 owner 变化目前只过滤迟到结果，未主动停止旧任务及后续检查。下一轮将补齐独立的 capability 取消与会话切换链路，并用真实静默 socket 验证。当前已通过的路由身份校验、资源限制和聊天取消收尾仍按本页范围成立。
