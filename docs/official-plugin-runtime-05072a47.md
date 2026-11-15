# 官方插件运行验收：05072a47

已通过提交：`05072a47aab4099b8e8d4876d268c2c06e59c6e1`。本文记录已取得的证据，不把 ARM64 ELF 检查当作 ARM64 实际运行。

- [完整 GitHub Actions](https://github.com/fplj-fplj/magpie-mobile/actions/runs/43682069460)：build、runtime-emulator、runtime-arm64-capabilities、publish 均成功；capabilities job 只检查宿主条件。
- [固定签名候选发布](https://github.com/fplj-fplj/magpie-mobile/releases/tag/v0.1.40-official-plugins-05072a47)：`0.1.40-official-plugins`／code 41，prerelease。
- 核心：官方 Magpie `v0.1.3781`、commit `b52c711142a662fe18fedf5a80cb1e4aa94bce62`，`nogui,androidhost`，版本 `0.1.3781-androidhost.2`；Go `1.26.8`，Bun `1.4.2`。

| 产物 | SHA-256 |
| --- | --- |
| 候选 APK，96,198,014 字节 | `a34d3a364b9b0b6b3089cf03e35cfbdb1a48e9b1eda98c322f572293793094d1` |
| ARM64 `libmagpie_exec.so` | `52f9b2599b120894780162934ce0120f174b28b3ee14db7b73f2aee1b93d5ca5` |
| ARM64 `libbun_exec.so` | `f2970336bfccf36e4e8a408b25c627122f210e941672f184198c31f4dbd377a0` |
| 固定签名证书 | `dee1d3f485ee17a6184178eaab318d6333fb8b67e7276146a30a03e1f9ee09d6` |

生产 APK 两个 ARM64 载荷通过 ELF、PIE、Android linker 与 16 KB 对齐检查，打包核心哈希与本轮重建核心一致。

## Linux 完整核心与 Bun

官方 API 安装以下固定社区包／仓库，并通过真实核心请求执行：

- `@magpie-community/tools-calc@0.1.0`：MCP 工具执行，`0.1+0.2` 返回精确结果 `3/10`。
- `@magpie-community/middleware-word-guard@0.1.1`：请求拒绝、响应变换与 SSE 事件变换。
- `@magpie-community/gateway-powercontext@0.2.1`：真实 HTTP 召回、会话关联、响应完成后写入本机测试记忆服务。
- `github:guotree/magpie-websearch#3ff2a8deec6742d1f74e3a65c3b006405ac27615`：安装与加载。

测试插件另外覆盖提供商登录、模型和非流式／流式推理、工具参数、同步中间件、异步 Hooks 的请求改写、onResponse、异常、超时及客户端断开取消。实际 Go 生成的授权桥在 Bun 执行，跨 origin 重定向不泄露专用 Key；停用、删除、重启和显式恢复均验证。插件更新与 race 回归通过。

社区包的本机测试服务结果和第三方个人账号服务端验收分别记录。

## Android x86_64 API 35

测试使用已安装 APK 自身的 Android Go／Bun 载荷，真实管理与网关请求，未替换为假客户端。

`OfficialPluginsInstrumentedTest.officialPluginsExecuteInInstalledAndroidApk` 的 XML：**tests=1、failures=0、errors=0、skipped=0**，suite 时间 44.969 秒。

覆盖真实提供商请求、API Key／模拟 OAuth／取消、工具调用、moejs 中间件和 Bun 异步 Hooks、非流式与实时 SSE。标准 DocumentsProvider 提供 SAF 单文件与包含相对文件及 npm 依赖的目录，导入后实际执行；官方浏览器删除后成功重读回收私有副本。JVM 回归还确认配置文件损坏或磁盘清单有更新引用时保留来源文件，同一目录有其他已登记脚本时也不删除。

原生界面操作包含插件列表、详情、JSON 参数保存、API Key 登录与返回。浏览器与原生双向读取同一 options。后台返回、Activity 重建保持核心会话及已保存参数；1.3 倍字体截图确认换行、搜索、列表和底部导航可见。

停止结果为 `PASS: core and Bun processes stopped`，进程列表中无两个运行载荷，3425／3430 均不再接受连接。运行期间核心 `restartCount=0`。

同轮 Actions artifacts：

| Artifact | ID | 内容 |
| --- | --- | --- |
| `android-plugin-runtime-05072a47aab4099b8e8d4876d268c2c06e59c6e1` | `14148627723` | XML、测试日志、原生页／大字体截图、请求结果、停止证据 |
| `signed-release-05072a47aab4099b8e8d4876d268c2c06e59c6e1` | `14148822448` | 固定签名 APK、证书、核心哈希及构建／smoke 证据 |
| `mobile-core-evidence-05072a47aab4099b8e8d4876d268c2c06e59c6e1` | `14148921925` | Go 边界回归、官方插件 smoke、race 及构建来源 |
| `regression-reports-05072a47aab4099b8e8d4876d268c2c06e59c6e1` | `14148578874` | JVM 管理契约、未知操作对账、导入日志与提供商写入预算回归 |

## 尚缺证据

生产 ARM64 载荷的实际 Android 执行还没有通过报告。`ubuntu-24.04-arm` runner 无 `/dev/kvm`，也没有注册的真机 runner。官方 QEMU 37.2.13 的 API30 ARM64 软件模拟已实际进入内核与 init，但 app_process64 在 libcodec2_vndk.so 的全局构造期间发生 SIGSEGV，APK 测试未开始。`ranchu`／`virt` 与关闭 GPU 的实验均未完成启动；[完整崩溃日志实验](https://github.com/fplj-fplj/magpie-mobile/actions/runs/43680824377)保存了 backtrace。官方 API29 镜像的[最后一次独立实验](https://github.com/fplj-fplj/magpie-mobile/actions/runs/43683164233)正在验收；未成功前不声明 ARM64 三类插件运行通过。

真实第三方个人账号授权、供应商服务、具体包的 Linux 原生依赖及 Android 设备电池／网络稳定性仍需对应环境验收。
