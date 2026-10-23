# DouAi 0.2.14 本地候选：自动滑动与长按修复

本地候选版本 **0.2.14 / 115**，包名 `io.github.yudigaga.douai`。本候选仅在本机构建与验证，未上传 GitHub 或 LSPosed，未创建发布标签。

## 修复内容

- 自动滑动钩子晚于抖音页面选中事件安装时，通过宿主读取当前 holder 或 aweme 恢复当前视频状态，并确认 holder 仍属于前台页面。
- Activity 暂停后不允许迟到的 holder 读取重新激活自动滑动。
- 配置长按动作会消费本次抬手事件，避免抖音继续处理原生释放动作。
- 播放完成发生在触摸期间时仍按原行为跳过，不会在松手后补翻页。

## 本地验证

严格验收使用抖音 **40.8.0 / 400801** 宿主 APK。完整运行 `:app:testDebugUnitTest`、`lintRelease` 和签名 Release 构建，另通过 Windows ICU 占位符检查。

- **2,215 项测试，211 个套件**：0 失败、0 错误、0 跳过。
- Release Lint：0 错误、56 条已批准警告，增量预算通过，无新增警告。
- APK 包名、版本、原长期证书 v3 签名、APK 合同、宿主 DEX 合同和 16 KiB 对齐检查通过。
- 手机安装、LSPosed 运行与真实手势行为未验证。

封存验收目录：`artifacts/release-verification/20261022T100655Z-b966f965/`。清单状态为 `verified-local`、`published=false`，源码快照基于 `59e02085c3fd4f7b972aca8d224d02665df06c7c`，验收时工作区包含未提交改动。

## APK

[DouAi-0.2.14-release.apk](../artifacts/DouAi-0.2.14-release.apk)，**4,853,033 字节**，SHA-256：`0B7B499D076415DF4FE0B8DEE5DE2803BB31C51E0A622DC804156212DA7EA616`。

签名证书 SHA-256：`FAE67C134F4C4FA73B8E34487C47A8C7F3E7C34B840BC245AFAC51585B7DFD47`。本地验收清单与证据摘要见上述目录的 `manifest.json` 和 `SHA256SUMS.txt`。
