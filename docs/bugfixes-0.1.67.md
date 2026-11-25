# 0.1.67 上游核心同步至 v0.1.4801

0.1.67 将锁定的上游核心由 `yetone/magpie` `v0.1.4759`（commit `95e6a4180866afde186e0f1a25369388c7fd7e7f`）升级至 `v0.1.4801`（commit `9cbf787cca8260e7ccc0d46b601b2c137ada1a5f`），Android 宿主核心版本更新为 `0.1.4801-androidhost.4`，应用版本升为 `0.1.67-upstream-v0.1.4801`（`versionCode = 68`）。

## 上游核心与宿主兼容核对

- 通过 GitHub Actions `sync-upstream.yml` 三方合并 13 个上游整文件补丁与 28 个 Android overlay，并经 `test_upstream_sync.py`、`test_apply_mobile_core.py` 与完整 GitHub Actions 验证确认兼容。
- Android 宿主安全边界保持完整：`X-Magpie-Android-Manager` 原生请求头、一次性浏览器编辑器票据与 tab proof、`/api/quit` 与 `/api/restart` 等宿主管控拦截、loopback 仅监听本机、禁用本地自更新及官方加密备份浏览器下载桥接均未改变。
- 上游新增与修复特性一并随 `0.1.4801-androidhost.4` 集成，包括 Bedrock AWS Access Key SigV4 鉴权、MCP OAuth 回调与保活控制、Gemini thinkingLevel / 工具调用处理、响应注解与长连接内存及账号筛选修复，以及未启用账号时隐藏已下线内置 Antigravity 添加入口；原生提供商客户端白名单字段解析不受 `noNew` 扩展字段影响。
- 界面保持暖纸色主题、RikkaHub 提供商与聊天页面结构、霞鹜文楷屏幕阅读版字体以及官方三类外部插件执行机制。

## 构建与验收记录

- 全部编译、单元测试、Go 回归、官方／社区插件集成与 Android API 35 模拟器测试均由 GitHub Actions 执行，本机不运行编译或测试。
- 上游适配 PR #3（分支 `automation/upstream-v0.1.4801`，commit `12b6ce0e76ba567a11cb6ea5ab375dbe49e6e5b6`）已完成全套 [Validation `45650024420`](https://github.com/fplj-fplj/magpie-mobile/actions/runs/45650024420) 与 [签名构建 `45650046613`](https://github.com/fplj-fplj/magpie-mobile/actions/runs/45650046613)：JVM core 668＋app 487 共 1,155 项，Android API 35 x86_64 模拟器 32 项，Go mobile 53 项、AndroidHost 61 项、真实模型目录 TLS、参数转发、官方与社区插件及 race 均通过，`android-errors.txt` 为空，签名 APK 与 `0.1.4801-androidhost.4` 核心载荷静检匹配。
- 正式发布前将对最终提交再次运行完整 GitHub Actions Validation 与签名构建，并逐项核验报告、APK 签名及载荷后再发布 Release。
