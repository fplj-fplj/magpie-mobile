# 0.1.59 自动选择模型的原生连通测试

## 问题与修改

官方配置 `models=[] / noModels=false` 表示自动选择可用模型。实际管理列表的 `chosen` 可以为空，但 `models[].on=true` 已标记网关曝光的模型。原生连通测试原先只取 `chosen`，因此会在网关可以正常服务时误报没有已保存模型。

现在从当前已保存提供商的 `models[].on` 生成候选，保留核心顺序并去重，同时要求提供商就绪、未停用、未明确禁用模型。未保存的编辑草稿、手动 ID 和只读发现目录不进入测试候选；`chosen` 不作回退。官方核心已在 `on` 中表达可服务性与自动曝光边界，原生不重复截取 24 项，也不扩大候选集合。开始实际检查前，原有客户端仍实时读取 `/v1/models`，严格核对模型及所属提供商路由。

保持暖纸色、RikkaHub 页面结构、霞鹜文楷屏幕阅读版及官方插件机制。版本为 `0.1.59-provider-auto-models`、versionCode 60，备份 appVersion `0.1.59`；核心保持 `0.1.3781-androidhost.2`。

## 验证设计与当前状态

本地只做源码、资源、补丁和 Python 静态检查，不进行 Gradle、Kotlin／JVM、Go 或 Android 编译及执行。编译及运行验证交给 GitHub Actions；同提交完整 Validation 和签名候选通过后才发布 Release。

新增 9 项 JVM 用例，通过真实管理客户端解析 loopback HTTP 响应，覆盖空 chosen 的自动选择、显式 picks 与真实曝光不一致、未就绪／停用／禁用、目录缺失、重复及非法 ID，以及 23／24／25 项曝光边界、先过滤不可服务模型和超过 24 项的显式选择。

新增 3 项完整原生 Panel 的 Android Compose 用例，使用真实 HTTP 管理响应。正例在模型面板实际切换两个已保存启用模型，搜索排除未启用目录、只读发现结果和未保存手动 ID；两个负例验证明确禁用模型、停用提供商继续无法开始测试。既有 3 项保存／外部返回回归保留。

实际 Go／Bun Android 综合回归通过官方外部编辑 API 写入自动选择配置，再用官方 Refetch 持久化真实上游目录；检查 native editable、chosen、on 和真实网关路由。刷新原生列表并确认空 chosen 的新列表已展示后，原有模型面板操作及三项实际能力检查使用该自动配置。随后写入明确禁用对照，核对路由消失、原生开始按钮禁用，再从最新 revision 恢复显式模型，保留后续聊天及插件回归。官方 API 操作使用测试专用合成凭据和真实管理 Cookie，不记录 Cookie 或一次性链接。

以下保留前几轮脚本失败的实际经过；最终验收采用后面同一提交的完整报告，不据源码或 APK 编译成功提前声称通过。

首轮源码 `80cb5c0f2f50ad1cfffb2f25378fbbeaea11ca84` 的 Validation `44668662199` 中，JVM 645＋435＝1,080 项零失败／错误／跳过，六项完整 Panel 测试全部通过。实际 Go／Bun 综合用例在新增脚本的刷新定位超时：TextButton 的文字子节点没有 clickable 属性，脚本同时要求文字和 clickable 导致找不到节点。已去掉文字子节点不具备的筛选条件，保留明确点击与新列表断言；需要以修正后的完整同提交回归为准，首轮不发布。

第二轮 `5fa5fd08ed908b12e216441a559917694ca5a872` 的 Validation `44670071608` 已通过自动选择的实际三项能力检查，留存结果截图；六项 Panel 测试继续通过。禁用对照的弹窗已在失败截图中正确显示无模型与禁用按钮，但 UiAutomator 用 Compose testTag 查找独立 Dialog 的 Android resource ID，无法识别该窗口，随后清理异常遮盖原始定位超时。脚本改用实际可访问的窗口标题，并将清理失败附加到原错误，保留首次失败证据。仍需全量重跑，不提前发布。

第三轮 `dad3ec333fbd9fe613c63e7c9d8d1f5783ab779b` 的 Validation `44671961094` 已完成禁用对照的真实配置、路由消失及弹窗定位，六项 Panel 测试继续通过。失败断言读取了按钮内文字子节点的 enabled 值，未读取按钮本体；失败截图显示按钮确已禁用，与 Compose 页面断言一致。为实际按钮增加既有风格的 resource testTag，再从按钮本体验证启用状态并点击；不修改可用性条件、视觉或测试结果标准，继续全量云端验收。

## 同提交完整验收

2026-11-20，最终源码 `6b9427058613c9c65cc6edf2c80d452ca735e723` 的 [Validation 44673101779](https://github.com/fplj-fplj/magpie-mobile/actions/runs/44673101779) 和 [签名候选 44673132396](https://github.com/fplj-fplj/magpie-mobile/actions/runs/44673132396) 均成功。已独立读取 GitHub run 状态及源码 SHA，并下载实际报告，校验 artifact digest 后检查 XML、JSON 和截图。

| 验证 | 实际结果 |
| --- | --- |
| JVM core／app | 645＋435＝1,080 项，新增 9 项，零失败／错误／跳过 |
| Go mobile／androidhost | 47／23 个顶层用例，含子用例共 53／25 个通过事件，无失败 |
| 两种实际核心的预设参数转发 | 各 4 组 JSON 证据通过 |
| 两种实际核心的目录 RPC／TLS | 各 8 组握手、凭据及 pin 边界通过 |
| 官方／社区插件及 race | 三类插件、真实 MCP、Hooks、SSE、社区包和两包 race 均通过 |
| Android API 35 x86_64 | 7 项全部通过，零失败／错误／跳过，总计 181.549 秒 |

Android 实际 Go／Bun 综合用例为 148.775 秒，确认官方空 picks、真实目录与原生列表的自动选择状态后，通过非流式、流式和工具调用三项实际检查。明确禁用后真实路由消失，按钮本体的 `enabled=false` 已通过设备断言；从最新 revision 恢复显式选择后，路由恢复且后续插件、聊天、预设请求、Room 隔离与停止清理均通过。`provider-auto-models.txt` 和两张结果／禁用截图齐全，已人工查看。

六项 Panel 用例分别验证保存期间旧回调被拒、已打开草稿的外部返回、旧列表 GET 与最新返回、自动模式中切换已保存模型、明确禁用与停用；后三项新增用例分别耗时 9.804、5.270、3.885 秒。它们不启动假连接上的推理，实际推理由前述 Go／Bun 用例验证。

报告 artifact ID：JVM `14601430153`、Android `14600949071`、核心集成 `14601276860`、签名候选 `14600024623`。候选 APK 为 103,564,840 字节，SHA-256 `df9c659ae57a94ee57caa2045a55a8f910a02b3682f752757ee82bc6129bf6fd`；实际 v2 证书 SHA-256 为固定值 `dee1d3f485ee17a6184178eaab318d6333fb8b67e7276146a30a03e1f9ee09d6`，内嵌 ARM64 PIE 核心 SHA-256 为 `bf755425a0006c1c4e502c77f5d46c6e5bacc0411f96a6437f8b9ba64b1c3abf`。

前三轮候选仅保存在 Actions，完整验收通过后才触发正式发布。ARM64 仅核对固定签名和载荷；实际 Android 执行在 API 35 x86_64 模拟器，不声称 ARM64 真机通过。

## 正式发布

同提交正式流程 [44675009286](https://github.com/fplj-fplj/magpie-mobile/actions/runs/44675009286) 在完整验收后触发并成功，发布为 [v0.1.59-provider-auto-models-6b942705](https://github.com/fplj-fplj/magpie-mobile/releases/tag/v0.1.59-provider-auto-models-6b942705)，Release ID `452271594`。已核实 Latest、非草稿／非预发布、tag 直接指向上述完整验收提交，以及四项附件。

正式签名 artifact `14602131630` 的 ZIP SHA-256 为 `3dd7c025488ac34d469d8dd963939f1f8bbf3feb22683f302902c8890ae3bb79`。下载后读取实际 APK：103,564,840 字节，SHA-256 `209ffb6146d7eb40d9440bb2bd9f1df9e9f594be5b33a6a9e5b39792addad2c0`，与 Release APK asset digest 和下载的 `.sha256` 附件一致。实际 APK v2 证书和内嵌 ARM64 PIE 核心与上述固定哈希一致；build-info 绑定最终源码及正式 run。候选与正式重建分别核验，不假设二者 APK 哈希相同。

四项附件 ID：APK `770458446`、checksum `770458450`、build-info `770458447`、mobile-core-build-info `770458449`。Release 说明以同提交实际测试和验证边界回写后独立 GET 核对，附件保持不变。文档另作提交，不触发重复编译；正式 tag 保持指向通过运行验收的源码。

## 后续巡查

已确认独立显示问题：提供商列表的模型标签仍使用显式 `chosen.size`，自动模式可能显示“已启用 · 0 / 1 模型”，即使实际有一个启用模型。本版已修复测试候选，尚未修复该标签。后续应明确区分已保存启用数量与目录数量，同时更新本轮实际 Go 测试依赖的列表刷新摘要断言，保持布局和模型选择策略。
