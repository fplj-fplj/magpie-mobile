# 0.1.62 聊天分页、上下文隔离与敏感剪贴板

## 修改范围

删除非当前会话时，旧逻辑会重新加载当前聊天的最新 50 条记录，导致用户查看的更早页面被替换并触发滚动到底部；历史列表也会缩回最初 30 项。现在只刷新会话列表，并按当前已加载数量重新读取列表，聊天消息页、分页游标、草稿、失败正文、思考预览和其他当前会话状态保持不变。删除选中会话的 fallback 仍读取 fallback 会话的最新页面。

重命名当前会话也曾把旧消息页重置到最新。现在重命名只刷新历史列表及当前会话的标题与更新时间，保留已加载的历史范围、消息页和草稿。

提供商操作若在协程 body 启动前取消，原先只释放请求租约，不执行 `onFinished`，可能遗留忙碌状态。现让 body 的 `finally` 与完成处理器共享一次性收尾：先释放租约，再且仅一次调用 `onFinished`；正常请求路径的回调时机保持不变。扩充既有两项启动前取消回归，检查已取消 scope 与排队后取消都不启动 body／HTTP、忙碌状态清零、回调时租约已释放，重复取消也不重复回调。

原生显式复制的 Caller Key、生成的密钥、网关接入 API Key 和含凭据 cURL 现在都通过统一的 Android 剪贴板封装写入敏感标记。接入页面在复制时以 NativeCoreSession 的会话锁核对完整连接轮次并读取当前配置中的 Key，旧 UI 回调在连接轮次变化后不再覆盖剪贴板。提供商 API 密钥输入框仅在该字段局部包装 Compose 剪贴板管理器，让系统 Copy/Cut 同样携带标记；读取与粘贴继续委托原管理器。

普通 Base URL 保持未标记。敏感标记是提供给系统剪贴板和输入法的提示，不是加密保证；官方网页编辑器由外部浏览器执行的复制不在本轮原生实现范围内。保持现有暖纸色、RikkaHub 页面结构和霞鹜文楷字体，不调整页面布局或编辑流程。

同步纠正密钥字段与 README 的备份说明：官方加密备份可以由导出选项包含密钥，不能声称密钥绝不进入备份；现有原生 SAF 文件入口仍未接入，schemaVersion 1 元数据格式也不能等同于官方加密完整备份。

## 回归覆盖

- JVM 新增 13 项真实 ViewModel／Room 用例，覆盖旧消息页及草稿保持、104 条记录的下一页游标、加载 30/60 项后的删除分页边界、运行中会话删除被拒时保留当前视图、重命名时保留旧消息页和 60 项历史列表，以及写入提交后读失败的上下文隔离与恢复；扩充了选中会话删除后恢复 fallback 最新页的既有回归。
- Android 新增 1 项 ChatPanel／Room 滚动锚点用例、1 项提供商系统 Copy/Cut/Paste 敏感标记用例、2 项网关剪贴板与旧回调用例；真实核心集成测试增加当前会话成功和旧 epoch 拒绝断言。
- 最终源码的实际 JVM 总数为 1,127 项，Android API 35 x86_64 仪器测试为 23 项，均已通过；完整证据见本文最终验收。以下第四轮的 1,119 项结果对应补入最后 8 项故障恢复回归之前的源码。

## 验证状态

首轮源码 `a3e9a4a089327231e7f8989bcb1885f11333de0b` 的 [Validation 44715579488](https://github.com/fplj-fplj/magpie-mobile/actions/runs/44715579488) 未通过。JVM 报告 `14619896576` 中，31 条会话用例从只加载了 30 条的列表读取索引 35；Android job 编译测试 APK 时报告 `onAllNodesWithText` 缺少导入，未启动模拟器。修正了用例索引和导入，同时调整聊天惰性列表的滚动定位，以及网关剪贴板用例的轮次切换方式。

第二轮源码 `aa7e9472760ceed218833faa544fcfdb484116d6` 的 [Validation 44738211832](https://github.com/fplj-fplj/magpie-mobile/actions/runs/44738211832) 中，regression job 已通过，Android APK 和测试 APK 编译成功，前 9 项聊天与网关仪器测试通过。第 10 项完整应用插件测试进入提供商页面时，ART 对 `ProvidersPanel` 的生成方法报告 `VerifyError`（`copy1 v1<-v277`，类型为 `MeasurePolicy`），导致进程崩溃，完整 23 项未跑完；这不是可忽略的测试环境失败。

现将完整 API 密钥输入框提取为独立、非 inline 的 Composable，敏感剪贴板管理器和局部 provider 一并移入，减少父方法的生成代码及局部变量。字段状态仍由父页面持有，标签、显示／隐藏、输入法、焦点处理和 `provider-api-key` 标记保持原行为。这是针对生成代码验证问题的最小修复，不据此断言工具链根因。

第三轮源码 `622346ce8f44021f6cf2e50c0ea36c283e854e17` 的 [Validation 44776872303](https://github.com/fplj-fplj/magpie-mobile/actions/runs/44776872303) 中，regression job 已通过。实际 JVM XML 报告核对 1,119 项，零失败／错误／跳过；Android 23 项全部执行完毕，22 项通过，未再发生上述 ART 崩溃，实际 `android-errors.txt` 为空。唯一失败是新增剪贴板用例在点击系统 Select all 后等待 Copy：所用 Compose 的平台工具栏会在 Select all 后结束当前 ActionMode，测试误以为同一菜单仍然显示。这不证明生产剪贴板有故障，也不构成该项功能已验收。

现修正测试前提：在真实字段上设置并核对完整选区，再调用字段生产 OnLongClick 打开实际系统菜单，继续由 UiAutomator 点击系统 Copy／Cut／Paste。保留完整剪贴板文本、敏感标记、剪切后清空、两次粘贴恢复及零 POST 断言，超时不变；失败时补录该步骤的截图和 UI hierarchy。JVM／Android 报告 artifact 为 `14650666847`／`14649904096`，下载 ZIP 的 SHA-256 分别为 `93703a25e108bd38405dc142c1c1dfb0b7a16a659558e2d854df6486bb7e7e93`／`d95af303ee05610fdc54253d68f8f69ab0e62a1e5170a6cd1b4ee551e50728ab`，均与 GitHub artifact digest 一致。

第四轮源码 `2439c2c0b883fe8133d1f0721775eaf90e39f337` 的 [Validation 44781437023](https://github.com/fplj-fplj/magpie-mobile/actions/runs/44781437023) 中，regression 与 runtime-emulator 均已完成并成功。实际 JVM XML 为 core 645＋app 474＝1,119 项，Android XML 为 23 项，均零失败／错误／跳过；Android 用例名称与源码清单完全一致，真实系统 Copy／Cut／Paste 用例通过，`android-errors.txt` 为空。已查看提供商页面实际截图，布局、暖纸色与字体保持原样。JVM／Android 报告 artifact 为 `14651908435`／`14651283684`，ZIP SHA-256 分别为 `ccb013b1b677cda5cd1e366d75e494767f013722a04709a222e55a3bc62872da`／`f18a12448ca9df2f9d15b2a027fea627108eedda58063cf017d462912336765f`，下载后均与 GitHub digest 一致。

该轮通过后尚未发布。进一步巡查确认：删除当前会话的事务成功后，若后续列表或消息读取失败，旧界面仍保留已删除的 selected 和草稿；首次发送会被本机存储拒绝，不会因此直接请求上游，但之后重读或发送收尾若 fallback 到另一会话，旧草稿和失败预览可能被保留到新的上下文。

现以数据库事务实际提交为边界：删除当前 ID 后立即撤销可发送身份和消息／预览状态，后续读取失败仍禁止发送；`loadLatest` 只在实际 ID 变化时保存旧会话内存草稿、恢复目标会话自己的草稿并清除旧预览，同 ID 的普通重读继续保留草稿及失败信息。明确区分重命名／新建／删除未提交与已提交后的读失败；新建记录保存待恢复 ID，重读不会重复创建，也不会在有更新记录时误选另一会话。`drafts` 仍只在 Main 访问，事务完成后的必要安全状态在 IO 返回分派前发布，覆盖该返回点被取消的窗口。

最后新增 8 项回归使用真实 Room，单次事务外 SELECT 故障注入不会替代数据库写入；同时通过真实 SQLite trigger 验证回滚不撤销原视图。恢复后的实际 HTTP 断言只包含 fallback 自己的草稿和历史；另覆盖普通刷新、发送收尾的跨 ID fallback、初始化／显式新建的同一已提交 ID 恢复，以及新建／删除在 IO 已提交但 Main 尚未返回时取消。该时点要求最终源码完整重新验收，不能复用第四轮成功结果作为新提交的发布依据；实际最终结果另列于下文。

第四轮完整核心报告 artifact `14651909861` 的 ZIP SHA-256 为 `b58d3f9a91500464d74c3c4f2714d12f3613d7444672fa47f31be56a55806a3b`，已下载并核对 GitHub digest。Go mobile／androidhost 分别通过 47／23 个顶层用例（含子用例 53／25 个通过事件），两种核心的各 4 组参数转发与各 8 组目录 TLS 检查、真实三类插件、MCP／Hooks／SSE、社区包及两包 race 均通过；job 实际日志也确认新增发布门禁 20 项与发布附件 13 项 Python fixture 全部通过。

## 发布防护

修复手动发布仍可绕过同提交 Validation 的缺口。签名构建和 Actions 产物归档继续独立；发布另须显式勾选 `publish_release`，并通过同一源码 SHA 最新可信 Validation attempt 的完整成功检查，要求 `regression` 与 `runtime-emulator` 均真实完成并成功。不复用旧成功覆盖较新的失败、进行中或不同 attempt 结果，发布证据记录具体验证链接。

新 Release 先创建草稿，四项附件的名称、大小及 SHA-256 经 GitHub API 核对后再公开并标记 Latest，避免上传失败留下不完整 Latest。仅允许补传草稿；既有公开 Release 只读比对，不覆盖其附件或移动 tag。门禁与发布逻辑的离线 API fixture 回归也交由 Actions 执行。

本机只进行了源码、差异、ZIP/APK 静态审查及 `git diff --check`；未运行 Gradle、Kotlin/JVM、Go、Android 或 Python 单元测试。最终同提交完整 Validation、签名候选及实际产物均通过核查后，才触发正式发布。

发布版本：`0.1.62-chat-session-clipboard-fixes`，versionCode 63，备份 metadata appVersion `0.1.62`；核心继续使用 `0.1.3781-androidhost.2`。

## 最终同提交完整验收

2026-11-20，最终源码 `90971632a4fe2d282449ae0b20c89a57fbba615f` 的 [Validation 44787218390，attempt 1](https://github.com/fplj-fplj/magpie-mobile/actions/runs/44787218390/attempts/1) 与 [签名候选 44789193460](https://github.com/fplj-fplj/magpie-mobile/actions/runs/44789193460) 均完成并成功。独立核对 run 与 artifact 的完整源码 SHA，实际下载报告并核对 ZIP SHA-256 与 GitHub digest，不使用第四轮旧成功替代最终提交。

| 验证 | 实际结果 |
| --- | --- |
| JVM core／app | 645＋482＝1,127 项，零失败／错误／跳过；ChatViewModel 的 43 项全部执行通过 |
| Go mobile／androidhost | 47／23 个顶层用例，含子用例共 53／25 个通过事件，无失败 |
| 两种实际核心的参数转发 | 各 4 组检查通过 |
| 两种实际核心的目录 RPC／TLS | 各 8 组检查通过 |
| 官方／社区插件及 race | 三类插件、真实 MCP、Hooks、SSE、社区包与两包 race 均通过 |
| Android API 35 x86_64 | 23 项全部通过，零失败／错误／跳过，总计 268.256 秒 |

Android 实际用例名称与源码的 23 项清单一致；`android-errors.txt` 为空。真实 Go/Bun 综合、正常关闭、预设表单与实际请求证据均通过。提供商页面截图已独立查看，新的密钥说明正常显示，原有暖纸色、RikkaHub 布局与文楷保持。ARM64 能力 job 在 push 验证中按既有规则跳过，不能将 x86_64 运行结果表述为 ARM64 真机验收。

报告 artifact：JVM `14653534719`、Android `14654428007`、核心 `14653959738`。ZIP SHA-256 分别为 `8d202d511575d0050f456f47d89578a12c24a699e399e434e351f0324d7dea1e`、`280d6cb1c45dd0b2a482bb0d88f910da6b8b94a33c55b30ba99a17ea32e02700`、`a5a5d80fc8c28e3e9129e21726c1945aa96d9fc4e87d8c619fa9d8502adeeae1`，均与 GitHub digest 一致。

签名候选 artifact `14656011512` 的 ZIP SHA-256 为 `c224795b3a9ab3e20f1e653ea3ef3c2c11cd00958f253385313896b1746cf70d`。候选 APK 为 103,617,204 字节，SHA-256 `ad1a2daa22cedcb1e765376f6259b2acc83580d27406bcc918d9205d945bbb83`；实际读取 APK v2 证书，固定 SHA-256 为 `dee1d3f485ee17a6184178eaab318d6333fb8b67e7276146a30a03e1f9ee09d6`。Manifest 核对 applicationId、versionCode 63、版本名、minSdk 29、targetSdk 35、非 debuggable 及禁用系统备份；dex 含 `90971632` 标记。包内仅有 arm64-v8a，Go/Bun 均为 AArch64 ELF PIE，内嵌 Go 核心哈希 `bf755425a0006c1c4e502c77f5d46c6e5bacc0411f96a6437f8b9ba64b1c3abf` 与 CI evidence 一致。

## 正式发布

候选核对完成后，才触发 [正式流程 44791102942](https://github.com/fplj-fplj/magpie-mobile/actions/runs/44791102942)。build 与 publish 均成功；发布门禁实际核对本提交最新可信 Validation `44787218390/attempts/1`，构建信息记录该具体验证链接。Release 为 [v0.1.62-chat-session-clipboard-fixes-90971632](https://github.com/fplj-fplj/magpie-mobile/releases/tag/v0.1.62-chat-session-clipboard-fixes-90971632)，ID `453034886`。独立 GET 确认 Latest、非草稿／非预发布，tag 直接指向上述完整验收源码。

正式签名 artifact `14656866161` 的 ZIP SHA-256 为 `7c225c934ec7ba2d4d688b0e4f838fe28e702c2733d23f7aa54f55f4c619df6e`。已分别下载正式 artifact 和 Release 的实际 APK，逐字节相同：103,617,204 字节、SHA-256 `714414357bd77e97c3035bbd1afcac0c4dd06f8ed0d169b5b0e0d4c08e8184f6`；与实际 checksum 附件和 GitHub asset digest 一致。正式 APK 的实际 v2 证书、ARM64 PIE 载荷、核心哈希和源码标记另行核对通过，没有把候选 APK 哈希当作正式哈希。

四项附件 ID：APK `772853838`、checksum `772854047`、build-info `772854083`、mobile-core-build-info `772854135`。名称、大小及实际下载内容的 SHA-256 均与 API digest 一致；核心构建信息与正式 artifact 内容相同，build-info 包含最终源码、正式 run、固定证书与实际 Validation attempt。下载后重新 GET Release 与 Latest，附件 ID／大小／digest 未变。Release 说明补录最终验收结果后独立 GET 读回完全一致，四项附件仍未改变；文档单独提交，不移动正式 tag。

### Release 对象恢复（2026-11-23）

清理历史 Release 时曾误删本版本的 Release 对象，Git tag `v0.1.62-chat-session-clipboard-fixes-90971632` 与源码提交 `90971632a4fe2d282449ae0b20c89a57fbba615f` 均保留。发现后从该原始提交重新运行正式构建 [45220410022](https://github.com/fplj-fplj/magpie-mobile/actions/runs/45220410022)，build 和 publish 均成功；Release 对象已按相同 tag 恢复。恢复构建仍使用 versionCode 63、原包名和固定签名证书，并重新核对同提交通过的 Validation `44787218390/attempts/1`。

恢复后的公开 APK 为 103,617,204 bytes，SHA-256 `af2e84bf4ad8dea641f35f3bac82491baa382108c26155f4403ad2ef42483d8a`。该值已由实际下载文件、`.sha256` 附件和 GitHub asset digest 三方核对一致；APK 实际 manifest 为 `cc.fplj.magpiemobile`、`0.1.62-chat-session-clipboard-fixes`、versionCode 63，证书 SHA-256 为 `dee1d3f485ee17a6184178eaab318d6333fb8b67e7276146a30a03e1f9ee09d6`。重新运行构建生成的 APK 与原 Release APK 字节不同，旧哈希 `714414357bd77e97c3035bbd1afcac0c4dd06f8ed0d169b5b0e0d4c08e8184f6` 仅对应首次发布附件，不代表当前已恢复 Release 的文件。当前四个恢复附件 ID 为 APK `783755060`、checksum `783755379`、build-info `783755426`、mobile-core-build-info `783755505`；Release 页面仍只保留 0.1.62 与 0.1.64，Latest 已重新设为 0.1.64。

## 后续独立开发

官方网页备份导出只返回 Android 应用私有路径的缺口另行开发，本版没有包含该补丁。新的文件桥接须保留官方加密格式，并实际验证外部浏览器下载到可取出的文件、系统文件选择器选回该文件、错误口令不恢复及正确口令恢复；Go/Node 夹具或直接 HTTP 导入不能替代真实浏览器往返。原生 SAF 占位入口仍未接入，不宣称已完成。
