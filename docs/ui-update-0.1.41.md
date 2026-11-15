# 0.1.41 屏幕阅读与网关开关

本轮根据横屏截图中启停按钮分离、禁用按钮文字不可见和字体过细的反馈调整。保留暖纸色、反相状态卡和陶土橙，采用工具优先布局：状态在左、唯一开关在右，说明与端点位于其下；启停状态变化不移动操作位置。

开关读取实际服务状态。启动与恢复时显示已开启，仍可关闭；停止回收期间保持开启且禁用，退出完成后变为关闭。失败／已停止时可重新开启。不另外维护可能与核心冲突的 UI 开关状态。

全原生界面的 15 个 Material 文本角色使用霞鹜文楷屏幕阅读版，包括按钮、标签、底部导航、输入框和对话框；端点、JSON 参数与设备码也使用同一字体。顶部主页面的「Magpie Mobile」保留 Poppins，二级页标题仍为文楷。系统通知、系统文件选择器及外部浏览器由对应系统／应用绘制。

## 字体来源

- 官方项目：[lxgw/LxgwWenKai-Screen](https://github.com/lxgw/LxgwWenKai-Screen)。上游说明屏幕版将较厚的 Medium 笔画作为 Regular，并调整 Android 屏幕度量。
- 固定发布：[v1.530 / LXGWWenKaiScreen.ttf](https://github.com/lxgw/LxgwWenKai-Screen/releases/tag/v1.530)，提交 `34f2c5602eb51d7455a0032c56ea9b0029edab53`。
- 文件大小：26,095,590 字节；SHA-256：`abd13364163e3cd84372b333f2fdcee9efb848634668f1c0ce5788dcd059ee61`。保留完整原始 TTF，离线加载，无子集化。
- 上游仅提供屏幕版 Regular 文件；正文使用它本身的较厚笔画，标题与操作文字由 Compose 合成加粗，未把同一文件伪装成独立 Bold 字重文件。
- OFL 与合入的 Roboto 打底字形 Apache 许可随包放在 `assets/licenses/`。

## 验证

本地只做字体哈希、TTF／cmap／字重及许可核对和源码检查。编译、JVM、Go 及 Android 运行全部由 GitHub Actions 执行。

Android instrumentation 使用唯一开关启动真实 Go/Bun 核心，在横屏与竖屏保存关闭／运行截图；完整插件流程结束后以同一开关关闭核心，核对开关状态、进程与端口清理。参数、登录与 1.3 倍字体页面也保留截图，供检查字体覆盖与换行。
