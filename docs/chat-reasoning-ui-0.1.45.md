# 0.1.45 思考过程与聊天布局

## 数据链路

DeepSeek-V4.1 Flash 等推理模型会在 SSE 的 `delta.reasoning_content` 中持续返回思考增量，随后才在 `delta.content` 中返回正式回答。0.1.45 使用两个独立累积器：

- `reasoning_content` 只更新思考卡与成功 assistant 的 `reasoning` 字段；
- `content` 只进入正式回答、JSONL/Room 的 `text` 字段和下一轮上下文；
- 任一字段为 JSON `null` 或数字时都忽略，不生成字符串 `"null"`；
- 非流式 405/501 降级同样从 `message.reasoning_content` 提取思考内容。

思考内容不会拼进 assistant 正文，也不会进入后续模型上下文。旧 JSONL 与 Room v1 消息缺少思考字段时按空值读取。

## 展示与交互

聊天测试页参考 RikkaHub 主聊天页面的层级，改为用户消息靠右、助手消息靠左，模型选择、输入框和发送按钮组成底部输入面板。助手消息的思考卡位于正式回答之前：

- 思考流进行中自动展开，最多显示 128dp 的实时预览并滚动到最新内容；
- 正式回答开始或本轮完成后自动折叠；
- 点击卡片标题行可展开或收起，历史思考内容最多显示 280dp 并支持滚动；
- 正文与思考内容均可长按选择复制；
- 颜色继续使用暖纸色、陶土橙强调和霞鹜文楷屏幕阅读版。

## 持久化与隐私

Room 从 v1 迁移到 v2：

```sql
ALTER TABLE `message` ADD COLUMN `reasoning` TEXT NOT NULL DEFAULT ''
```

迁移不清库、不改写旧消息。成功 assistant 的思考内容保存在 App 私有数据库；失败轮次的思考 partial 只在内存中显示，不写入成功上下文。思考内容不会写入日志、错误摘要、模型请求或配置备份。

## 验证边界

GitHub Actions 覆盖以下行为：

- SSE 同一事件或连续事件中的 reasoning/content 分离与 `null` 忽略；
- 流式客户端、流式会话和非流式降级的思考回调；
- JSONL 新字段往返与旧行兼容；
- Room 查询、字符上限与成功上下文仍只含正式正文；
- ViewModel 成功历史可见、失败 partial 可预览、后续上下文不含思考内容；
- KSP 导出 schema、APK 构建、签名和载荷校验。
