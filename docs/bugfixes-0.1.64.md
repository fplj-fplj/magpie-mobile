# 0.1.64 流式思考别名

## 根因

应用原先只读取 SSE `choices[0].delta.reasoning_content`。部分 OpenAI 兼容中转把相同语义的思考增量放在 `delta.reasoning`，因此正文为空、思考实际存在时，聊天页和提供商连通测试会把响应误判为空。此前已经禁止把 JSON `null` 或非字符串值拼进正文，本版继续沿用该严格类型契约。

## 修复

- 每个流式事件优先读取 `reasoning_content`；主字段缺失、为 `null`、空串或非字符串时，回退到字符串类型的 `reasoning`。
- 两个字段同时存在时只选择其中一个，避免镜像内容重复显示或重复持久化。主字段为非空字符串时始终优先，别名不会覆盖。
- 空白字符串增量仍按原样派发，不擅自 trim；非字符串字段保持忽略，不会变成 `"null"`、数字或布尔正文。
- 思考别名与主字段共享单次思考字符上限和正文／思考组合上限。超限事件在派发前完整校验，不留下只提交思考或只提交正文的半事件。
- 思考回调取消仍立即中止同一事件的正文回调；聊天轮次按既有失败路径收尾，不自动重放请求。
- 思考内容继续独立写入 Room 与失败／成功预览，不拼入 assistant 正文，也不会进入下一轮模型上下文。

本版保持暖纸色、RikkaHub 页面结构、霞鹜文楷屏幕阅读版、提供商布局和官方插件机制。页面样式没有改动。

## 回归覆盖

新增 10 项核心回归：

- SSE 解析 5 项：主字段缺失或不可用时回退、双字段优先级与空白增量、非字符串别名、共用思考与组合上限、取消回调。
- 模型连通测试 3 项：纯别名响应保留思考详情、双字段不重复且正文独立、超限后仍执行工具检查。
- 聊天存储与实际 SSE 请求 2 项：思考只提交一次且不进入下一轮请求；取消后不提交答案或重发请求。

发布版本为 `0.1.64-reasoning-aliases`、versionCode 65，metadata appVersion `0.1.64`；核心保持 `0.1.3781-androidhost.3`。本机只做源码、产物与差异静态检查；全部编译和运行测试由 GitHub Actions 完成。

## 同提交验收

最终源码 `a589171b7534b60f13943f549d4040fd151a470c` 的 Validation `45109998062` 中，`regression` 与 `runtime-emulator` 均完成并成功。实际下载归档核验：

- JVM core 655＋app 482＝1,137 项，零失败、错误或跳过；新增用例的实际名称均在 XML 报告中且结果为通过。
- Android API 35 x86_64 的 23 项实际仪器用例，零失败、错误或跳过；`android-errors.txt` 为空。
- Go mobile／AndroidHost、官方与社区插件、参数转发、RPC／TLS、Hooks／MCP／SSE 与 race 证据均通过。

## 正式发布

签名发布 run `45216001353` 的 build 与 publish 均成功，发布门禁重新核对了同提交 Validation。Release `v0.1.64-reasoning-aliases-a589171b` 已公开并成为 Latest，恰好包含 APK、checksum、build-info 与 mobile-core-build-info 四项附件。

公开 APK 为 103,617,320 bytes、SHA-256 `c3b8c92bfb5172dc773447109d776ff9476ac49a32985138eddbfb4c6c89dc8a`，与 checksum、GitHub asset digest 及签名的 Actions artifact 逐字节一致。静态产物检查确认固定证书 `dee1d3f485ee17a6184178eaab318d6333fb8b67e7276146a30a03e1f9ee09d6`、ARM64-only Go／Bun ELF、内嵌核心 `70f21f9308fc8b8e1dce886c919f3fa0f2618d4af62810ad82361ec4b0d79835`、包名、versionName、versionCode 65 和源码标记均符合预期。

Release 清理按 `docs/release-policy.md` 执行：Release 页面保留 0.1.64 Latest 和长期保留的 0.1.62；0.1.63 的 Release 对象已移除，Git tag 和 Actions 运行记录均保留。0.1.62 曾在清理时被误删 Release 对象，之后从原始提交通过 [恢复构建 45220410022](https://github.com/fplj-fplj/magpie-mobile/actions/runs/45220410022) 成功重建并恢复，当前附件哈希与旧记录不同，详情见 [0.1.62 恢复记录](bugfixes-0.1.62.md#release-对象恢复2026-11-23)。
