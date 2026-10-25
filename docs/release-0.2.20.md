# DouAi 0.2.20 本地正式版：Miuix 视图查找与删除闪退修复

版本 **0.2.20 / 121**，正式包名 `io.github.yudigaga.douai`。沿用长期证书、R8 Release 构建，可覆盖安装；安装后完全退出并重新打开抖音。本次交付仅为本地正式包，未创建远端 Release。

## 本版改动

- 查找工具条、选择面板、搜索与筛选列表改用模块的 `HostSettingsTheme` 和实际 Miuix 控件，跟随系统明暗主题；保留拖动、折叠、横屏布局、重叠切换、属性复制及规则路径预览。
- 修复自定义视图点击删除时的 `No NavigationEventDispatcher was provided via LocalNavigationEventDispatcherOwner` 闪退。模块提供独立导航 owner，处理取消、返回、确认和关闭清理，不替换宿主导航对象。
- 为嵌入宿主的查找界面绑定模块私有 Recomposer，避免依赖宿主 Decor 的 AndroidX 生命周期；搜索结果继续显示安全内容摘要。

删除崩溃的日志、复现和修正依据见[分析记录](2026-10-25_custom-view-delete-crash.md)。用户此前“功能可用”的反馈对应 0.2.19，本版手机效果仍待复验。

## APK 与验证

[下载 DouAi-0.2.20-release.apk](../artifacts/DouAi-0.2.20-release.apk)

| 项目 | 结果 |
| --- | --- |
| versionName / versionCode | `0.2.20 / 121` |
| 文件大小 | 5,147,945 字节 |
| SHA-256 | `6E6C488D0586DA22A7B33390E17F46AA3B7A81A9A1ECF674182B99D086C18E11` |
| 签名 | 原长期证书，APK v3 验证通过 |
| 证书 SHA-256 | `FAE67C134F4C4FA73B8E34487C47A8C7F3E7C34B840BC245AFAC51585B7DFD47` |
| 对齐 | `zipalign -c -P 16 -v 4` 通过 |
| 专项 | 15 个套件、223 项，0 失败、0 错误、0 跳过 |
| Release Lint | 0 错误、54 条警告，0 新增代码警告；基线未修改 |

223 项由 14 套件的 218 项 UI、定位、组件隐藏、暂停手势和导航回归，以及最终签名归档 APK 的 5 项元数据/DEX/R8 合同组成。等价 KTX 背景写法调整后再次执行了 8 项视觉检查；统计不重复计入这 8 项。原生 ICU 预检通过。24 张真实渲染 PNG 覆盖明暗主题、窄屏、横屏、1.3 倍字体与展开状态，视图树截图取其实际窗口。

封存目录：`artifacts/targeted-verification/20261025-miuix-finder-0.2.20-final/`。`targeted-manifest.json` 记录 `verified-targeted-release`、`fullSuiteRun=false`、`published=false`，绑定 554 个源码/构建输入、APK、JUnit、Lint、R8 mapping、签名与预览哈希；canonical、归档与 Gradle 输出 APK 摘要一致。

## 边界

本版未重跑全量业务单元测试、宿主 APK 完整 DEX 扫描、平台模拟器或手机验收、真实 LSPosed 回调、真实服务及 CI。APK 合同检查了 40.6 与 40.8 规则资产与源码一致，但不代替宿主运行验证。普通 View 树不展开 Canvas 绘制对象、WebView DOM 或每个 Compose 节点。

0.2.19 的全量验收因用户追加 Miuix UI 要求而中止，其 APK 与崩溃 mapping 保留为独立基线；首轮 0.2.20 构建因两条新增 Lint 写法提示而被本目录最终包替代，不作为本次交付。
