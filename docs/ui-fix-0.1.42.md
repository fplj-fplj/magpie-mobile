# 0.1.42 提供商编辑安全区修复

Android 全面屏设备上，“添加提供商 → 高级”的固定保存按钮曾落在系统手势栏下方，只显示按钮顶部。提供商编辑页没有接管边到边窗口，却直接使用 `safeDrawingPadding`；系统栏契约与插件编辑页不一致。

本轮将全屏对话框的状态栏／导航栏图标处理提取为共享 Compose 组件。提供商编辑页与插件详情、插件登录对话框一样设置 `decorFitsSystemWindows = false`，并把对话框窗口宽高都设为 `MATCH_PARENT`。保存按钮继续使用原有 Material 按钮样式与启用条件，但放入页签滚动内容末尾，让系统栏、大字体和不同厂商的对话框窗口都无法把操作区裁到屏幕外；暖纸色／深色主题、文楷字体与页签样式保持不变。

Android instrumentation 在插件流程后进入提供商页、打开“添加提供商”、切换到“高级”，检查“保存连接”按钮完整位于屏幕内，并保存 `provider-editor-advanced.png`。编译、JVM、Go 和模拟器验证全部由 GitHub Actions 执行；本地仅做源码与补丁检查。

## 实际验证

最终修复提交：`1f9982550f5042c96d212a8d37da29352a857b76`。

- [Build and release](https://github.com/fplj-fplj/magpie-mobile/actions/runs/43693920521)：APK 构建、固定签名、ARM64 载荷及 16 KB 检查通过，并自动发布。
- [Validation](https://github.com/fplj-fplj/magpie-mobile/actions/runs/43693920526)：JVM regression、Go 安全测试、真实官方插件集成、race 检查及 Android API 35 运行全部通过。
- 签名 APK：[0.1.42-provider-insets Release](https://github.com/fplj-fplj/magpie-mobile/releases/tag/v0.1.42-provider-insets-1f998255)。
- Android 运行截图与报告 artifact：`14153976979`。实际检查 `provider-editor-advanced.png`，保存按钮完整显示；UIAutomator 记录文本边界为 `bottom=1599`、设备高度 `2400`，与底部系统手势栏有明确间距。
