# 0.1.47 会话、连通测试与状态修复

应用提交：`b01870ce68faa699d456c18459074e9796f763ae`，版本 `0.1.47-bugfixes`／versionCode 48。保留现有暖纸色、陶土橙、RikkaHub 页面结构和霞鹜文楷屏幕阅读版；顶部品牌字体及官方三类插件机制不变。

## 已修复的触发场景

| 触发 | 原因与修复 |
| --- | --- |
| 删除有历史消息的会话失败 | 外键使用 RESTRICT，不能直接删除父会话。在事务中先拒绝 RUNNING，再依次删除消息、操作和会话；任一步失败全部回滚，旧归档继续只读。无需数据库迁移。 |
| 删除当前会话后草稿或失败回答串用 | 切到回退会话后没有恢复其草稿，也没有清理被删会话的失败正文及思考预览。现在恢复正确草稿并清理预览；删除其他会话时保持当前内容。 |
| 空响应的模型连通测试误报通过 | 非流式仅检查 message 对象存在，流式仅检查收到 DONE。现在要求过滤空白和控制字符后，至少有有效正文或思考文本；纯思考仍通过，合法工具调用继续允许空正文。 |
| 能力测试普通请求接收超大响应 | 与工具检查共用 256 KiB 有界读取，同时检查声明长度和实际字节数；超限失败，不返回截断成功。 |
| 已保存 root 提供商只改名称却改变接口路径，目录模型也被丢弃 | 新输入 bare origin 的默认 `/v1` 与已保存 explicit root 不同。回开时用 `/chat/completions` 表单保留 root；目录回调明确比较请求 canonical base 与当前表单归一值，避免再次补 `/v1`。同源、过期响应检查及仅一次路径纠正继续生效。 |
| 断线后旧用量请求回填，或连续刷新结果倒序 | 清理时取消任务并提升代次，成功和失败均核对当前代次。核心 owner 变化才清理；同 owner 的 Activity 重建保留洞察及在途读取。 |
| 非法插件地址在被核心拒绝前已进入 Android 保存状态 | 核心客户端与 ViewModel 复用同一 spec 校验，在保存操作标记前拒绝 URL 凭据；恢复时清除旧版非法标记，错误不回显输入。 |
| 插件安装、移除后市场仍显示旧按钮状态 | 用最新插件快照同步 installed 与 matchedSpecs；刷新、编辑及会话切换使旧市场读取失效，清理被接管读取的 loading。 |
| 普通聊天错误响应拖延状态码处理，成功响应可无限读取 | 非 2xx 直接抛出带 HTTP 状态的固定异常，不等待错误响应体。成功响应上限 4 MiB，同时限制 Content-Length 与 chunked 实际字节数，UTF-8 在有界读取后统一解码。 |

## GitHub Actions 验证

全程未在本地执行 Gradle、Go 或 Android 编译与运行测试。本地仅做源码审查、差异检查及 Python 静态／资源检查。

[同提交 Validation](https://github.com/fplj-fplj/magpie-mobile/actions/runs/44579929141) 已完整通过，下载报告核实如下：

| 检查 | 结果 |
| --- | --- |
| 核心 JVM | 428 项，0 failure／error／skipped |
| 应用 JVM | 193 项，0 failure／error／skipped |
| Go mobile 回归 | 35 项通过 |
| Go Android 宿主回归 | 25 项通过 |
| Linux 官方／社区插件集成 | 通过：提供商／工具、同步中间件、异步 Hooks 与固定社区插件 |
| 插件宿主 race | 通过 |
| Android API 35 x86_64 实际安装运行 | 1 个完整仪器测试，0 failure／error／skipped，测试套件耗时 92.741 秒 |

本轮新增 31 项 JVM 测试方法，共 621 项 JVM 回归。JVM 报告 artifact ID：`14558617394`；核心运行证据：`14558942592`；Android 运行证据：`14558482849`。

Android 报告确认三项模型能力均通过，真实 Go＋Bun 的提供商登录、工具执行、中间件、异步 Hooks、普通响应和流式响应通过，停止后核心与 Bun 进程完成清理。全屏编辑器坐标为 `Rect(0, 0 - 1080, 2400)`；保存按钮为 `Rect(550, 2180 - 1038, 2306)`。键盘顶部 y=1517，发送按钮下边缘 y=1462；页面几何连续 3 次采样稳定后截图，现有安全区与键盘布局未回归。

[同提交 ARM64 签名候选构建](https://github.com/fplj-fplj/magpie-mobile/actions/runs/44580048078) 设置 `publish_release=false`，已通过并只归档到 Actions。固定签名证书、核心载荷哈希与 APK 运行载荷检查通过；签名候选 artifact ID：`14557799078`。

## 可用版本发布

完整 Validation 与签名候选均通过后，才启动同提交的[正式签名与发布流程](https://github.com/fplj-fplj/magpie-mobile/actions/runs/44581607953)，build 与 publish 均成功。[正式 Release](https://github.com/fplj-fplj/magpie-mobile/releases/tag/v0.1.47-bugfixes-b01870ce) 已核实为非草稿、非预发布及 Latest，Release ID：`451714399`。

- APK：`magpie-mobile-0.1.47-bugfixes.apk`，102,665,281 字节。
- APK SHA-256：`37d6ae1e246705f11fc05fd37932f9228cb5d480c955fecba0846468687d96dc`。
- Release 附件包含 APK、对应 SHA-256、build-info 与核心构建来源，共 4 项。
- 本轮中间候选没有创建 Release；正式版本基于通过全部验证的同一应用提交。

## 后续独立巡查范围

本轮 ordinary/tool 读取限制不等于完整 SSE 传输限制。共享流式层仍需独立处理无换行超长行、持续心跳、累计正文／思考预算，以及 ViewModel 真正清理时的阻塞读取取消与持久化收尾。当前没有用户停止生成按钮；正常旋转通常保留 ViewModel，不应被描述为每次旋转都取消发送。

Android 运行结果需要区分 API 35 x86_64 模拟器与 ARM64 生产 APK 的静态载荷检查；后者不能替代 ARM64 真机运行。
