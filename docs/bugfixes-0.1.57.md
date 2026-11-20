# 0.1.57 聊天预设生效与安全保存

## 已确认的问题与修改

既有预设页展示了 systemPrompt、temperature、topP、maxTokens，但聊天发送没有传入这些参数，流式与普通降级请求均遗漏。现在发送点击同步取得不可变配置快照，两条请求路径共用；系统提示词仅加在出站 messages 的首部，不写入历史、Room 或 JSONL。预设提示词计入既有 65,536 字符出站容量限制，超限保持草稿且不发 HTTP。

未修改的 `ChatProfile.DEFAULT` 按完整值相等判断，保留原来的模型默认请求；页面显示“使用模型默认参数”，不再展示未发送的内建英文提示词。自定义预设以及修改过但仍使用 `default` ID 的预设正常传参。`modelAlias` 仍作为元数据保存，不覆盖已确认的模型目录选择，不绕过会话校验。

原存储在解析失败时退回默认列表，随后保存可能覆盖损坏源；原地写入也可能截断文件。新实现仅在真实文件不存在时建立默认库；严格校验 UTF-8、JSON 结构、重复键和 ID、参数、大小、数量与嵌套边界。有效未知字段可以读取；保存按已知 ChatProfile 格式写入。读取或写入异常使用固定安全错误，不携带提示词、原文或文件路径。

同进程多个 Store 串行读改写；同目录 0600 临时文件完整写入并同步，核对源身份及字节后仅通过原子替换提交，无非原子回退。允许 Android 既有目录别名，拒绝目标文件符号链接及异常路径。存储失败后只有显式成功重读才能解除写入门禁，防止从陈旧默认快照覆盖原库。

表单验证温度为有限的 0.0..2.0 数值，不再把 `abc` 静默替换为 0.7。保存成功才关闭并清空；失败保留标识、名称、提示词和温度。预设读取失败时发送保留聊天草稿，要求先重试读取。维持既有 UI、字体、插件、取消及思考显示规则。

## 验证方式

本地仅做源码、资源、补丁和 Python 静态检查；不运行 Gradle、Kotlin／JVM、Go、Android 编译或运行测试。

新增验证覆盖真实 HTTP 的 SSE／405／501 参数、历史隔离、默认与修改过的 default、目录模型不被 alias 替换、两种排队调度的不可变快照、上下文字符边界、损坏／不可读库及原子提交失败、表单输入保留。实际 Linux mobile／androidhost 核心冒烟保存参数转发证据；Android API 35 综合回归从原生表单输入非法温度、改为零值保存，再真实点击聊天发送，核对 Go 网关转发及 Room 历史。

## 云端验收

验证源码为 `0c4996621ac3050209e849b3d6c73437a4eb0ee8`，versionName `0.1.57-chat-profiles`，versionCode 58，备份 appVersion `0.1.57`。固定上游与核心版本不变。

- [完整 Validation](https://github.com/fplj-fplj/magpie-mobile/actions/runs/44655555549) 的 regression 与 runtime-emulator 均通过。已下载报告，JVM 共 1,060 项，core 645 项、app 415 项，本轮新增 58 项；失败、错误、跳过均为 0。
- 报告包含 ChatProfileRequestTest 13 项、ChatProfileStoreTest 24 项、ChatProfileTest 5 项、ChatProfileFormStateTest 6 项、ChatProfileViewModelTest 13 项、ChatViewModelTest 30 项。请求测试使用实际 HTTP peer；Room、配置快照与保存异常测试不是仅检查 DTO。
- Go mobile 47 个顶层测试、53 个通过事件；androidhost 23 个顶层测试、25 个通过事件。实际 mobile／androidhost 核心各通过 8 组目录 RPC/TLS 验证；真实官方、社区插件集成及 plugin/gui race 回归均通过。
- `mobile-chat-profile.json` 与 `androidhost-chat-profile.json` 均为 passed=true，记录系统消息与完整历史未变、显式零值采样保留、正值 max_tokens 转发、目录模型路由与单次发送保持。
- Android API 35 x86_64 综合测试通过，XML 耗时 128.563 秒，无失败、错误、跳过。`chat-profile-form-and-request.txt` 记录实际表单拒绝 abc 并保留全部输入、改为零值后成功关闭、真实 UI 发送经安装包 Go 核心仅抵达上游一次，以及别名不替换已确认模型、Room 只有 user／assistant。Top-P、maxTokens 和 alias 由同一生产 ViewModel API 设置，不声称已有对应的表单控件。
- 已查看 `chat-profile-default.png`、`chat-profile-invalid-temperature.png`、`chat-profile-saved.png`；默认说明、错误输入与保存成功状态均符合预期，保留原有暖纸色与霞鹜文楷。成功截图在 ViewModel API 设置额外参数之前截取，其 Top-P=1.0 与后续请求探针 Top-P=0.0 不混为同一次状态。
- [签名候选](https://github.com/fplj-fplj/magpie-mobile/actions/runs/44655636386) 成功，publish 任务按候选设置跳过。实际候选 APK 为 103,546,860 字节，SHA-256 `0a398e13525f020a529d59d8049e37d13e57fa6fc6f9d0b4b75a757db5c80529`。下载后核对了 APK v2 固定证书与 ARM64 PIE 核心载荷；候选哈希不冒充正式重建哈希。

| 已下载并核对摘要的证据 | Artifact ID |
| --- | ---: |
| JVM 报告 | 14592424304 |
| Android 运行与截图 | 14592174913 |
| Go、RPC/TLS、插件、race 与参数转发 | 14592888862 |
| 签名候选 | 14592763123 |

## 正式发布核验

- [正式构建与发布](https://github.com/fplj-fplj/magpie-mobile/actions/runs/44656977105) 通过，仍为已验证源码 `0c4996621ac3050209e849b3d6c73437a4eb0ee8`。后续提交仅记录验收，不修改应用源码。
- [Release v0.1.57-chat-profiles-0c499662](https://github.com/fplj-fplj/magpie-mobile/releases/tag/v0.1.57-chat-profiles-0c499662)，ID `452174591`，是 Latest、非草稿、非预发布。正式 tag 精确指向上述提交，附件恰为 APK、APK checksum、build-info、core build-info 四项。
- 正式 APK `magpie-mobile-0.1.57-chat-profiles.apk` 为 103,546,860 字节，SHA-256 `dfb5a2044e85f0b354441e0270a449c65e5c33c33c8f016a4db30bd36b1bba29`。已下载并核对正式 artifact `14593602445` 的摘要 `c492e02996cfad5d9cfb47727566a2f293aea8d755f0b44904478ce081a6c8e9`，其中实际 APK 字节与 Release asset digest、checksum 相符。正式重建哈希与候选不同，分别核对而未混用。
- 实际 APK v2 签名证书 SHA-256 为固定 `dee1d3f485ee17a6184178eaab318d6333fb8b67e7276146a30a03e1f9ee09d6`；包内 ARM64 PIE 核心 SHA-256 为 `bf755425a0006c1c4e502c77f5d46c6e5bacc0411f96a6437f8b9ba64b1c3abf`。ARM64 只检查签名／载荷，不声称真机运行通过。
- Release 说明已补入实际同提交验收、正式 APK 哈希与运行边界；独立 GET 读回确认正文完全一致，四项原附件 ID 保持不变。

## 范围

Android 云端运行环境为 API 35 x86_64 模拟器。ARM64 正式包仅检查固定签名、ELF 和核心载荷，不将其描述为 ARM64 真机运行通过。个人 API 服务端、具体外部插件的原生依赖与不同设备仍需各自验收。

后续独立批次将处理提供商保存中手动模型字段仍可编辑、外部编辑返回事件在编辑器打开或繁忙时丢失补刷新等问题。本批未将这些未修项描述为已完成。
