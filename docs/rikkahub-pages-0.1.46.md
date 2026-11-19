# 0.1.46 提供商、模型选择与连通测试

应用提交：`d14cfedb314902f39595c21f6bba530f97630363`，版本 `0.1.46-rikkahub-pages`／versionCode 47。界面参照 [RikkaHub `90ef90b7`](https://github.com/rikkahub/rikkahub/tree/90ef90b765105a775e1c3292a5d39f4ddd45be27) 的页面结构，继续使用 Magpie 的暖纸色、陶土橙与霞鹜文楷屏幕阅读版。顶部品牌文字保留 Poppins。

## 页面与行为

- 添加提供商先显示连接类型面板；详情分为「配置／模型／高级」页签，连接、凭据、模型与传输设置分组呈现。
- 提供商与聊天共用模型选择面板：搜索、提供商分组、折叠、单选和多选。面板直接完整展开，底部多选确认始终进入可见区域。
- 连通测试使用已保存的提供商模型路由，依次检查非流式响应、完整 SSE 和工具调用；分别展示状态、耗时、回复详情与可折叠的模型思考内容。工具参数只校验，不执行。
- 编辑器使用非浮动全屏窗口，修复原浮动 Dialog 的垂直偏移与底部按钮越界；保留固定页脚样式，并处理状态栏、手势栏、键盘和返回操作。
- 聊天页统一由外层处理 IME inset，键盘打开时收起底部导航。标题、模型选择器与发送按钮保持可见，输入框不再与键盘之间留下大片空白。
- 仅「模型提供商」与「更多」主页保留原生／浏览器编辑选择，二级页直接进入对应功能。完整 CLI Android 宿主、三类官方插件、流式 `null` 修复与独立思考内容保留。

## GitHub Actions 验证

[同提交 Validation](https://github.com/fplj-fplj/magpie-mobile/actions/runs/44566737319) 已通过：

| 检查 | 结果 |
| --- | --- |
| 核心 JVM | 415 项，0 failure／error／skipped |
| 应用 JVM | 175 项，0 failure／error／skipped |
| Go Android 宿主与既有边界回归 | 通过 |
| Linux Go＋Bun 官方插件集成 | 通过：提供商／工具、同步中间件、异步 Hooks，以及固定社区插件 |
| 插件宿主 race 回归 | 通过 |
| Android API 35 x86_64 安装运行 | 1 个完整仪器测试，0 failure／error／skipped，79.301 秒 |

Android 测试操作提供商真实目录模型，验证取消选择、确认、重新打开与重新选择，并通过三项模型能力检查。还覆盖添加类型、配置／高级页、未保存配置提示、插件参数与登录、横竖屏、1.3 倍字体、后台返回、Activity 重建及网关停止后的进程清理。

窗口坐标证据以 1080×2400 显示区域为基准：

- 提供商编辑器：`Rect(0, 0 - 1080, 2400)`。
- 完整页脚：`Rect(42, 2089 - 1038, 2400)`；保存按钮：`Rect(550, 2180 - 1038, 2306)`，完整位于手势栏上方。
- 键盘顶部 y=1517；聊天面板底部 y=1496，模型选择器 y=341…467，发送按钮 y=1336…1462。连续 3 次采样几何稳定后才截图；发送按钮与键盘间距 55px，约 21dp。
- 最终网关状态 `STOPPED`、`restartCount=0`；Go 与 Bun 进程清理通过。

实际截图、坐标采样、XML 与停止报告位于该次运行的 `android-plugin-runtime-d14cfedb314902f39595c21f6bba530f97630363` artifact（ID `14553855109`）。JVM 报告 artifact ID 为 `14551718850`。全程未做本地 Gradle／Go／Android 编译。

## 签名 APK 与发布

完整 Validation 通过后，再运行同提交的 [签名构建与发布](https://github.com/fplj-fplj/magpie-mobile/actions/runs/44569127591)，`build` 和 `publish` 均通过。[正式 Release](https://github.com/fplj-fplj/magpie-mobile/releases/tag/v0.1.46-rikkahub-pages-d14cfedb) 为非草稿、非预发布，标记为 Latest。

- APK：`magpie-mobile-0.1.46-rikkahub-pages.apk`，102,659,113 字节。
- APK SHA-256：`37af694146fd6269ed052c5ec36af166ccba7cc0560aac5a48348294ba977612`。
- 同轮签名产物 artifact ID：`14553751476`；APK、校验文件与构建来源同时保留在 Release。
- 本轮中间修复没有创建 Release。

## 验证范围

Android 实际运行证据来自 x86_64 API 35 模拟器。ARM64 生产 APK 另行通过签名、打包、载荷哈希、PIE 与 16KB 对齐检查；这些检查不等同于 ARM64 真机运行。第三方个人账号、各厂商输入法、原生二进制依赖插件及设备后台策略仍按对应环境验收。
