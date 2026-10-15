# DouAi 0.1.64

版本 `0.1.64` / versionCode `65`。本次让模块界面自动跟随系统深浅色，沿用正式包名、发布证书与抖音 40.6.0 适配。

## 界面行为

- 独立模块状态页、启动窗口、状态栏与导航栏同步深浅色。
- 抖音内设置主页、分类、组件隐藏、模型/提示词编辑器、测试对话、帮助和配置备份弹窗不再固定浅色。
- 模块按系统当前夜间配置选择主题，宿主 Activity 的浅色覆盖不改变模块的模式判断；不修改抖音全局主题、资源或设置。
- 对仍在显示的模块页面就地刷新配色，保留控件与 Compose 编辑状态；原生弹窗背景、按钮、输入框和下拉菜单也同步更新。宿主因配置变化自行重建 Activity 时，仍遵循原有页面关闭生命周期。
- 用户自选的颜色预览保持原值，不随界面配色转换。动态加入的原生控件同样应用当前配色，页面离开后释放配置监听。

实现依据：Android 通过 `Configuration.uiMode` 的夜间位表示当前深浅色；宿主内使用全局资源的夜间位，并保持模块自身资源、窗口与生命周期隔离。参见 [Android 深色主题指南](https://developer.android.com/develop/ui/views/theming/darktheme) 及 [AOSP 全局与 Activity 资源配置更新](https://android.googlesource.com/platform/frameworks/base/+/a890d7b328dc436999d7de4f8a1dc3337b5c7944/core/java/android/app/ResourcesManager.java)。

## 验证范围

本地覆盖系统浅→深→浅切换、宿主固定浅色、未保存输入与选区、用户颜色预览、动态控件、回调释放、原生和 Miuix 布局，以及 Android 9/15 的窗口主题。

2026-10-15：最终全量 126 个测试套件、1,064 项测试通过，0 失败、0 错误、0 跳过，包含本地抖音 40.6.0 的 3 项 DEX 检查和最终 Release APK 的 3 项契约检查。Release Lint 为 0 错误、48 条警告，签名 R8 Release 构建成功，`git diff --check` 通过。浅色、深色、窄屏和横屏截图已检查；Miuix 标题与正文前景色也随主题同步。

| 项目 | 结果 |
| --- | --- |
| APK | `DouAi-0.1.64-release.apk` |
| 版本 | `0.1.64` / `65` |
| 包名 | `io.github.yudigaga.douai` |
| 大小 | 4,123,975 字节 |
| SHA-256 | `0538551098B8E4E518CAFB54D6995C42E3B6C5627844268626724A12C6A10C6E` |
| 签名 | v3 验证通过，与既有正式证书一致 |
| 证书 SHA-256 | `FAE67C134F4C4FA73B8E34487C47A8C7F3E7C34B840BC245AFAC51585B7DFD47` |
| Android | minSdk 28、targetSdk 35，未开启调试 |

按用户要求跳过全部真机验证。未安装到设备、未进行真实账号发送或第三方接口调用。

正式下载：[GitHub](https://github.com/yudigaga/DouAi/releases/tag/v0.1.64) · [LSPosed](https://github.com/Xposed-Modules-Repo/io.github.yudigaga.douai/releases/tag/65-0.1.64)。沿用既有正式签名，可覆盖升级；APK 关于页及两个仓库均保留[免责协议](../DISCLAIMER.md)。
