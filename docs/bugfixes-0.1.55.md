# 0.1.55：模型目录遵循证书指纹

日期：2026-11-20 UTC。versionName `0.1.55-discovery-tls-pin`，versionCode 56，备份 appVersion `0.1.55`。固定上游 `b52c711142a662fe18fedf5a80cb1e4aa94bce62`，核心版本保持 `0.1.3781-androidhost.2`。

## 修复范围

原生自动目录读取原本没有传递编辑页的 serverPin；Go 专用 discovery transport 也只做系统 TLS 校验。正确指纹对应的自签证书不能读取，系统信任但不同于保存指纹的证书则没有受到额外约束。

现在 Kotlin DTO、严格 GUI JSON 解码与 Go DTO 共同支持可选 `serverPin`。缺失字段、显式空串和非空指纹分别表示同源继承、系统 TLS、精确证书 SHA-256 校验。JSON null、重复或拼写变体继续拒绝。原生页面始终发送当前可见草稿值，包括空串；不静默清掉跨源草稿中显示的指纹。

读取已保存提供商时，key 与 pin 在同一 canonical mutex/file lock、同一次 revision 校验内取得，联网前释放锁。旧 key 仍只在同源、没有显式新 key 时复用；继承的 pin 同样只属于原 origin。提供新 key 改 origin 也不自动继承旧 pin；显式传入的 pin 严格按本次请求执行。

非空 pin 只允许 HTTPS，必须为 64 位小写十六进制。握手阶段比较 leaf certificate 原始 DER 的 SHA-256；精确匹配才接受，语义与固定上游 netproxy 的 pinTLS 一致，可用于对应自签证书。空 pin 保留普通系统证书链、名称与有效期校验。专用传输继续禁止环境代理与重定向，不引入全局 InsecureTLS 或 provider 的其他代理策略。握手失败只返回固定 `provider_models_tls_pin`，不返回内部 PinMismatchError 的主机与指纹。

页面把规范 pin 纳入 effect 和请求状态检查；输入事件即时增加目录代次，使重组前旧回调同样失效。成功、错误、地址采用共用 currentness 校验；排入 IO 队列后，在实际构造和发送请求前再次核验同一状态，防止等待 worker 期间的 pin 修改留下旧请求。修改后又恢复、或等价空白变化，旧请求也不能恢复有效。现有高级表单内展示格式或 HTTP 组合错误，不新建页面或确认框。原有选择策略、保存 CAS、会话租约和未保存 key 均保持。

## 验证设计与边界

本地仅 Python 静态契约、语法、资源、补丁哈希和 `git diff --check`；没有本地 Gradle、Go、JVM 或 Android 编译、运行测试。以下为测试边界，实际同提交云端证据见下文。

- Go 使用生产调用的私有 transport factory，测试只注入 trust roots 与 fixture dial；精确 pin verifier 始终由生产 factory 设置，没有 RPC 可选安全绕过或全局可变 hook。
- host integration 启动 Actions 编译的真实 mobile 与 androidhost 核心，通过受鉴权的正式管理 RPC 读取模型目录。runner 的 `/etc/hosts` 仅把保留测试域 `catalog.magpie.test` 映射到本地临时 TLS 服务；child core 的 SSL_CERT_FILE 只增加一个临时 fixture trust root。不改变生产验证器和 App 配置。
- 真实 TLS 矩阵区分：自签正确 pin 成功；自签错误 pin 零 HTTP；受信证书错误 pin 零 HTTP；受信证书空 pin 成功；不受信自签空 pin 按系统 TLS 拒绝。还检查 stored 三态、同源版本、跨源不继承、同源 404 备选、重定向目标零请求，以及整轮 canonical 文件字节不变。
- Kotlin 测试覆盖三态序列化、HTTP 组合拒绝、实际管理 transport 错误头映射、固定脱敏提示、临时 key 清理、不重放及返回 URL 同源边界。
- App policy 测试覆盖 pin 变更和旧响应／错误／地址采用；Android 仪器路径验证 APK 内真实 Go RPC 的空 pin 请求、非法格式／HTTP+pin 零上游请求、revision 不变和现有高级页提示。Android 的新增局部 fixture 使用 HTTP，不冒充 Android TLS 握手矩阵；完整 TLS 握手矩阵由真实 host core 执行。
- 完整 Validation 保留已有 JVM、Go、真实官方／社区插件、race 与 Android API 35 x86_64 综合测试。ARM64 APK 仅核验实际签名与载荷，不宣称 ARM64 真机执行。

## 后续已确认问题

本批只修目录 TLS pin。重复 STOP 新建空 Service 的清理 owner 竞争、ChatProfile 请求参数与失败草稿保留，以及提供商外部返回刷新／保存中手动模型输入等问题保留为后续批次，不宣称已修复。

## 已取得的同提交云端证据

验证源码：`39f0778999af7739a8631bfacc59ce9f8e3793f4`。仅说明文档的后续提交不改变该 App 源码或正式 tag 目标。

- [完整 Validation](https://github.com/fplj-fplj/magpie-mobile/actions/runs/44645456554)：regression 与 runtime-emulator 均成功。
- [签名候选，publish_release=false](https://github.com/fplj-fplj/magpie-mobile/actions/runs/44645457612)：成功，候选仅归档到 Actions。
- 已下载并核对 GitHub artifact SHA-256。JVM XML：core 607、app 379，共 986 项，本轮新增 16 项，失败／错误／跳过均为 0。相关 suite 为 ProviderModelDiscoveryTest 36、ProviderEditorPolicyTest 21、ProviderOperationControllerTest 20。
- Go JSON：mobile 47 个顶层测试、53 个通过事件（含子用例）；androidhost 23 个顶层测试、25 个通过事件。没有 fail 事件。本轮新增 9 个 provider TLS 顶层测试和 3 个 GUI 顶层测试，生产握手 factory 继续参与真实 TLS 测试。
- `mobile-discovery-tls.json` 与 `androidhost-discovery-tls.json` 均为 passed=true，每种真实核心完成 8 组完整 RPC 检查：正确自签 pin、错误自签 pin、受信证书错误 pin、普通 TLS、存储 pin 三态／过期版本、跨源、404／重定向、非法组合及配置不变。错误 pin 的服务端 HTTP handler 与 Authorization 到达数确实为零。
- 真实官方／社区 provider、tools、middleware、async hooks、PowerContext、npm／GitHub 插件与更新验证均通过；plugin/gui race 回归通过。
- Android API 35 x86_64：1 项综合测试，XML 总耗时 135.789 秒，0 失败／错误／跳过。实际 APK 内 Go 接受显式空 pin；拒绝非法格式／HTTP+pin，真实上游请求数不增加，提供商 revision 不变。完整插件、聊天／目录／连接撤销和安全启停的既有综合路径继续通过。
- 已检查最终 `provider-invalid-pin.png` 与 `provider-editor-advanced.png`：错误与空值提示位于原高级页表单，暖纸色、文楷、原页脚和键盘避让保持。测试通过现有“返回提供商列表”按钮退出，避免输入法消费返回键。
- 签名候选实际 APK v2 证书 SHA-256：`dee1d3f485ee17a6184178eaab318d6333fb8b67e7276146a30a03e1f9ee09d6`。包内 ARM64 PIE 核心 SHA-256：`bf755425a0006c1c4e502c77f5d46c6e5bacc0411f96a6437f8b9ba64b1c3abf`。候选 APK 为 103,511,416 字节，SHA-256 `2dadc8f4dd09488b2990b0e249d5d9907088c2d5852affd9579097b06397bbff`。

首轮 `7fbfd407` 的签名候选和 regression 已通过，但 Android 在新增指纹提示检查之后，最后一次返回键被输入法消费，退出编辑器等待超时，因此未发布。交叉审查另补了 IO 排队期间修改 pin 的 worker 入口检查，以及两个真实 helper／双 dispatcher 回归；最终提交完整重跑通过，没有跳过断言或放宽 TLS 规则。

已下载并核对 artifact digest 的证据：

| 证据 | Artifact ID | 所属 run |
| --- | ---: | ---: |
| JVM 报告 | 14588571504 | 44645456554 |
| Android 运行与截图 | 14589360712 | 44645456554 |
| Go／RPC TLS／插件／race | 14589266442 | 44645456554 |
| 签名候选 | 14589235166 | 44645457612 |

## 正式发布核验

- [正式构建与发布](https://github.com/fplj-fplj/magpie-mobile/actions/runs/44647056590)成功，仍为上述已验证源码 `39f0778999af7739a8631bfacc59ce9f8e3793f4`。
- [Release `v0.1.55-discovery-tls-pin-39f07789`](https://github.com/fplj-fplj/magpie-mobile/releases/tag/v0.1.55-discovery-tls-pin-39f07789)，ID `452118828`，已核实为 Latest、非草稿、非预发布；正式 tag 精确指向已验证提交。
- 附件恰为 APK、APK `.sha256`、`build-info.txt`、`mobile-core-build-info.txt` 四项。build-info 中 commit、正式 run ID、固定签名均正确。
- 正式 APK：`magpie-mobile-0.1.55-discovery-tls-pin.apk`，103,511,416 字节，SHA-256 `3d5fa976d53014cbcd44c3cfd17dac43789ac8b33dd505bb95a28c4d270e2c17`。
- 已下载并核对正式签名 artifact `14589536975`（所属 run `44647056590`），检查其中实际 APK 的 v2 证书和 ARM64 PIE 载荷；正式 APK 字节哈希与 Release asset digest、附件 checksum 完全一致。不是以候选哈希代替正式文件。
- 正式 APK 继续使用固定证书 `dee1d3f485ee17a6184178eaab318d6333fb8b67e7276146a30a03e1f9ee09d6`；包内核心 `bf755425a0006c1c4e502c77f5d46c6e5bacc0411f96a6437f8b9ba64b1c3abf`。ARM64 为签名／载荷核验，没有宣称 ARM64 真机执行。
- Release 说明已补充同提交实际验证结果，并重新 GET 读回确认正文完全一致、四项附件仍完整；没有以待验证描述替代已完成验收。
