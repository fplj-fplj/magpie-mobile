# 0.1.62 候选：聊天分页与敏感剪贴板

## 修改范围

删除非当前会话时，旧逻辑会重新加载当前聊天的最新 50 条记录，导致用户查看的更早页面被替换并触发滚动到底部；历史列表也会缩回最初 30 项。现在只刷新会话列表，并按当前已加载数量重新读取列表，聊天消息页、分页游标、草稿、失败正文、思考预览和其他当前会话状态保持不变。删除选中会话的 fallback 仍读取 fallback 会话的最新页面。

重命名当前会话也曾把旧消息页重置到最新。现在重命名只刷新历史列表及当前会话的标题与更新时间，保留已加载的历史范围、消息页和草稿。

提供商操作若在协程 body 启动前取消，原先只释放请求租约，不执行 `onFinished`，可能遗留忙碌状态。现让 body 的 `finally` 与完成处理器共享一次性收尾：先释放租约，再且仅一次调用 `onFinished`；正常请求路径的回调时机保持不变。扩充既有两项启动前取消回归，检查已取消 scope 与排队后取消都不启动 body／HTTP、忙碌状态清零、回调时租约已释放，重复取消也不重复回调。

原生显式复制的 Caller Key、生成的密钥、网关接入 API Key 和含凭据 cURL 现在都通过统一的 Android 剪贴板封装写入敏感标记。接入页面在复制时以 NativeCoreSession 的会话锁核对完整连接轮次并读取当前配置中的 Key，旧 UI 回调在连接轮次变化后不再覆盖剪贴板。提供商 API 密钥输入框仅在该字段局部包装 Compose 剪贴板管理器，让系统 Copy/Cut 同样携带标记；读取与粘贴继续委托原管理器。

普通 Base URL 保持未标记。敏感标记是提供给系统剪贴板和输入法的提示，不是加密保证；官方网页编辑器由外部浏览器执行的复制不在本轮原生实现范围内。保持现有暖纸色、RikkaHub 页面结构和霞鹜文楷字体，不调整页面布局或编辑流程。

## 回归覆盖

- JVM 新增 5 项真实 ViewModel／Room 用例，覆盖旧消息页及草稿保持、104 条记录的下一页游标、加载 30/60 项后的删除分页边界、运行中会话删除被拒时保留当前视图，以及重命名时保留旧消息页和 60 项历史列表；扩充了选中会话删除后恢复 fallback 最新页的既有回归。
- Android 新增 1 项 ChatPanel／Room 滚动锚点用例、1 项提供商系统 Copy/Cut/Paste 敏感标记用例、2 项网关剪贴板与旧回调用例；真实核心集成测试增加当前会话成功和旧 epoch 拒绝断言。
- 预期 JVM 总数为 1,119 项，Android API 35 x86_64 仪器测试为 23 项。最终数量与结果以 GitHub Actions 同提交报告为准。

## 验证状态

首轮源码 `a3e9a4a089327231e7f8989bcb1885f11333de0b` 的 [Validation 44715579488](https://github.com/fplj-fplj/magpie-mobile/actions/runs/44715579488) 未通过。JVM 报告 `14619896576` 中，31 条会话用例从只加载了 30 条的列表读取索引 35；Android job 编译测试 APK 时报告 `onAllNodesWithText` 缺少导入，未启动模拟器。修正了用例索引和导入，同时调整聊天惰性列表的滚动定位，以及网关剪贴板用例的轮次切换方式。

第二轮源码 `aa7e9472760ceed218833faa544fcfdb484116d6` 的 [Validation 44738211832](https://github.com/fplj-fplj/magpie-mobile/actions/runs/44738211832) 中，regression job 已通过，Android APK 和测试 APK 编译成功，前 9 项聊天与网关仪器测试通过。第 10 项完整应用插件测试进入提供商页面时，ART 对 `ProvidersPanel` 的生成方法报告 `VerifyError`（`copy1 v1<-v277`，类型为 `MeasurePolicy`），导致进程崩溃，完整 23 项未跑完；这不是可忽略的测试环境失败。

现将完整 API 密钥输入框提取为独立、非 inline 的 Composable，敏感剪贴板管理器和局部 provider 一并移入，减少父方法的生成代码及局部变量。字段状态仍由父页面持有，标签、显示／隐藏、输入法、焦点处理和 `provider-api-key` 标记保持原行为。这是针对生成代码验证问题的最小修复，不据此断言工具链根因。

第三轮源码 `622346ce8f44021f6cf2e50c0ea36c283e854e17` 的 [Validation 44776872303](https://github.com/fplj-fplj/magpie-mobile/actions/runs/44776872303) 中，regression job 已通过。实际 JVM XML 报告核对 1,119 项，零失败／错误／跳过；Android 23 项全部执行完毕，22 项通过，未再发生上述 ART 崩溃，实际 `android-errors.txt` 为空。唯一失败是新增剪贴板用例在点击系统 Select all 后等待 Copy：所用 Compose 的平台工具栏会在 Select all 后结束当前 ActionMode，测试误以为同一菜单仍然显示。这不证明生产剪贴板有故障，也不构成该项功能已验收。

现修正测试前提：在真实字段上设置并核对完整选区，再调用字段生产 OnLongClick 打开实际系统菜单，继续由 UiAutomator 点击系统 Copy／Cut／Paste。保留完整剪贴板文本、敏感标记、剪切后清空、两次粘贴恢复及零 POST 断言，超时不变；失败时补录该步骤的截图和 UI hierarchy。JVM／Android 报告 artifact 为 `14650666847`／`14649904096`，下载 ZIP 的 SHA-256 分别为 `93703a25e108bd38405dc142c1c1dfb0b7a16a659558e2d854df6486bb7e7e93`／`d95af303ee05610fdc54253d68f8f69ab0e62a1e5170a6cd1b4ee551e50728ab`，均与 GitHub artifact digest 一致。

## 发布防护

修复手动发布仍可绕过同提交 Validation 的缺口。签名构建和 Actions 产物归档继续独立；发布另须显式勾选 `publish_release`，并通过同一源码 SHA 最新可信 Validation attempt 的完整成功检查，要求 `regression` 与 `runtime-emulator` 均真实完成并成功。不复用旧成功覆盖较新的失败、进行中或不同 attempt 结果，发布证据记录具体验证链接。

新 Release 先创建草稿，四项附件的名称、大小及 SHA-256 经 GitHub API 核对后再公开并标记 Latest，避免上传失败留下不完整 Latest。仅允许补传草稿；既有公开 Release 只读比对，不覆盖其附件或移动 tag。门禁与发布逻辑的离线 API fixture 回归也交由 Actions 执行。

本机只进行了源码与差异静态审查及 `git diff --check`；未运行 Gradle、Kotlin/JVM、Go 或 Android 编译／测试。修复后的候选仍须通过同提交完整 Validation 和签名产物核查，才可发布 Release。

版本候选：`0.1.62-chat-session-clipboard-fixes`，versionCode 63，备份 metadata appVersion `0.1.62`；核心继续使用 `0.1.3781-androidhost.2`。
