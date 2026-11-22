# 0.1.63：官方加密备份的浏览器文件桥接

## 已确认的缺口

官方 `POST /api/backup/export` 原先把加密备份保存在核心进程的 `Downloads` 或 HOME，再返回 JSON 私有路径。在 Android 宿主上，该路径属于 App 私有目录；外部浏览器收到“Saved to”不代表用户拿到了可选择、可迁移的文件。原生设置中的 SAF 导入导出仍是占位入口，现有 schemaVersion 1 元数据格式也不能替代官方加密完整备份。

本版只解决官方网页的文件交付路径，保持现有暖纸色、RikkaHub 页面结构、霞鹜文楷屏幕版和官方插件机制。原生 SAF 不在本轮实现范围内。

## 候选实现

- 导出请求新增 `sink: "app"`，仍走官方 Collect、所选 Library／Memory／SignIns、Seal 流程。成功返回 `application/octet-stream` 与固定附件名 `magpie.magpie-backup`，设置 no-store、nosniff 和 Content-Length，直接写密文字节，不创建私有备份文件或明文临时文件。
- 空 sink 和 `downloads` 保持原桌面落盘、JSON path 及打开文件夹行为；未知 sink 拒绝，不把调用方输入当作文件路径。原有 cookie、Origin、Sec-Fetch-Site 与路径约束继续生效；失败返回 JSON，不伪装成可下载文件。
- `mobilepolicy.AndroidHost` 编译常量只为 AndroidHost 浏览器注入 boot flag，普通桌面与旧 mobile 标签均不启用网页下载分支，不按 User-Agent 猜测平台。
- 官方网页使用 same-origin、禁止重定向、no-store 的 fetch，校验状态、Content-Type、固定 Content-Disposition 及非空 Blob 后才下载。按钮防双击；成功清除口令，保留同一份密文的 Download 重试，不重复提交导出。对象 URL 在 60 秒后释放。
- 自动下载前检查文档可见性、表单连接状态和所有祖先的 hidden 状态；页面切走后完成仅保留显式下载入口。自动与手动保存异常都显示在真实 syncBar 的 alert 节点，保留恢复入口。

应用版本 `0.1.63-browser-backup-bridge`、versionCode 64，metadata appVersion `0.1.63`；修改过的核心单独标记为 `0.1.3781-androidhost.3`。上游固定源码仍为 `b52c711142a662fe18fedf5a80cb1e4aa94bce62`，历史发布记录与 tag 不改写。

## 自动回归与静态检查

新增 `androidhost_backup_test.go` 的 8 个 `TestAndroidHostBackup…` 顶层用例，覆盖：

1. 有／无提供商密钥的 HTTP 加密导出、官方 Open 和实际官方 import 路由恢复，插件元数据保留且没有私有备份文件。
2. Library、Memory、SignIns 的默认、选中与排除行为，密文不含合成秘密明文。
3. 桌面 Downloads 与 HOME fallback、默认 sink、文件不覆盖与打开目录行为。
4. cookie、Origin、Sec-Fetch-Site、方法与路径边界。
5. 非法 JSON、空口令、未知 sink、配置损坏时不会返回附件。
6. 不读取／修改调用方指定的无关私有路径。
7. boot flag 仅对 AndroidHost 的浏览器启用。
8. Node 执行实际嵌入的 syncfiles.js 和真实 syncBar；覆盖 Blob 下载、响应／URL／append／click 故障、同密文重试、防双击、口令清理、对象 URL 释放、隐藏／断连页面与桌面原行为。

Python patch fixture 同时核对新增补丁与平台常量的打包约束。Go 中的 Node 回归依赖 Node ≥18，Validation 的 regression job 显式配置 Node 22。该工作仍须与全部 JVM、Go、插件、RPC／TLS、race 和 Android 既有回归一起在 GitHub Actions 执行。

本机只做源码、AST、补丁哈希与差异静态检查，不运行 Gradle、Kotlin/JVM、Go、Android、Node 或 Python 测试。

源码 `103ae1fd6bbe13902a40b272867c82095b581912` 的 Validation `44794021147` 已读取实际归档报告：JVM core 645／app 482，共 1,127 项通过，零失败、错误或跳过；Go mobile 47／AndroidHost 31 个顶层用例（含子用例 53／50 个通过事件），包含全部新增 8 项备份用例与实际 Node 网页模块回归。两种标签的聊天参数转发、RPC／TLS 边界、真实官方／社区插件集成与 race 也通过。

同次 Android 报告为 23 项中 1 项失败，错误日志为空：`OfficialPluginsInstrumentedTest` 仍断言旧核心 `androidhost.2`，实际安装载荷已为 `androidhost.3`，导致该项后续插件集成未执行。现已精确更新本次安装测试的预期版本；旧版本解析 fixture 保留。必须在修订提交上重跑完整 Validation，不能把该次 Android 任务计为通过。

修订 `bea57ef70e46f287931c670b5e949e8bab54a29c` 的 Validation `44796417575` 中，regression 任务通过；Android 实际 Go／Bun 插件运行证据为 PASS，23 项中 22 项通过、1 项失败，无跳过，AndroidRuntime 错误日志为空。失败发生在 `ProviderPanelInstrumentedTest` 的初始列表等待：语义读取经 Espresso idle 进入绘制时出现 `performMeasureAndLayout called during measure layout`，尚未打开密钥编辑器或执行复制。该测试和提供商页面从已验证的 0.1.62 源码未变，前一轮同项完整通过；现有栈不足以确定底层触发器，不能归因为备份或剪贴板缺陷。

本次仅收敛测试同步：启用行的有界等待不再嵌套 Compose 的 frame-advancing `waitUntil` 与已自行同步的语义读取，改用墙钟超时；先确认唯一启用行，再断言只发起一次 GET。仍不捕获、重试或忽略布局异常，产品 UI 与断言强度不变。此调整须在新源码提交的完整 Actions 上验证，未将一次重跑成功视为底层触发器已修复。

源码 `7133e4e10cea39e740efbc71cd496dc749394207` 的 Validation `44799890338` 中，JVM 实际报告仍为 1,127 项通过、零失败／错误／跳过；regression 任务成功。Android 23 项中 22 项通过、1 项失败，无跳过，实际 Go／Bun 插件运行证据为 PASS，AndroidRuntime 错误日志为空。这次失败是手动模型保存门禁用例首次等待保存按钮超时，上一轮的布局重入未出现；不能据此认定底层布局问题已经修复。

已定位本次测试同步缺口：编辑器目录检查先设置 `discoveryLoading`，再通过 `LaunchedEffect` 的虚拟时钟等待 1,000ms；保存按钮在目录检查完成前禁用。墙钟轮询不会持续推进仅有未来 delay 的 Compose 测试时钟。现仅在该用例编辑手动模型前，先用可推进虚拟时钟的有界等待确认初次目录 POST，再确认保存按钮启用，与其他手动草稿用例的既有顺序一致。后续请求挂起、旧回调拒绝、草稿保留、失败后显式重试及写请求计数断言均保留，不改变产品逻辑，也不延长超时或吞掉异常。

最终代码提交 `26639e1ed74ca60a2d4cd46b7374affeaa628dc8` 的 Validation `44802493336` 完整通过：JVM core 645／app 482，共 1,127 项；Android API 35 x86_64 的 23 项真实仪器用例；Go mobile／AndroidHost、参数转发、RPC／TLS、官方与社区插件、Hooks／MCP／SSE 及 race 均通过。报告已实际下载核验，失败、错误和跳过均为零。

## 真实 Android 浏览器验收

独立分支 `browser-backup-probe` 的环境探针只调查 API35 x86_64 官方 `google_apis`／`google_apis_playstore` 镜像：真实 HTTP handler、独立浏览器前台、无凭据 loopback 页面、截图、UI hierarchy、浏览器与 DocumentsUI 版本。它不安装产品 App，不执行备份，因此不能代替下面的往返验收。

独立验收分支使用真实 Chrome、公共 Downloads 与 DocumentsUI 完成：

1. 先准备浏览器首次启动与默认应用选择，再从 App 的“提供商／更多 → 浏览器编辑”真实按钮进入；由浏览器消费生产的一次性入口，不在测试中抓 cookie 或注入凭据。
2. 官方网页 Export 后，公共 Downloads 出现真实、稳定的新文件，记录 basename、size 和 SHA-256；不能只按 toast、网页按钮或 HTTP body 判定保存成功。
3. 使用绑定同一核心连接的生产 RPC 将配置 A 改为 B。
4. 官方网页 Import 唤起真实系统文件选择器，实际选择刚下载的文件；不得注入 File、伪造 ActivityResult 或直接调用 import API 替代选择。
5. 错口令时 RPC 仍为 B，正确口令后 RPC 恢复为 A；下载文件 size／hash 保持，未创建 App 私有备份档案。

入口票据只有 30 秒；首次欢迎／默认应用弹窗不得消耗本机票据后再以注入 cookie 补救。API35 作用域存储下使用测试 shell 只读观察公共文件，不给正式 App 增加全盘存储权限。非标准备份扩展名能否通过浏览器 input 及系统选择器选回，也须实测。

最终真实往返为 Actions run `44974211901`、harness 提交 `8e211f24b0483cf9cf8ac584b510c1b62421cfad`，结果为 `status=PASS`、`phase=passed`：`wrongPhraseStatePreserved=true`、`correctPhraseStateRestored=true`、`privateArchiveChecks=3`，公共文件保持 1,124 bytes，SHA-256 为 `5eb497d76db3a62fcb91972fe8f75338565d78c34281b56ec607e3871229098a`。最终 Sync 页面脱敏 XML 与截图均已核验，验收过程未使用 JS 注入、合成文件、直接 import RPC 或私有路径绕行。

## 发布状态

代码提交 `26639e1` 的签名候选构建 `44974804808` 已通过固定证书、ARM64-only 载荷、嵌入核心、版本字段和运行时探测检查。正式发布仍要求文档收尾提交自身的完整 Validation、签名构建和发布门禁全部通过；任何环境缺失、文件未落盘、无法被选择或未恢复配置都必须明确失败，不 skip 后宣称可用。
