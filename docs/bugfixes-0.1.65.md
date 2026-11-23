# 0.1.65 Android 管理会话与界面收尾

## 管理密钥不再经过浏览器 Cookie

旧的 Android 宿主把管理密钥放进 `127.0.0.1` Cookie。浏览器 Cookie 不按端口隔离，本机其他 loopback 服务因此可能收到完整管理权限。

- 原生客户端仅通过 `X-Magpie-Android-Manager` 请求头认证管理 API；服务端要求精确 `127.0.0.1:<port>` Host，拒绝浏览器 Origin、Referer、Sec-Fetch 元数据及 legacy Cookie。
- 外部编辑器由原生 App 获取 30 秒一次性 ticket。浏览器兑换后将 30 分钟随机 proof 放入当前 tab 的 `sessionStorage`，后续同源 API 通过 proof header 认证。链接和页面 URL 不携带 proof，服务端不设置管理 Cookie。
- API、`boot.js` 和 `dev.js` 需要认证上下文；认证后的 API 重定向被阻断。官方归档及会话下载由 proof-aware bootstrap 获取并以 Blob 触发下载；API 响应流不被读取或缓冲。图标 GET/HEAD 只开放 same-origin 资源读取。
- 保留官方插件页面不可猜测的运行时路径令牌和插件网关显式恢复语义。补充 Android host 和 mobile 两类 host smoke、Go 安全 fixture、Kotlin HTTP fixture 及真实插件密钥复制敏感标记检查。

## 界面修复

提高浅色主题操作文字和错误提示的对比度；历史会话弹窗随窗口高度收缩并保留关闭操作；提供商编辑页签及模型分组提供 TalkBack 选中与展开状态。插件登录的敏感 prompt、API 密钥和 OAuth 回执使用 Android 敏感剪贴板标记。

保持暖纸色、霞鹜文楷和 RikkaHub 页面布局。本次不重排现有编辑流程。

## 发布防护

Release 上传附件后在公开前重新核验精确同 SHA 的 Validation run/attempt；Latest 标记会在发布前后与 GitHub 实际状态对照，旧版恢复默认不抢 Latest。新增需显式保留 tag、dry-run 与二次确认的 Release 对象清理脚本；清理不删除 Git tag。

本版版本号 `0.1.65-editor-session`，versionCode 66，核心 `0.1.3781-androidhost.4`。所有构建和运行测试仅由 GitHub Actions 执行，通过完整验证前不会发布。

候选 `287400e39bef75d1d7f5edfc0865ae20cd9aec23` 的首轮 Validation `45248885240` 未通过：Android 测试因 UiAutomator 不支持的正则文本选择器而未能编译；Go 启动清单回归以未认证方式请求受保护的 `/boot.js`。第二轮 `f4fefa6574eb6eca6b7a19ec09969c49aeb656a4` 已修正 Android 选择器并让 Go 夹具使用真实浏览器 proof，但 Go 测试编译发现局部变量名冲突。已修正该测试变量；最终提交仍需通过新的完整 Validation 和真实浏览器验收。
