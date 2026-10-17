# 0.1.75：升级版本并重新打包

版本：`0.1.75 / 76`。按用户要求递增版本号，基于当前源码重新构建正式 Release，用于更新此前本地交付的 `0.1.74 / 75`。本轮已完成正式构建、全量本地回归、签名和归档核对，尚未进行真机验收。

## 保留的行为

- 生产源码保持 0.1.74 的两轮黑边回退结果：仅沿用原有单一黑边更新回调，0.1.69 的创建绑定和 0.1.73 的 holder 恢复处理继续撤回。回退范围及此前本地验证见 [0.1.74 记录](release-0.1.74.md)。
- 保留官方下载和第三方下载分类选择、图片与动态视频多选、实况识别、来源字段标记及各自独立的默认行为。
- 保留评论回复、素材识别、独立回复悬浮提示及其他已有功能。
- 按用户选择保持一屏一条、保留完整画面，接受视频画幅比例所需的必要留白，不新增裁剪、可变页高或固定负页距。

本版没有新增运行行为修改，不将重新打包视为视频切换黑带修复。此前分析中，相邻页面外框已在采样日志中相接，但截图中的黑带尚未与具体作品源尺寸和实际渲染矩形对应；分析范围与证据边界见 [视频切换衔接分析](2026-10-17_reverse-douyin-video-junction-report.md)。

## 本地构建与验证

143 个套件、1325 项测试全部通过，0 失败、0 错误、0 跳过，包含原有视图行为 5 项、宿主 DEX 集成 3 项与最终归档 APK 契约 3 项。Release Lint 为 0 错误、44 警告，正式 Release 构建通过。

成品包名为 `io.github.yudigaga.douai`，实际版本为 `0.1.75 / 76`，启用 R8，未开启调试。APK v3 签名验证通过，证书与 0.1.74 归档一致。成品 DEX 中未发现已撤回的黑边创建诊断、holder 恢复诊断和 `black_space_recovery` 标记；生产黑边实现仍与 `9b60a36` 基线一致，保持原有单一回调。

本轮先完成 Release Lint 与正式构建，再归档并将 `DOUAI_TEST_APK` 指向原始宿主 APK、`DOUAI_VERIFY_RELEASE_APK` 指向最终归档，执行全量测试：

```powershell
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
.\gradlew.bat :app:lintRelease :app:assembleRelease --console=plain
Copy-Item -LiteralPath 'app/build/outputs/apk/release/app-release.apk' -Destination 'artifacts/DouAi-0.1.75-release.apk'
$env:DOUAI_TEST_APK = (Resolve-Path '抖音-40.6.0_40609900_20260922 (400601).apk').Path
$env:DOUAI_VERIFY_RELEASE_APK = (Resolve-Path 'artifacts/DouAi-0.1.75-release.apk').Path
.\gradlew.bat :app:testDebugUnitTest --console=plain
```

环境与通用签名说明见 [正式版构建流程](releasing.md)。上述结果对应 0.1.75 最终归档；0.1.74 的归档和历史记录继续保留。

## 构建产物与记录

- APK：`artifacts/DouAi-0.1.75-release.apk`，4,206,287 字节。
- SHA-256：`21D72E1159025837127D343727CD56EFA76FB4C70EE241DE70F31126950C0E63`。
- 签名证书 SHA-256：`FAE67C134F4C4FA73B8E34487C47A8C7F3E7C34B840BC245AFAC51585B7DFD47`。
- 本地验证记录：`artifacts/DouAi-0.1.75-release.validation.json`。
- 本地构建日志：`artifacts/release-075-build.log`；全量测试日志：`artifacts/release-075-full-tests.log`。

最终归档、Release 构建输出和全量测试使用的 APK 哈希一致。未进行真机安装或真实宿主行为验收。安装后需完全退出并重新打开抖音，使新版本模块加载。

正式下载：[GitHub](https://github.com/yudigaga/DouAi/releases/tag/v0.1.75) · [LSPosed](https://github.com/Xposed-Modules-Repo/io.github.yudigaga.douai/releases/tag/76-0.1.75)。沿用既有正式签名，可覆盖升级；APK 关于页与两个仓库均保留[免责协议](../DISCLAIMER.md)。
