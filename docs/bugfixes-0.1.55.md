# 0.1.55：模型目录遵循证书指纹

日期：2026-11-20 UTC。versionName `0.1.55-discovery-tls-pin`，versionCode 56，备份 appVersion `0.1.55`。固定上游 `b52c711142a662fe18fedf5a80cb1e4aa94bce62`，核心版本保持 `0.1.3781-androidhost.2`。

## 修复范围

原生自动目录读取原本没有传递编辑页的 serverPin；Go 专用 discovery transport 也只做系统 TLS 校验。正确指纹对应的自签证书不能读取，系统信任但不同于保存指纹的证书则没有受到额外约束。

现在 Kotlin DTO、严格 GUI JSON 解码与 Go DTO 共同支持可选 `serverPin`。缺失字段、显式空串和非空指纹分别表示同源继承、系统 TLS、精确证书 SHA-256 校验。JSON null、重复或拼写变体继续拒绝。原生页面始终发送当前可见草稿值，包括空串；不静默清掉跨源草稿中显示的指纹。

读取已保存提供商时，key 与 pin 在同一 canonical mutex/file lock、同一次 revision 校验内取得，联网前释放锁。旧 key 仍只在同源、没有显式新 key 时复用；继承的 pin 同样只属于原 origin。提供新 key 改 origin 也不自动继承旧 pin；显式传入的 pin 严格按本次请求执行。

非空 pin 只允许 HTTPS，必须为 64 位小写十六进制。握手阶段比较 leaf certificate 原始 DER 的 SHA-256；精确匹配才接受，语义与固定上游 netproxy 的 pinTLS 一致，可用于对应自签证书。空 pin 保留普通系统证书链、名称与有效期校验。专用传输继续禁止环境代理与重定向，不引入全局 InsecureTLS 或 provider 的其他代理策略。握手失败只返回固定 `provider_models_tls_pin`，不返回内部 PinMismatchError 的主机与指纹。

页面把规范 pin 纳入 effect 和请求状态检查；输入事件即时增加目录代次，使重组前旧回调同样失效。成功、错误、地址采用共用 currentness 校验；排入 IO 队列后，在实际构造和发送请求前再次核验同一状态，防止等待 worker 期间的 pin 修改留下旧请求。修改后又恢复、或等价空白变化，旧请求也不能恢复有效。现有高级表单内展示格式或 HTTP 组合错误，不新建页面或确认框。原有选择策略、保存 CAS、会话租约和未保存 key 均保持。

## 验证设计与边界

本地仅 Python 静态契约、语法、资源、补丁哈希和 `git diff --check`；没有本地 Gradle、Go、JVM 或 Android 编译、运行测试。下面列出测试设计，实际通过结果与发布核验在云端完成后追加。

- Go 使用生产调用的私有 transport factory，测试只注入 trust roots 与 fixture dial；精确 pin verifier 始终由生产 factory 设置，没有 RPC 可选安全绕过或全局可变 hook。
- host integration 启动 Actions 编译的真实 mobile 与 androidhost 核心，通过受鉴权的正式管理 RPC 读取模型目录。runner 的 `/etc/hosts` 仅把保留测试域 `catalog.magpie.test` 映射到本地临时 TLS 服务；child core 的 SSL_CERT_FILE 只增加一个临时 fixture trust root。不改变生产验证器和 App 配置。
- 真实 TLS 矩阵区分：自签正确 pin 成功；自签错误 pin 零 HTTP；受信证书错误 pin 零 HTTP；受信证书空 pin 成功；不受信自签空 pin 按系统 TLS 拒绝。还检查 stored 三态、同源版本、跨源不继承、同源 404 备选、重定向目标零请求，以及整轮 canonical 文件字节不变。
- Kotlin 测试覆盖三态序列化、HTTP 组合拒绝、实际管理 transport 错误头映射、固定脱敏提示、临时 key 清理、不重放及返回 URL 同源边界。
- App policy 测试覆盖 pin 变更和旧响应／错误／地址采用；Android 仪器路径验证 APK 内真实 Go RPC 的空 pin 请求、非法格式／HTTP+pin 零上游请求、revision 不变和现有高级页提示。Android 的新增局部 fixture 使用 HTTP，不冒充 Android TLS 握手矩阵；完整 TLS 握手矩阵由真实 host core 执行。
- 完整 Validation 保留已有 JVM、Go、真实官方／社区插件、race 与 Android API 35 x86_64 综合测试。ARM64 APK 仅核验实际签名与载荷，不宣称 ARM64 真机执行。

## 后续已确认问题

本批只修目录 TLS pin。重复 STOP 新建空 Service 的清理 owner 竞争、ChatProfile 请求参数与失败草稿保留，以及提供商外部返回刷新／保存中手动模型输入等问题保留为后续批次，不宣称已修复。
