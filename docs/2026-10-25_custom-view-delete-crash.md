# 自定义视图删除闪退与 Miuix 宿主环境修正

## 现场与本地复现

用户提供 `LSPosed_20261025_155255.zip`，并报告在模块设置的自定义视图中点击删除会闪退。包内三份抖音 40.8.0 / 400801 崩溃记录分别为北京时间 2026-10-25 15:51:09、15:51:33 和 15:51:54，异常相同：

```text
java.lang.IllegalStateException:
No NavigationEventDispatcher was provided via LocalNavigationEventDispatcherOwner
```

日志 R8 ID 与本地 0.2.19 APK 的 mapping 一致：`93321e1e1f4735c0ab79f8d64a08d886ebf672239300db24953e6aa6ad911b0c`。映射后的关键帧为 `NavigationEventHandlerKt.NavigationEventHandler`，与 Miuix 删除确认浮层启用返回处理的路径相符。原压缩包不改动、不提交，最小脱敏证据及摘要在 `work/view-delete-crash-20261025/`；0.2.19 mapping 保留在其本地构建目录。

新增 `MiuixComponentHideDeleteTest.plainHostCanCancelAndConfirmDeletingACustomView`：在普通 `android.app.Activity` 内打开真实 `MiuixComponentHidePage`，滚动至已保存规则并点击删除。修正前单项测试失败于 `NavigationEventHandler.kt:82`，异常与现场完全一致，不是模拟抛错。

## 原因与修正

原 `HostComposeEnvironment` 为模块 Compose 树设置了 Lifecycle、SavedState、ViewModel 与旧 OnBackPressed owner，但没有提供新 NavigationEvent owner。普通宿主并不保证提供与模块同一套 AndroidX 导航环境；删除确认浮层打开后读取不到对应 owner，尚未执行删除持久化就抛出异常。

修正由模块环境自己实现 `NavigationEventDispatcherOwner` 并安装到自己的视图树，以公开的 `NavigationEventInput` 接入既有返回键处理。确认浮层激活时先处理返回，停用后保留旧回调顺序和关闭兜底。关闭先撤销桥接与 owner，释放 Compose，再销毁导航分发器。既不把宿主对象强制转换为模块 AndroidX 类型，也不替换或销毁宿主 Decor 的 owner。

回归覆盖取消、返回关闭确认框、再次打开并确认删除，检查取消/返回不改变配置、确认后规则移除且组件管理页仍保持打开；另验证宿主导航 owner 不被替换、旧回调与关闭顺序、环境销毁后不再回调。

## 与界面统一一起处理的宿主接入问题

视图查找工具条、选中面板、搜索列表统一使用模块 `HostSettingsTheme` 与实际 Miuix 控件。嵌入普通宿主 Decor 的新 Compose Surface 显式绑定模块自己生命周期管理的 Recomposer，避免向宿主 Decor 查找模块 LifecycleOwner；原暂停与长按查找测试会覆盖这一接入。搜索列表继续使用 `ViewInspector.contentSummary`，保留内容摘要与密码保护。

## 验证边界

删除场景修正前红、修正后绿的 JUnit 与命令日志已保存。本轮正式包为 0.2.20 / 121，最终专项、画面检查、签名和产物结果见 [正式版记录](release-0.2.20.md)。本地复现与静态日志分析不能代替新版在手机上的最终验收；用户此前“功能可用”的反馈对应 0.2.19。
