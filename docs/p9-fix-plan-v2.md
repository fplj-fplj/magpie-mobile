# P9 真机反馈修复方案（目标 goal-62d5bf78）

> 依据：用户 2026-11-12 真机截图 + 源码只读核对。所有根因均带文件行号证据。
> 状态：诊断完成；代码落地待设备桥授权恢复（当前 write/edit 对已存在文件、bash 均返回
> `DSHA_DESTRUCTIVE_BLOCKED: DSHA_NATIVE_CONSENT_UNAVAILABLE`）。

## 问题① 网络状态误报「检测网络状态异常」

**现象**（截图 1）：`网络状态: 检测网络状态异常: ConnectivityService: Neither user 10096 nor current process has android.permission.ACCESS_NETWORK_STATE.`

**根因**：`app/src/main/AndroidManifest.xml` 只声明了 4 条权限（INTERNET、FOREGROUND_SERVICE、FOREGROUND_SERVICE_SPECIAL_USE、POST_NOTIFICATIONS），**缺 `ACCESS_NETWORK_STATE`**。`NetworkConnectivityMonitor` 调用 ConnectivityManager 时抛 SecurityException，被兜底捕获后渲染为「网络状态异常」。

**修复**（Manifest 第 4 行后插入两条）：
```xml
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
```

同时 `NetworkConnectivityMonitor` 应把 SecurityException 与「无网络」区分开：无权限时显示「需授予网络状态权限」，而不是「网络异常」。

## 问题② 「前往系统设置忽略电池优化」点击无效

**根因（三重）**：
1. `AndroidManifest.xml` 缺 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` → `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 无法解析，`resolveActivity` 返回 null；
2. `BatteryOptimizationHelper.createOptimizationSettingsIntent` 两级都解析失败时 **返回 null**，调用点 `SystemCards.kt:34` 用 `?.let` 静默跳过，无任何反馈；
3. `SystemCards.kt:37` `catch (_: Exception) {}` **吞掉全部异常**（含 ActivityNotFoundException / SecurityException），用户只能看到「点了没反应」。

**修复**：
- Manifest 加 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`（见①）。
- `createOptimizationSettingsIntent` 改为**多级回落链**，保证总有可用目标：
  1. `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`（package URI）
  2. `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`（系统列表页）
  3. `ACTION_APPLICATION_DETAILS_SETTINGS`（本应用详情页）
  4. 厂商 ROM 专用页（MIUI/HyperOS `com.miui.securitycenter` 省电策略页、EMUI 启动管理、ColorOS/vivo 后台管理），按 `Build.MANUFACTURER` 匹配且 `resolveActivity` 校验
- 返回值改为 `BatteryOptimizationRoute(intent?, label, note)`，UI 显示实际跳转目标；`intent == null` 时显示「未找到可跳转的系统页面，请手动前往 设置 → 应用 → Magpie Mobile → 省电策略」。
- 去掉静默 catch，改为捕获后置状态文案（不抛栈、不泄露）。
- 增加「已开启？重新检测」按钮（复用 `viewModel.refresh()`）形成闭环。

## 问题③ 字体非设计要求的霞鹜文楷

**现状**：`app/src/main/java/cc/fplj/magpiemobile/theme/UiTheme.kt` 只内置 Lora（衬线拉丁）+ Poppins（无衬线拉丁）共 7 枚，**无中文字体** → 中文回落系统默认（截图里是系统黑体）。

**规范**（`skills/anthropic-style-cn/references/typography-cn.md`）：中文正文用霞鹜文楷（LXGW WenKai），行高 `--leading-cn-normal: 1.75`，标题字重 400。

**修复**：
- 下载 LXGW WenKai Lite（约 5–8MB，避免 +20MB 全量）Regular + Bold 两枚 TTF → `app/src/main/res/font/lxgw_wenkai.ttf`、`lxgw_wenkai_bold.ttf`；
- 许可：OFL-1.1，随包 `assets/licenses/ofl-lxgw-wenkai.txt`（与 Lora/Poppins 同规格）；
- `UiTheme.kt` 字体栈：英文优先 Lora/Poppins，中文回落 LXGW WenKai（Compose 用 `FontFamily(Font(...))` 组合，中文缺字由后续 Family 兜底）；
- Typography 中文行高 1.75（bodyLarge 16sp/28sp、bodyMedium 14sp/24sp），标题字重 400；
- 体积与启动：字体放 `res/font`（非 assets）由 AGP 压缩，`font-display` 等价行为由 Compose 同步加载保证。

## 问题④ 页面信息过载 → 二级界面

**现状**：`NativeNavigation.kt` 四页（网关/提供商/聊天/更多）都是单个 `Column + verticalScroll` 平铺；网关页含 11 张卡片（端点/密钥/近期请求/用量洞察/速度/目录/路由/号池/插件/市场/稳定性/备份），单屏滚动数屏。

**方案（网关页拆二级）**：
| 层级 | 内容 |
| :--- | :--- |
| 网关首页（L1） | 状态卡（运行态 + 启动/停止 + 网络/电源两行摘要）、端点与 Key（精简）、待办提醒（未开白名单/未连核心）、4 个入口 |
| 接入详情（L2） | 端点、复制 Base URL / API Key / cURL、内置 Key |
| 密钥管理（L2） | 密钥列表 + 限制编辑 + 新增 |
| 用量分析（L2） | 近期请求、热力图、额度、额度历史、速度排行、目录 |
| 路由与号池（L2） | ModelRouting、KeyPool |
| 插件与市场（L2） | 订阅插件、插件市场、MCP |
| 系统与备份（L2） | 稳定性、备份恢复、诊断与版本 |

实现：新增 `GatewaySection` 枚举 + `var section: GatewaySection?`（null=首页）；L2 用 `BackHandler` 返回首页；卡片组件签名不变，仅换容器。同步为提供商页做「列表→编辑」二级（已有 Dialog，改为整页亦可）。

## 问题⑤ 插件适配要覆盖原版全部插件

**现状**：`core/…/SubscriptionPluginManager.kt` 只做**本地文件安装 + SHA-256 校验 + 备份回滚**（install/list/rollback），`PluginMarket.kt` 是受控市场元数据；**没有任何插件执行链路**——不加载 JS、不调 Bun、不参与登录/列模型/转发请求。因此实际可用插件数≈0，只能看不能跑。

**上游事实**（README 与 `internal/plugin`、`internal/pluginrt`、`internal/jsmod`）：
- 插件是 npm 包（OpenCode 插件）或 pi 包，跑在 **Bun** 上；
- 插件职责：登录、列模型、发送请求；Agent 把插件模型当普通 provider 用；
- 另有「网关中间件」形态：`onRequest`/`onEvent`/`onResponse` 三段 JS 钩子，可改请求/看流事件/看回复，异常或超时则原样放行。

**移动端适配路径（分三期）**：
1. **P9-1 中间件优先**（低成本高收益）：核心已自带 Bun（打包在 ELF 内），通过管理面调用插件 runtime；移动端新增「中间件」二级页：列出已装中间件、开关、参数 JSON 编辑、命中统计。中间件不依赖交互式登录，最适合移动端。
2. **P9-2 provider 型插件**：接入 `plugin login` 的扫码/回调流程（OAuth device flow 优先），把插件产出的模型注入 provider 列表；需要核心暴露 `plugin.login.status` 只读端点 + 移动白名单放行。
3. **P9-3 全量插件市场**：从 `magpie-community/plugins` 拉清单，按「是否支持无桌面登录」分组展示，支持「可装即可用」标记。

**明确限制（须写入 UI 文案）**：依赖桌面浏览器回调或本地 Keychain 的插件在 Android 上无法完成登录 → 标为「桌面专属」，不假装支持。

## 落地顺序与验收

1. ①+②（Manifest + 电池助手 + 稳定性卡文案）→ 0.1.38-hotfix，验证：真机不再误报网络异常；点按钮必能跳到某个系统页并显示跳到哪。
2. ③ 字体 → 0.1.39-fonts，验证：中文正文呈霞鹜文楷字形（「霞」「楷」等字有毛笔感）、行高明显更松。
3. ④ 二级界面 → 0.1.40-nav，验证：网关首页单屏可见且不超 2 屏，二级页可返回。
4. ⑤ 插件 P9-1 → 0.1.41-plugins-mw，验证：中间件列表可见、可开关、命中计数非零。
5. 每期 CI 全绿 + Release 带更新日志（沿用 git/gh 原生发版技能 proc_pre_e7065dc1390）。

## 当前阻塞

设备桥授权状态失效：`write`/`edit` 对**已存在文件**、`bash`、`glob(/tmp/...)` 均返回
`DSHA_DESTRUCTIVE_BLOCKED: DSHA_NATIVE_CONSENT_UNAVAILABLE: CANCELLED`（新建文件与 memory 通道正常）。
需在手机 **DSHA 应用 → 设置 → 设备能力授权** 重新确认授权后继续。
