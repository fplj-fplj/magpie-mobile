# Release 发布规则

`main` 上的每次应用或核心改动都会运行 `Build signed APK (manual release)`。构建 job 完成固定源码、ARM64 核心、APK 编译、固定签名、载荷哈希和运行时探测后，会把签名 APK 与证据保留在对应 Actions run。

普通 push 不创建 GitHub Release。准备发布一个确认可用的版本时，手动运行该工作流并勾选 `publish_release`：

1. 目标提交的最新可信 `Validation` attempt 必须完成并成功，其中 `regression` 与 `runtime-emulator` 均实际执行成功；不同提交、旧成功、缺失或跳过的必需 job 均不满足条件；
2. 用目标提交运行签名构建，确认全部签名与载荷检查通过，核对候选产物及完整运行报告；
3. 勾选 `publish_release` 手动触发正式构建与发布；人工授权和同提交运行验收须同时满足；
4. publish job 只使用本轮构建并校验过的产物，先创建 `v<versionName>-<sha8>` 草稿，上传并核对全部四项附件的名称、大小与 SHA-256。附件完成后再次检查同一提交的最新 Validation run／attempt，只有它仍与 build-info 记录的已通过 attempt 一致，才会立即公开。草稿可显式重试补传；既有公开 Release 不覆盖附件或移动 tag。
5. `latest_mode=auto` 只在新版本号严格高于当前 Latest 时提升 Latest；恢复旧版本或同版本不同提交时保留当前 Latest。`latest_mode=latest` 与 `keep-current` 可显式覆盖；`keep-current` 在不存在 Latest 时失败。公开前和公开后均读取 `/releases/latest` 核对实际状态；已有公开版本还必须不是 prerelease，状态不符时失败而不是报告虚假成功。

`Validation` 继续独立运行 JVM、Go、插件和 Android 模拟器回归。验证失败会保留报告，也不会删除已经通过签名与载荷检查的 Actions 产物；发布门禁会拒绝把未验收候选放到 Release。Release 说明与 build-info 记录实际通过的 Validation attempt 链接。

Release 页面只保留当前可用 Latest，以及明确需要长期保留的正式版本。同一功能的中间修复、失败构建和候选 APK 不再保留在 Releases；历史构建仍可从 Actions run 追溯。

## 受限 Release 清理

清理只能通过 `scripts/cleanup-releases.py` 执行，必须逐个列出需要保留的 Release tag，并包含当前 Latest。脚本默认只打印 `KEEP`、`DELETE` 和 `DRY RUN` 计划；没有显式确认不会修改任何对象。

当前应保留：

- Latest：`v0.1.66-update-center-7890bf89`
- 长期版本：`v0.1.62-chat-session-clipboard-fixes-90971632`

0.1.66 发布后，清理 dry-run 确认只会移除 0.1.65；执行前已下载核对两项保留 Release 的 APK、SHA-256 附件、GitHub digest 与附件大小：

```sh
GH_TOKEN=... python3 scripts/cleanup-releases.py \
  --repository fplj-fplj/magpie-mobile \
  --keep v0.1.66-update-center-7890bf89 \
  --keep v0.1.62-chat-session-clipboard-fixes-90971632
```

清理已完成。下次执行时检查 dry-run 计划、Latest 状态以及两个保留 Release 的 APK/checksum 可下载且一致后，再执行：

```sh
GH_TOKEN=... python3 scripts/cleanup-releases.py \
  --repository fplj-fplj/magpie-mobile \
  --keep v0.1.66-update-center-7890bf89 \
  --keep v0.1.62-chat-session-clipboard-fixes-90971632 \
  --apply --confirm 'DELETE RELEASE OBJECTS'
```

如果当前 Latest 不在保留列表、任一保留 tag 不存在、存在未保护的草稿，或清理期间 Latest 发生变化，脚本在删除前失败。脚本只调用 Release object 的 `DELETE /releases/{id}`，不调用 Git ref／tag 删除接口，因此不删除任何 Git tag。中途失败后重新运行默认 dry-run 核对剩余对象。
