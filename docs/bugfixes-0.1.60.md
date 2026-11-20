# 0.1.60 提供商模型摘要

## 问题与修改

官方自动选择模型配置使用 `models=[] / noModels=false`，管理列表通过 `models[].on=true` 表示实际曝光模型，但提供商行此前把 `chosen.size` 当作已启用数量，可能显示“已启用 0 / 1 模型”。本轮改为显示“启用 E / 目录 D”：E 来自就绪、未停用且未明确禁用提供商的 `models[].on` 唯一模型，D 为完整管理目录数量；状态顺序为已停用、模型已禁用、未就绪、已启用。

编辑页的模型选择属于尚未提交的草稿，文案改为“当前草稿”“将启用”和“保存后生效”。连通测试候选仍由已保存模型投影生成，未保存手动模型和只读发现目录不会进入测试。

版本为 `0.1.60-provider-model-summary`、versionCode 61，备份 appVersion `0.1.60`；核心保持 `0.1.3781-androidhost.2`。继续使用暖纸色、RikkaHub 页面结构和霞鹜文楷屏幕阅读版。

## 验证设计与当前状态

本地仅进行源码、资源、补丁、ZIP/APK 和空白检查，不运行 Gradle、Kotlin/JVM、Go 或 Android 编译及运行测试。所有编译和测试交由 GitHub Actions；在同一源码提交的完整 Validation 和签名候选成功、实际报告及候选产物核对后才触发正式发布。

共享 JVM helper 保留上一版 9 项真实管理客户端 HTTP 用例，并强化 implicit-all、ready/off/noModels 七种组合、显式选择与 `models[].on` 不一致、重复及 23/24/25 边界。真实 ProviderPanel 由 6 项扩展到 9 项，覆盖默认 1/1、implicit 2/3、明确禁用和停用 0/1、24/25、未就绪及草稿不写入列表。测试使用 loopback 管理响应和合成凭据，不启动假连接上的推理。

首轮源码 `7e13f27e51b4d6edd81f605974e9f8d2696ee0a3` 的 Validation `44679289929` 完成 Android 编译和 164.538 秒的真实 Go/Bun 综合用例，但九项 Panel 用例均在新增摘要的可见性断言失败，未发布。XML 证明匹配到节点却未通过显示性检查；现场没有语义树或 bounds，不能据此把裁切或零尺寸认定为唯一根因。实际 Dashboard 提供滚动容器，隔离 fixture 原先缺少该容器；修正使 fixture 与实际宿主一致，并使用未合并树中的具体 Text 滚动后检查可见性，保留 exact 文案和行祖先限定。生产布局不变，最终结果以修正后同提交的完整重跑为准。

实际 Go/Bun 回归使用官方 API 同时保存唯一短名称标记和 `models=[] / noModels=false`，再真实 Refetch 上游目录。原生列表必须同时出现该标记、“启用 1 / 目录 1”和可操作行，避免旧显式配置恰好同为 1/1 而误判刷新。禁用对照要求 0/1、实际路由消失和开始按钮禁用；最后使用最新 editable revision 同时恢复原名称及显式模型，再核对路由、列表和后续聊天功能。保留原始失败并把清理异常附加其后。

## 同提交完整验收

2026-11-20，最终源码 `7681edbbc4a2a09c37560463592a86c26f240749` 的 [Validation 44680940234](https://github.com/fplj-fplj/magpie-mobile/actions/runs/44680940234) 与 [签名候选 44681003878](https://github.com/fplj-fplj/magpie-mobile/actions/runs/44681003878) 均成功。已独立读取 run 状态和源码 SHA，下载实际报告并核对 artifact digest。

| 验证 | 实际结果 |
| --- | --- |
| JVM core／app | 645＋435＝1,080 项，零失败／错误／跳过；本轮强化现有 9 项 helper 用例 |
| Go mobile／androidhost | 47／23 个顶层用例，含子用例共 53／25 个通过事件，无失败 |
| 两种实际核心的参数转发 | 各 4 组 JSON 检查通过 |
| 两种实际核心的目录 RPC／TLS | 各 8 组检查通过 |
| 官方／社区插件及 race | 三类插件、真实 MCP、Hooks、SSE、社区包与两包 race 均通过 |
| Android API 35 x86_64 | 10 项全部通过，零失败／错误／跳过，总计 177.677 秒 |

Android 实际 Go/Bun 综合用例耗时 138.272 秒，九项 Panel 用例全部通过。其中新增未就绪、24/25 和显式选择与 `on` 不一致用例分别耗时 3.148、3.214、4.174 秒。implicit-all 用例还在关闭测试与编辑页面后再次检查行摘要，证明发现目录与未保存模型未写入列表。已人工查看实际提供商列表的新摘要截图、三项能力结果截图和禁用按钮截图，暖纸色与文楷排版保持原样。

报告 artifact ID：JVM `14604945599`、Android `14604053460`、核心集成 `14604826771`、签名候选 `14604466431`。候选 APK 为 103,564,812 字节，SHA-256 `acff1db98ae88326c0a176339aaf36d3b43fd381769a75d4533b41175376b1a9`；实际 APK v2 证书 SHA-256 为固定值 `dee1d3f485ee17a6184178eaab318d6333fb8b67e7276146a30a03e1f9ee09d6`，内嵌 ARM64 PIE 核心 SHA-256 为 `bf755425a0006c1c4e502c77f5d46c6e5bacc0411f96a6437f8b9ba64b1c3abf`。

ARM64 只核对签名及载荷结构，实际 Android 执行在 API 35 x86_64 模拟器完成，不声称 ARM64 真机通过。

## 正式发布

上述同提交完整验证与签名候选核对后，触发 [正式流程 44682572381](https://github.com/fplj-fplj/magpie-mobile/actions/runs/44682572381)，build 和 publish 均成功。发布为 [v0.1.60-provider-model-summary-7681edbb](https://github.com/fplj-fplj/magpie-mobile/releases/tag/v0.1.60-provider-model-summary-7681edbb)，Release ID `452314433`。独立 GET 确认其为 Latest、非草稿／非预发布，tag 直接指向上述完整验收提交，四项附件齐全。

正式签名 artifact `14604673264` 的 ZIP SHA-256 为 `73c84df24d24a48a529df42c15de42df27a4838bc9e21fffe6027164ef46d3d7`。已分别下载该 artifact 以及 Release 的实际 APK 并逐字节对比一致：103,564,812 字节，SHA-256 `3facdc3e97cfb2741226424e3f1bb673fb22b112f6baddd1314ef4e6ced8ebac`；与 Release asset digest 和下载的 checksum 附件一致。实际 APK v2 证书、内嵌 ARM64 PIE 核心均与上述固定哈希一致；build-info 绑定最终源码与正式 run。候选和正式包分别核验，不假设重建后的 APK 哈希相同。

四项附件 ID：APK `770626048`、checksum `770626051`、build-info `770626049`、mobile-core-build-info `770626054`。Release 说明补录实际验收结果后再独立 GET 核对，附件不变；文档单独提交，不触发重复编译，正式 tag 保持指向通过运行验收的源码。

## 后续巡查

另行只读复核提供商选择策略、目录发现失效保护、保存与未知结果恢复、会话切换、外部返回、缓存代次及发布载荷检查，未确认额外具备完整用户触发路径的新缺陷。该结果仅代表本次源码审计范围，不替代上述实际云端验收，也不扩张为所有平台无缺陷的结论。
