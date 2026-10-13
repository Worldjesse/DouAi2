# DouAi

抖音增强模块，面向抖音 `com.ss.android.ugc.aweme` 40.6.0，使用 libxposed API 102。

作者：**yudigaga**。当前正式版：**0.1.51** / versionCode **52**。

- [源码仓库](https://github.com/yudigaga/DouAi)
- [正式版下载](https://github.com/yudigaga/DouAi/releases)
- [LSPosed 模块仓库](https://github.com/Xposed-Modules-Repo/io.github.yudigaga.douai)

正式包名：`io.github.yudigaga.douai`。

## 0.1.51 更新

修复部分机型反复出现“方法查找完成”：成功确认独立保存，自动重启失败不会撤销确认，返回关闭成功提示也会保存确认。重启中转就绪超时会提示手动重启，避免无回执时结束宿主进程。沿用 0.1.50 的短视频进度、独立评论/回复开关及其他功能。

[完整更新与验证](docs/release-0.1.51.md)

## 安装与使用

需要 Android 9 或以上，以及支持 libxposed API 102 的 LSPosed。当前规则针对抖音 40.6.0，不保证其他版本适配。

1. 下载 Releases 中的正式签名 APK 并安装。
2. 在 LSPosed 中启用 DouAi，作用域只选择抖音。
3. 完全退出并重新打开抖音，在抖音设置中的 DouAi 入口配置功能。

模块应用显示框架连接与扫描诊断，并提供隐藏桌面图标和关于入口。功能配置保存在抖音私有目录；模块应用与宿主功能配置相互独立。

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
