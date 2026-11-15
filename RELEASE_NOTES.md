## Magpie-Mobile 0.1.43-editor-entry（精简编辑方式入口）

- “选择编辑方式”卡片仅在“模型提供商”和“更多”主页显示。
- 移除“路由与号池”“系统与诊断”二级页的重复选择卡片，进入后直接显示对应功能。

版本：versionCode 44；备份文档 appVersion `0.1.43`；核心保持 `0.1.3781-androidhost.2`。

## Magpie-Mobile 0.1.42-provider-insets（提供商编辑安全区修复）

- 修复 Android 全面屏手势栏覆盖“添加提供商 → 高级”底部保存按钮的问题。提供商编辑对话框现在与插件编辑页统一采用边到边窗口，并由 `safeDrawingPadding` 显式避开状态栏、手势栏和显示挖孔。
- 保持现有暖纸色／深色主题、文楷屏幕版、顶部页签和保存按钮样式不变；保存操作随页签内容滚动，避免固定 Footer 被不同厂商的对话框窗口排到屏幕外。
- Android 运行流程新增高级页保存按钮完整可见检查，并保存 `provider-editor-advanced.png`，防止手势栏裁切回归。

版本：versionCode 43；备份文档 appVersion `0.1.42`；核心保持 `0.1.3781-androidhost.2`。

## Magpie-Mobile 0.1.41-reader-switch（屏幕阅读与单一启停开关）

- 网关状态卡改为固定位置的单一开关；关闭、运行、启动与停止状态共用同一布局。停止回收期间禁用开关，启动／恢复期间仍可关闭网关。
- 内置官方霞鹜文楷屏幕阅读版 v1.530，更厚的基础笔画适合 Android 屏幕。正文、标题、按钮、导航、输入框、端点和参数统一使用此字体，仅顶部「Magpie Mobile」保留品牌字体。
- 标题和操作文字加强字重，正文与辅助文字适当增大字号；移除 Lora 与等宽字体的局部覆盖，保持汉字、数字与英文的统一字形来源。
- GitHub Actions 直接操作新开关启动与停止真实核心，保存横屏／竖屏状态卡、原生参数与登录、大字体及停止证据。每次构建与签名／载荷校验成功自动发到 Release；JVM、Go 和模拟器测试独立运行并保留结果。

版本：versionCode 42；备份文档 appVersion `0.1.41`；核心保持 `0.1.3781-androidhost.2`。

## Magpie-Mobile 0.1.40-official-plugins（官方插件接入候选）

- 原生「插件与工具」改用官方核心管理接口，支持目录发现、npm／GitHub／文件／目录安装、启停、更新、删除、JSON 参数与登录。原生和浏览器共用核心配置，返回应用刷新提供商与模型。
- 保留官方提供商／认证／工具 Bun 宿主、Go/moejs 同步中间件和 Bun 异步 Hooks。目录导入先完整复制，依赖安装关闭脚本；列表区分配置启用与加载错误。
- 插件详情使用全屏页签与暖纸色主题，显示工具及 MCP 地址、中间件和 Hooks 诊断；动态登录适应键盘与长内容，明确显示失败和取消终态。
- 核心升为 `0.1.3781-androidhost.2`，适配插件对本机网关的调用凭据；专用普通 Key 不与管理或聊天 Key 串用。插件同 UID 运行，专用凭据不代表沙箱隔离。
- Actions 增加插件包回归、真实 Go+Bun 宿主测试、Android 测试 APK 运行及截图；签名候选发布等待构建与运行 job 通过。
- SAF 副本记录提交与登记状态；明确失败后对账清理，网页移除后重读回收，结果未知的安装保留副本。清理跟随 ViewModel 操作，离开插件页后仍能完成。
- 原生提供商配置写入使用独立 30 秒预算，状态读取维持 5 秒，减少慢盘提交被误报为超时的情况。

版本：versionCode 41；备份文档 appVersion `0.1.40`。候选发布标记为 prerelease；生产 ARM64 真机运行、具体插件的原生依赖及个人账号服务端验证仍按报告逐项验收。

## Magpie-Mobile 0.1.39-full-core-editors（完整核心与双编辑）

- 核心改用官方完整 CLI 源码的 Android 宿主构建，标签 `nogui,androidhost`，版本 `0.1.3781-androidhost.1`；不再以 `mobile` 功能白名单运行。
- 保留服务所有权、loopback 管理端、有效网关 Key 和 APK 更新边界。恢复官方配置接口，内嵌官方网页；新模式不因未安装桌面 Agent 自动隐藏完整页面。
- 提供原生提供商与官方浏览器编辑两种入口。浏览器由一次性票据登录，30 秒有效，管理主密钥不放入 Intent / URL；返回后清除提供商缓存并刷新模型。
- 原生提供商参考 RikkaHub 的列表与详情结构：整行进入、状态与模型数量标签，以及「配置 / 模型 / 高级」页签，模型整行勾选、搜索和固定保存按钮。
- 原生 OpenAI 兼容提供商使用现有版本事务；扩展协议在官方网页编辑，避免基础表单覆盖未知字段。原生路由、号池、MCP 和插件示例明确标注尚未同步核心，实际高级配置使用官方网页。
- Actions 同时运行 legacy mobile 与 Android 宿主回归，新增完整接口、浏览器票据及返回刷新测试和真实宿主烟测；签名与 16KB 对齐流程不变。

版本：versionCode 40；备份文档 appVersion `0.1.39`。Android 插件与桌面程序兼容性、浏览器回跳及视觉效果仍需真机验收。

## Magpie-Mobile 0.1.38-paper-ui（P9 界面与设备状态修复）

- 网关首页集中显示运行状态、本机端点和四个常用入口；接入应用、客户端密钥、用量与速度、路由与号池进入二级页，模型目录、插件与工具、系统与诊断收纳到「更多」。二级页支持返回、旋转恢复和独立滚动位置。
- 暖纸色背景、反相运行状态面板、单色线框导航图标和统一的 24dp 卡片内距；接入按钮与主题选择可换行，宽屏内容限制在 720dp。
- 内置霞鹜文楷 Lite Regular（固定上游提交 `00cf13cf`、原始 TTF 13.3MiB、OFL 随包分发）。使用 Android API 29 CustomFallbackBuilder 实现 Lora → 文楷 → 系统字体的真实缺字回退；中文正文行高 1.75。修正 Poppins 字重资源映射和橙色按钮文字对比度。
- 聊天页突出会话内容；模型与 Profile 设置按需展开，用户、助手及失败消息分层呈现。输入框支持多行，模型菜单绑定按钮，消息区适应屏幕高度并在靠近底部时跟随新消息。
- 补齐网络状态和电池优化请求权限，声明系统设置查询；电池设置采用授权页 → 系统列表 → 应用详情 → 系统设置回退，打开失败也提供操作反馈，返回应用时重新检测。
- 网络监听跟随默认网络，状态流实时更新；权限缺失与无网络分开呈现。
- 插件界面说明当前仅管理文件和示例，完整登录与执行仍待接入；移除误导性的沙箱和可用性声明。配置备份页明确文件选择入口尚未接入。
- 新增电池设置回退和网络状态回归用例；Actions 增加实际字体 SHA-256、汉字字形和许可核验。构建与 JVM 回归继续仅由 GitHub Actions 执行，真机验收另行记录。

版本：versionCode 39；核心保持 `0.1.3781-mobile.1`；备份文档 appVersion `0.1.38`。

## Magpie-Mobile 0.1.37-provider-serverpin (P8 功能对齐 A2)

### 提供商服务器证书指纹（serverPin）
- 创建/编辑提供商支持「服务器证书 SHA-256 指纹」（64 位小写 hex，留空不校验）：ProviderCreateDraft/ProviderEditDraft 透传，编辑仅在指纹变化时提交；
- 核心 overlay：mobile_providers.go 解码白名单放行 serverPin；mobile_store.go 快照/创建/编辑三处对齐上游 provider.ServerPin（格式保持式 edit.KV 写入），mobileServerPin 校验（空或 64 位小写 hex）；
- ProvidersPanel 高级设置新增指纹输入框；编辑打开时回填现有指纹；
- 单测新增 ProviderServerPinTest（4 例：校验边界/创建嵌入/编辑脏检测）。

### 版本
- versionCode 38；核心 0.1.3781-mobile.1 不变；备份包 APP_VERSION 0.1.37。

## Magpie-Mobile 0.1.36-key-restrictions (P8 功能对齐 A1+A3)

### 密钥限制读写（核心 3781）
- **A1 扩展写**：POST /api/caller-keys/{models-key|aliases-key|tier-key} 白名单放行；CoreManagementClient.updateCallerKey 每次单字段发送（encodeDefaults=false，绝不误清其他限制）；网关密钥卡片新增「限制」编辑对话框（模型白名单/模型别名/服务档）；
- **A3 只读展示**：密钥行内联展示额度窗口（period×days/hours、tokens/cost 上限）、moreLimits 计数、模型白名单、别名数、服务档、并发上限；RegisteredGatewayKey 与 KeyLimit 模型对齐 upstream access.Key/Limit；
- 单测新增 KeyRestrictionsParsingTest（3 例：载荷省略空字段/空模型字段丢弃/限额与 tier 解析）。

### 版本
- versionCode 37；核心 0.1.3781-mobile.1 不变；备份包 APP_VERSION 0.1.36。

## Magpie-Mobile 0.1.35-model-speeds-catalog (P8 功能对齐 A5+A4)

### 新端点对接（核心 3781）
- **A5 模型速度排行**：GET /api/model-speeds 白名单放行 + CoreManagementClient.modelSpeeds() 弹性解析（ttft/tps/n），网关页新增「本周模型速度排行」卡片（top 6，首 token 毫秒 + 输出 tok/s）；
- **A4 模型目录**：GET /api/catalog/models 白名单放行 + catalogModels() 解析，网关页新增「模型目录（models.dev 已同步）」卡片（条目总数 + 前 8 条 id/名称/上下文窗口）；
- 安全口径延续：只读、仅聚合元数据、解析失败空表、绝不呈现原始正文；白名单仍为精确路径 allowlist；
- 单测新增 ModelSpeedsCatalogParsingTest（5 例：典型/缺字段/畸形/空白/错误形状）。

### 版本
- versionCode 36；核心 0.1.3781-mobile.1 不变；备份包 APP_VERSION 0.1.35。

## Magpie-Mobile 0.1.34-upstream-3781 (P8 核心升级：对齐上游 270 commits)

### 核心升级 3622 → 3781（b52c711142a662fe18fedf5a80cb1e4aa94bce62）
- 上游 270 commits / 909 文件：新增 Agent 管理、订阅账号、隐私脱敏（PII masking）、意图路由、网关中间件、搜索/生图 MCP 工具等 40+ 功能域（详 docs/p8-gap-3781.md）；
- 移动补丁包重定位：34 条替换锚点 33 条直接命中，仅 lan.go 身份注入 1 条按新上下文（aliasRequest 别名行）重推导；apply 模拟测试 19/19 通过；
- 安全策略完整保留：mobilepolicy 开关、受管密钥、loopback 门禁与 callerGuard 认证路径全部延续；
- API 面纯增量（22 条新 /api 路由、0 删除），移动白名单为精确路径 allowlist，新桌面路由在 mobile 下自动 404 无暴露面；
- Go 1.26.8 不变；nogui,mobile 标签不变；16KB 页面对齐与固定签名链路不变。

### 版本
- versionCode 35；CORE_VERSION 0.1.3781-mobile.1；备份包 APP_VERSION 0.1.34。

## Magpie-Mobile 0.1.33-structure-uikit (P8 结构优化第三阶段)

### 结构优化（零行为变更）
- **SectionCard 原语抽取**：新增 UiBits.kt 共享 UI 组件，统一「卡片 + 12dp 内距 + 8dp 纵向间距 + 标题」骨架；
- NativeNavigation 六处手写 Card{Column{…}} 内联卡（网关状态/核心安全连接/本机界面偏好/版本与诊断/引擎状态/安全备份）全部替换为 SectionCard；
- 效果：NativeNavigation 326→308 行，卡片布局令牌单点维护（与 Anthropic 主题的卡片规范一致），括号平衡核验通过，行为零变更。

## Magpie-Mobile 0.1.32-anthropic-theme (P8 UI 风格重塑)

### Anthropic 风格主题（anthropic-style-frontend-cn 技能 · Compose 适配 · 工具优先模式）
- **暖米色大地调**：页面基底 #ECE9E0（不用纯白），卡片抬升色 #F5F3EC，深色反相区块 #141413；
- **唯一强调色陶土橙 #D97757** 承担主 CTA；危险操作使用错误红 #C0453A 强调；
- **品牌字体落地**：标题/正文 Lora（衬线）、UI/按钮/标签 Poppins（无衬线），共 7 枚字重文件内置 res/font，SIL OFL 许可随包分发（assets/licenses/ofl-lora.txt、ofl-poppins.txt）；
- **明暗双主题令牌**：完整 colorScheme/typography/shapes 三组 Token（theme/UiTheme.kt），全应用零硬编码颜色，一处接入（MainActivity）；
- **工具优先模式**：管理界面保持既有信息密度与 12dp 卡片节奏，仅重设色彩/字体/圆角，不牺牲可读性。

### 版本
- versionCode 33；核心 0.1.3622-mobile.1 不变；备份包 APP_VERSION 0.1.32。

## Magpie-Mobile 0.1.31-session-dialog-split (P7 结构优化延续)

### 结构优化（零行为变更）
- **ChatPanel 瘦身**：历史会话对话框（切换/重命名/删除/加载更多，约 90 行）外迁至 SessionHistoryDialog.kt，ChatPanel 由 154 行降至约 105 行；
- `chatDate` 工具函数随迁（internal，同包共用，ChatPanel 保留调用）；
- 纯代码移动：括号平衡、import 去重、调用点与定义分离核验通过，行为零变更。
