# 0.1.69：补齐黑边占位视图重建后的绑定

版本：`0.1.69 / 70`，基于 0.1.68。使用既有正式签名，补齐视频页面重建后黑边占位视图未及时纳入管理的问题。R8 构建、全量回归、签名与归档校验已完成。

相对于已发布的 0.1.64，本版包含 0.1.65 至 0.1.68 的累计更新：官方下载分类、跨页多选、实况识别和原生编码保存，以及第三方下载分类、多选与独立默认行为。详细历史记录见 [0.1.65](release-0.1.65.md)、[0.1.66](release-0.1.66.md)、[0.1.67](release-0.1.67.md) 和 [0.1.68](release-0.1.68.md)。

本地已经复现并修复视图重建时的绑定缺口。用户设备上底栏背景残留是否因此恢复，仍需要安装本轮 APK 后按原步骤实测；旧设备日志和录屏不足以单独证明具体 ROM 上的根因。

## 现象与修改范围

用户描述：在底部“首页”内切换信息流，再返回视频流后，底部黑边残留；进入“消息”或“我的”再返回可以恢复。这是用户提供的复现描述。

录屏可直接确认的是：初始视频画面延伸到底部应用标签栏背后，约 7–11 秒和 14–22 秒该区域保留深灰背景，高约 78 px；约 11.5–12.5 秒可见视频透底恢复。该区域包含“首页、商城、＋、消息、我”，属于抖音应用底栏，不能称为 Android 系统导航区。录屏还有会随内容切换移动的纯黑横带，与应用底栏背景分开记录。抽帧中没有完整的消息列表或个人主页，不能把中途恢复透底直接归因于“进入消息/我的后返回”。

[HostBehaviorHooks](../app/src/main/java/com/douai/module/feature/HostBehaviorHooks.java) 保留原 `adv_ui_black_spaces` 对应的 `BaseListFragmentPanel.LLLFF()` 回调，并在同一声明类的精确 `onViewCreated(View, Bundle)` 方法执行后补充绑定。宿主在此方法中为新页面重新赋值 `i`、`j`，新增回调随后读取并管理这两个新视图，使其不必等待后续第一次 `LLLFF()` 才生效。黑边功能注册目标由 1 个增加为 2 个。

只有“隐藏上下黑边”实际开启时才隐藏对应占位视图。关闭配置、关闭模块 hook 或安装失败时，沿用视图状态恢复；第二个 hook 安装失败也会显式回滚已管理视图。本次没有更改系统全屏、状态栏或系统导航栏设置。

新增诊断前缀 `Black-space panel created:`，记录 `enabled` 以及新 `top`、`bottom` 视图的 visibility 或 `null`。这条日志用于核对新页面绑定是否执行，不包含作品正文、媒体 URL 或凭据。新的注册日志应为 `hide_video_black_edges targets=2`；这些是新包的诊断格式，尚未收到该机型运行新包后的对应记录。

## Evidence：证据

范围为用户提供的日志与录屏、本地抖音 40.6.0 宿主证据，以及本项目生命周期回归。原始设备材料和 `artifacts/` 下的分析文件为本地证据，不随公共源码仓库分发。

| 编号 | 来源 | 已确认的观察与限制 |
| --- | --- | --- |
| E-001 | `artifacts/black-edge-069-log-evidence.txt`；原 ZIP `LSPosed_20261016_235553.zip`，SHA-256 `CF51ABAE2779B29262A5506E5BA60B67B2059AA0A3EB48A9DD3D89C29AFE6EF3` | OnePlus Ace 6，PLQ110，Android 16/API 36，ROM `PLQ110_16.0.7.201(CN01)`。2026-10-16 最新相关进程加载 DouAi 0.1.64/API 102；黑边日志仅有注册 `targets=1` 和 `LLLFF()` 唯一命中，没有实际启用值、占位高度或切换前后布局记录。不能确认当时宿主精确版本。 |
| E-002 | `artifacts/black-edge-0.1.69-video/observations.md`、对应抽帧和像素观察；原片 SHA-256 `02FA53DE68FBA617A4A9ACC0240DCB159F230A06D4E4B39FD20E2A1BB2D7036E` | 582×1280 录屏的底栏背景约位于 `y=1202…1279`；可见残留和恢复，但没有独立确认消息/我的页面切换。坐标是录屏像素，不是设备 dp。 |
| E-003 | [BlackSpaceHostContractTest](../app/src/test/java/com/douai/module/feature/BlackSpaceHostContractTest.java) 与本地原始宿主 DEX | 本地 40.6.0 声明精确 `LLLFF()`、`onViewCreated(View, Bundle)`；后者实际向 `i: View`、`j: BottomSpace` 写入新引用。此 ABI 证据不等于已经确认用户设备正在运行相同版本。 |
| E-004 | [HostBehaviorViewTest](../app/src/test/java/com/douai/module/feature/HostBehaviorViewTest.java) 的 `blackSpacesBindRecreatedPanelRootBeforeItsFirstSpaceUpdate` 回归 | 本轮已见证该用例在修复前失败、修复后通过：页面重建并换入新 `i/j` 后，在再次执行 `LLLFF()` 前就应应用已开启的黑边配置。本版最终全量回归包含此用例。 |

## Finding：结论

| 编号 | 结论 | 证据 | 状态 |
| --- | --- | --- | --- |
| F-001 | 仅绑定 `LLLFF()` 会遗漏页面刚重建、但尚未再次更新黑边的时段；新 `i/j` 可能没有纳入当前配置管理。 | E-003、E-004 | 机制缺陷已在本地复现并完成修复，置信度高。 |
| F-002 | 录屏中的主要残留区域位于抖音应用底栏背后，另有独立的内容黑带；不能混同为系统导航栏问题。 | E-002 | 画面观察已确认，具体视图归属与设备修复成效待实测。 |
| F-003 | 旧日志只能确认 0.1.64 注册了黑边 hook，不能证明该配置已开启，也不能证明 F-001 就是这台设备当时的唯一根因。 | E-001 | 证据边界已确认。 |

## Path：调用与复核路径

P-001，`path_type=callflow`：

1. 宿主创建或重建 Feed 页面，`onViewCreated(View, Bundle)` 为 `i/j` 写入新视图引用。E-003 / F-001。
2. 新增 after 回调在宿主赋值完成后读取新引用，交给既有视图管理；配置开启时应用隐藏，配置关闭时保留可恢复状态，并输出脱敏创建诊断。E-004 / F-001。
3. 后续 `LLLFF()` 仍走原有回调，继续管理宿主的黑边更新。两处回调共用同一功能组与开关，安装失败显式回滚。
4. 正式包安装并重启抖音后，按用户原复现路径切换首页信息流与视频流，核对底栏背景、创建诊断及实际开关值。录屏中其他内容黑带单独观察；该步骤尚待用户设备验收。E-001、E-002 / F-002、F-003。

## 正式 Release 验证

已完成视图重建回归的红→绿验证。最终针对性回归为 5 个套件、26 项全部通过；最终全量回归为 138 个套件、1172 项全部通过，0 失败、0 错误、0 跳过。其中视图行为 9 项、黑边宿主 ABI 1 项、沉浸式控制 8 项、原宿主 DEX 3 项及最终 APK 契约 3 项均通过。以上是本版实际执行结果，不沿用前版计数。

R8 Release 构建成功，Release Lint 为 0 错误、48 警告。APK 包名为 `io.github.yudigaga.douai`，版本 `0.1.69 / 70`，已确认不是 debuggable 包。归档 APK 的 v3 签名校验通过，证书与既有正式版一致；最终 APK 中也已确认包含新增黑边绑定诊断。本地构建通过不代表真机修复验收完成。

本轮沿用以下正式构建与全量回归入口：

```powershell
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$env:DOUAI_TEST_APK = (Get-ChildItem -File -Filter '抖音-40.6.0*.apk' | Select-Object -First 1).FullName
.\gradlew.bat :app:lintRelease :app:assembleRelease --console=plain
$env:DOUAI_VERIFY_RELEASE_APK = (Resolve-Path 'app/build/outputs/apk/release/app-release.apk').Path
.\gradlew.bat :app:testDebugUnitTest --console=plain
```

覆盖安装时沿用既有正式签名，安装后需完全退出并重新打开抖音，使新增生命周期 hook 加载。OnePlus Ace 6 上的真实布局表现、信息流切换恢复以及消息/我的返回路径仍待实测。

## 构建产物

- APK：`artifacts/DouAi-0.1.69-release.apk`，4,156,735 字节。
- SHA-256：`37B714D672B5A92EFA3CF9C484C010349B5CB011942AF6D7EF07F2AE86D463BB`。
- 签名证书 SHA-256：`FAE67C134F4C4FA73B8E34487C47A8C7F3E7C34B840BC245AFAC51585B7DFD47`。
- 最终验证汇总：`artifacts/black-edge-069-validation.json`。
- 构建与针对性日志：`artifacts/black-edge-069-release-build.log`；全量日志：`artifacts/black-edge-069-full-tests.log`。

归档 APK、Release 输出和最终全量测试所用 APK 的 SHA-256 一致。

正式下载：[GitHub](https://github.com/yudigaga/DouAi/releases/tag/v0.1.69) · [LSPosed](https://github.com/Xposed-Modules-Repo/io.github.yudigaga.douai/releases/tag/70-0.1.69)。沿用既有正式签名，可覆盖升级；APK 关于页及两个仓库均保留[免责协议](../DISCLAIMER.md)。
