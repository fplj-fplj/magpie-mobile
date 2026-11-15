# 0.1.44 推理流空正文修复

## 现象与复现

使用 OpenAI 兼容地址 `https://aapi.ai/v1`、模型 `deepseek-v4.1-flash` 发送 `哈哈哈` 时，聊天预览在真正回答出现前显示大量 `null`。实时 SSE 响应显示以下顺序：

1. 首批事件带有 `role`、`content:null` 与 `reasoning_content`；
2. 推理阶段的多数事件继续返回 `content:null`，只在 `reasoning_content` 中携带增量；
3. 正文阶段才返回字符串型 `delta.content`；
4. 最后返回 `finish_reason:"stop"` 与 `[DONE]`。

同一账号和模型的非流式请求在 `choices[0].message.content` 返回正常字符串，因此问题位于客户端流解析，不在网关或模型输出。

## 根因

kotlinx.serialization 的 `JsonNull` 也是 `JsonPrimitive`。旧实现只检查 `delta.content` 能否转为 `JsonPrimitive`，再把其 `content` 属性拼入回答；`JsonNull.content` 是字符串 `null`，所以每个推理空事件都向界面追加一次 `null`。

## 修复

- SSE 增量只在 `delta.content` 是 JSON 字符串时接受，缺失值、`null`、数字及对象均忽略。
- 非流式降级只在 `message.content` 是 JSON 字符串时接受，防止同一协议缺陷换路径重现。
- `reasoning_content` 不进入用户可见回答；正文出现后仍保持原始增量顺序与空白。

## 回归覆盖

- 首个 `role` 事件同时带 `content:null`；
- 连续 `content:null`、非空 `reasoning_content`；
- 缺少 `content` 的推理事件；
- 非字符串正文字段；
- 空事件与真实正文混合，最终只保留正文；
- HTTP SSE 客户端端到端拼接；
- 非流式 `message.content:null` 被安全拒绝。

按项目约束，JVM 测试、APK 编译、签名和 Release 发布均由 GitHub Actions 执行，本地未运行 Gradle。
