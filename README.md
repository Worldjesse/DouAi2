# DouAi

抖音增强模块，面向抖音 `com.ss.android.ugc.aweme` 40.6.0，使用 libxposed API 102。

作者：**yudigaga**。当前版本：**0.1.63** / versionCode **64**。

- [源码仓库](https://github.com/yudigaga/DouAi)
- [正式版下载](https://github.com/yudigaga/DouAi/releases)
- [LSPosed 模块仓库](https://github.com/Xposed-Modules-Repo/io.github.yudigaga.douai)

正式包名：`io.github.yudigaga.douai`。

## 0.1.63 更新

新增配置导入/导出：JSON 文件备份、默认不含接口密钥、合并或覆盖预览、确认后整批应用。发布前修复静止大树的异步控件检测，并包含 0.1.61、0.1.62 的自动回复、语音、下载任务及设置优化。按用户要求跳过真机验证，本地检查范围和限制明确记录。

[完整更新与验证](docs/release-0.1.63.md) · [发布前复核](docs/review-0.1.63.md)。

## 0.1.62 本地更新

完成后续优化：Feed 按预算检测视图变化，减少稳定页面重复工作；新增下载任务页、进度、取消及整体超时；回复和评论设置独立管理配置迁移、表单与关闭生命周期。沿用 0.1.61 的回复和语音修复。本版仅本地构建，按用户要求跳过真机验证，未上传 GitHub 或 LSPosed。

[本地构建与验证记录](docs/release-0.1.62.md)。

## 0.1.61 本地更新

补齐自动回复图片读取的整体超时与任务取消，统一系统语音的语速、音调校验和有效值展示，并将评论、私聊文本转语音纳入适配诊断。本版仅本地构建，未上传 GitHub 或 LSPosed。

[本地构建与验证记录](docs/release-0.1.61.md)。

## 0.1.60 更新

APK 关于页和仓库增加免责协议，明确项目仅用于学习与技术研究，用户的应用及使用行为和结果与本项目及作者无关。包含异步语音上传重复提交修复、语音失败转文本和历史发送方式标记，以及第三方下载、自定义目录、系统/第三方语音与离线帮助。

[完整更新与验证](docs/release-0.1.60.md)。沿用正式包名、长期发布签名和 R8 配置。

## 免责协议

本项目仅用于学习与技术研究。用户的应用、配置、二次开发，以及使用本项目生成、下载、发送或发布内容的行为和结果，均由用户自行决定并承担责任，与本项目及作者无关。完整责任边界见 [免责协议](DISCLAIMER.md)，APK 关于页也可查看。

## 安装与使用

需要 Android 9 或以上，以及支持 libxposed API 102 的 LSPosed。当前规则针对抖音 40.6.0，不保证其他版本适配。

1. 下载 Releases 中的正式签名 APK 并安装。
2. 在 LSPosed 中启用 DouAi，作用域只选择抖音。
3. 完全退出并重新打开抖音，在抖音设置中的 DouAi 入口配置功能。

模块应用显示框架连接与扫描诊断，并提供隐藏桌面图标和关于入口。功能配置保存在抖音私有目录；模块应用与宿主功能配置相互独立。

功能说明见 [完整帮助](docs/help.md)，也可在抖音内设置主页的“关于 → 帮助”离线查看和搜索。

首次从旧 Debug 版切换时，卸载旧模块后安装正式版，并重新启用正确的模块。后续正式版使用同一发布签名，可正常覆盖安装。

## 功能

- 推荐流筛选、顶部标签筛选、组件隐藏和透明度调整。
- 播放手势、播放速度、画面裁切、沉浸显示。
- 原生下载扩展、评论工具、语音工具。
- 自动回复与自动评论，支持固定内容、AI 模型及对象和内容筛选。
- 关注、粉丝 UID 名单和群聊范围配置。
- 模型设置、分层提示词、测试对话与图片/视频素材。

可用功能以当前适配规则和设置页面为准。方法扫描使用唯一候选判定；未匹配或存在多个候选时不会任意选择 Hook 目标。

## 构建

需要 JDK 21、Android SDK Platform 37。项目使用 Gradle 9.3.1、AGP 9.1.0、Kotlin/Compose 编译器 2.4.20 和 Miuix 0.9.4。

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat :app:testDebugUnitTest :app:lintRelease :app:assembleRelease --console=plain
```

签名配置、产物位置和验证结果见 [正式版发布说明](docs/releasing.md)。未配置正式签名时，只生成未签名的 Release APK。

全 DEX 集成测试使用本地抖音 40.6.0 APK，通过 `DOUAI_TEST_APK` 指定绝对路径；未配置时该测试跳过。第三方 APK、反编译输出、设备日志和发布私钥不包含在源码仓库内。

## 致谢

感谢 [Miuix](https://github.com/compose-miuix-ui/miuix) 提供 UI。其他依赖与设计参考见 [第三方说明](THIRD_PARTY_NOTICES.md)。
