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

发布由独立 Build and release 流程执行，APK 编译与固定签名／载荷检查成功即进入 Release。Validation 保存运行结果与截图，失败不会阻止已经构建成功的安装包发布。核心载荷与 Bun 依赖按固定来源缓存。

## 已完成的 Actions 验证（2026-11-15）

代码提交：`84554557a9ac49e57f59bf073dfc88eb0c0a3ec6`。签名候选已自动发布至 [0.1.41-reader-switch Release](https://github.com/fplj-fplj/magpie-mobile/releases/tag/v0.1.41-reader-switch-84554557)。

| 流程／项目 | 实际结果 |
| --- | --- |
| [首次构建与发布](https://github.com/fplj-fplj/magpie-mobile/actions/runs/43687912271) | build、publish 均通过；发布时独立回归仍在运行 |
| [命中缓存的构建与发布](https://github.com/fplj-fplj/magpie-mobile/actions/runs/43688502899) | ARM64 核心与 Bun 缓存命中，跳过 setup-go 和核心编译；build 用时 83 秒，publish 通过 |
| [独立 Validation](https://github.com/fplj-fplj/magpie-mobile/actions/runs/43687912256) | regression、runtime-emulator 均通过 |
| JVM | core 404 项、app 162 项；失败／错误／跳过均为 0 |
| Go | legacy mobile 35 项、Android host 25 项通过；access 测试及真实宿主 smoke 通过 |
| 官方插件集成 | 社区 tools-calc、word-guard、PowerContext 及 GitHub 插件安装通过；真实工具、中间件、Hooks、更新和 race 检查通过 |
| Android API 35 x86_64 | 真实 Go／Bun、浏览器／原生共用配置、插件登录与执行通过；唯一开关完成启动和停止，关闭后进程和端口清理通过 |
| 界面证据 | 横竖屏关闭／运行、停止后状态、原生参数／登录及 1.3 倍字体截图已取回检查；短横屏可完整显示状态卡 |

Android 报告 artifact：`14151079010`；JVM 报告：`14151655538`；Go／插件／race 报告：`14150334906`，均对应上述代码提交。ARM64 发布载荷通过 ELF／PIE／16 KB、打包哈希与固定签名校验；本轮 Android 运行证据来自 x86_64 模拟器，ARM64 真机验收仍单独记录。

缓存命中流程发布的 APK 已实际下载核对（102,353,613 字节）：

- APK SHA-256：`2561ea212cd03828abc4813bb2eb7a12bd71d21cbb23936574f3130c1cd5b3fc`，与 Release 的 `.sha256` 附件一致。
- 包内 ARM64 核心 SHA-256：`52f9b2599b120894780162934ce0120f174b28b3ee14db7b73f2aee1b93d5ca5`，与 `build-info.txt` 一致。
- 包内屏幕版字体（AAPT 重命名为 `res/UJ.ttf`）与上述官方原始字体哈希一致。
- 固定签名证书 SHA-256：`dee1d3f485ee17a6184178eaab318d6333fb8b67e7276146a30a03e1f9ee09d6`，Actions 实际签名检查通过。

本地仅做源码、YAML／shell 语法、字体与已发布 APK 的文件检查，没有运行 Gradle 或 Go 编译。
