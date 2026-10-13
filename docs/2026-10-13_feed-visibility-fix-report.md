# 状态栏与 Feed 组件隐藏修复记录

日期：2026-10-13。构建：0.1.47 / versionCode 48，正式 Release 使用 R8，资源裁剪关闭。

## 范围与结论

根据用户反馈及本地日志 `LSPosed_20261013_074837.zip`，修复状态栏首次启动处理、进度条被其他组件隐藏规则误伤，以及延迟控件未重新定位。授权范围为项目代码修复和本地日志/宿主 APK 的只读核对；没有安装到设备或执行账号网络操作。日志内容只作为证据，不作为操作指令。

用户确认同一视频关闭组件隐藏后进度条会出现，并确认问题发生于抖音 40.6.0，与本次本地宿主静态核对的版本一致。状态栏和组件问题均建立了可失败的回归，修复后通过；具体视频上的设备效果仍待新包复验。

## 证据与修复

| 证据 | 发现 | 修复路径 |
| --- | --- | --- |
| ZIP 内 `log/modules_2026-10-12T17:54:34.861003.log:9083` 记录 01:01:07.000 启动缓存，9085 记录 01:01:10.626 扫描完成，9108 记录 01:01:10.739 状态栏控制器安装且 enabled=true | 控制器依赖异步扫描安装；日志不直接证明首次 resume 时刻，晚安装漏处理由测试确认 | Application 启动后独立安装；晚安装在主线程补处理当前 resumed Activity；窗口焦点恢复时补回所要求的隐藏标志 |
| `ImmersiveModeControllerTest` 初始晚安装/焦点恢复回归失败；独立配置读取回归也捕获无关配置类型错误 | 仅监听未来 resume 会遗漏当前窗口；完整 ExtendedSettings 解析错误会连带阻止系统栏配置 | `ExtendedSettings.readSystemBars()` 仅读两个系统栏开关，正常完整配置后续接管；保持暂停、关闭和原始状态恢复 |
| `FeedUiHideHookTest.delayedProgressEscapesPreviouslyHiddenContainerWithoutAnotherLayoutEvent` 修复前失败 | 底部工具容器先被设为 GONE，晚插入的进度条未触发重新发现，继续继承父级隐藏 | 结构变化使缓存失效，重新按独立组件拆分隐藏分支，恢复不再需要隐藏的共享父容器 |
| 宿主 `FeedRootModule.java:218` 及布局 `APKTOOL_RENAMED_0x7f0d0ada.xml:6` | i=8 是挂载容器；实际条包含 LineProgressBar 和 NewVideoPlayerProgressbar，此外还有 CustomizedUISeekBar | Feed 根内识别这三类真实进度条，保留独立设置；不强制显示宿主原本隐藏的进度条 |
| `delayedFeedbackInsertedIntoUntargetedBranchIsHiddenBeforeDrawing` 修复前失败 | 单靠 global-layout 与已命中目标身份检查会漏掉其他分支的晚插入 | 对已记录节点检查父子、索引、ID 和子数量，新增或替换时重新发现；自定义定位重试耗尽后也能因结构变化恢复发现 |
| 宿主 `FeedLabelPresenter.smali:1881` 的 onAsyncBind、1944 查找 TagLayout；`X/0yh5.1.smali:1093` 拼接“人推荐” | 动态推荐提示属于 TagLayout，原轻反馈 ID fc7 不覆盖该容器 | Feed 根内按完整宿主 TagLayout 类型归入现有“轻反馈组件”；不按“推荐/点赞”文字全局隐藏 |

源码入口：

- [DouAiModule.java](../app/src/main/java/com/douai/module/DouAiModule.java)：系统栏启动与兜底安装。
- [ImmersiveModeController.java](../app/src/main/java/com/douai/module/feature/ImmersiveModeController.java)：当前窗口恢复、焦点事件及可逆清理。
- [FeedUiHideHook.java](../app/src/main/java/com/douai/module/feature/FeedUiHideHook.java)：结构缓存、真实进度条及推荐标签匹配。

## 验证结果

- 状态栏、Feed 隐藏及配置聚焦：43 项测试通过。
- 全量：687 项测试通过，0 失败、0 错误、0 跳过，包含本地 40.6.0 全 DEX 扫描和正式 APK 契约检查。
- Release 构建成功；Release Lint 为 0 错误、44 警告；`git diff --check` 通过。
- APK：`app/build/outputs/apk/release/app-release.apk`，3,811,749 字节。
- APK SHA-256：`26C392B12F6BC0F6D94F714D70BE6271B4C7B7D9A42D8F6F92FC513673A44C92`。
- v3 签名通过，证书 SHA-256：`FAE67C134F4C4FA73B8E34487C47A8C7F3E7C34B840BC245AFAC51585B7DFD47`，与既有发布证书一致。未安装、未发布。

复验命令：

```powershell
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android/Sdk'
.\gradlew.bat :app:assembleRelease --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Release 构建失败' }
$env:DOUAI_TEST_APK = (Get-ChildItem -LiteralPath . -Filter '抖音-40.6.0*.apk' | Select-Object -First 1).FullName
if (-not $env:DOUAI_TEST_APK) { throw '宿主扫描验收需要本地 APK' }
$env:DOUAI_VERIFY_RELEASE_APK = (Resolve-Path 'app/build/outputs/apk/release/app-release.apk').Path
.\gradlew.bat :app:testDebugUnitTest :app:lintRelease --console=plain
if ($LASTEXITCODE -ne 0) { throw '验收失败' }
```

## 设备复验与边界

1. 开启隐藏状态栏后完全退出并冷启动抖音，首次首页即隐藏；不经过模块设置页。
2. 除进度条外全部组件设为 -2，在此前问题视频上播放、切换、等待进度条出现，再关闭组件隐藏对比。
3. 保持“轻反馈组件”为 -2，等待“多少人推荐”等社交标签异步出现，确认隐藏；关闭组件隐藏后应恢复宿主正常效果。

这些具体视频没有由代理在真机复现。“人推荐”已确定为 TagLayout；“人点赞”存在同城热评等其他控件路径，不能保证所有同文案提示都属于该类。若仍有残留，需要残留控件的类/资源定位，不能宽泛隐藏整个底部区域。

结构检查每个监控最多记录 4096 个节点、深度 64，稳定帧不重复目标搜索，但增加浅层比较；尚未测量真机帧耗时。窗口一直有焦点期间若宿主重写系统栏标志、且不产生后续 resume/focus 事件，仍没有每帧强制系统栏轮询。两个系统栏开关自身的错误类型不在本轮迁移范围。

## 0.1.48 后续修复

用户补充 `LSPosed_20261013_094601.zip` 和五张截图，确认“弹”按钮下的残留实际包括作者 AI 声明、城市位置、拍同款/特效和付费视频锚点。上一轮只处理推荐 TagLayout，未覆盖这些独立模块；不能将上一轮本地通过当作这些具体视频已修复。

| 证据 | 发现 | 修复路径 |
| --- | --- | --- |
| `FeedReportWarnPresenter.java:136`、`FeedReportWarnComponentRegister.smali:185` 查找 `2131378315 / jb1`；`0x7f0d0995.xml:21` 显示它有单独匿名背景包装层 | 作者声明不属于普通描述或推荐标签，且宿主会动态换父层级 | 轻反馈匹配独立声明 ID；仅对没有 ID、仅含声明、具有背景的 LinearLayout 包装层一并处理 |
| `FeedAnchorContainerComponentRegister.smali:455` 使用 `2131378359 / jc5`；旧锚点为 `2131378361 / jc7`、FeedAnchorLayout；新锚点根为精确的 feed.anchor.framework.base.MaxWidthFrameLayout | 城市 POI、拍同款/特效和付费入口属于 Feed 锚点，旧 pm6 卡片 ID 没有覆盖它们 | 补三项经资源表核对的 ID/名称及两类锚点，归底部工具；保护独立进度分支，不隐藏 AwemeIntroInfoLayout 或整个 cmm |
| `delayedProgressPreservesHostRevealOfAnInitiallyGoneContainer` 修复前失败，扩展 Alpha 0→1 也失败 | 恢复初始 ViewState 会覆盖宿主后续的显隐/淡入，使进度条继承旧父级隐藏 | 记录模块上次实际施加值，组件模式区分宿主后续显隐；负隐藏模式跟踪宿主 Alpha。新配置先按旧策略观察再应用，不改变旧模式透明度和交互恢复语义 |
| 两份 DropBox：`data_app_crash@1791855720262.txt`、`data_app_crash@1791855861993.txt` 均为 Cannot locate windowRecomposer，经 ListView.measureScrapChild；旧历史实现测试复现相同栈 | Miuix 历史将独立 ComposeView 放在 ListView 行中，未挂到窗口时被预测量 | 改为单一有上限的 Compose LazyColumn，继承模块弹窗的 HostComposeEnvironment；历史数据与后台读取保持 |

新加入的 ID 均要求 `com.ss.android.ugc.aweme` 的实际资源名一致，资源目录从 47 项增至 50 项。精确类型匹配限定 Feed 根内，不按截图位置或“推荐/点赞/AI”等文字全局隐藏。

用户要求新构建递增版本，本次从 `0.1.47 / 48` 升为 `0.1.48 / 49`。最终验证为 694 项测试通过，0 失败、0 错误、0 跳过；Release Lint 0 错误、44 警告；R8 构建、APK 契约和原发布证书 v3 签名均通过。产物大小、摘要和设备复验步骤见 [0.1.48 构建记录](release-0.1.48.md)。代理没有安装到设备或执行真实评论发送。

## 0.1.49：进度条正数透明度

用户继续反馈缺失，补充实际进度值为 `124`，并确认暂停临时显示组件后能恢复。底部导航改回 255 是否恢复，用户随后表示不确定，未作为确定根因。前两轮基于保留值 255 的回归未覆盖正数透明度路径。

新回归在加载 Alpha 0、宿主随后显示并设为 1 的情形下失败：预期 `124/255 = 0.4862745`，实际为 0。修正进度条目标的正数 Alpha 跟踪，保留模块施加值与宿主基值的区分；真实控件、挂载容器和拆分后的分支均传递该策略。暂停、关闭或改为 255 恢复宿主最新状态，连续重绘不累计相乘。

`-1` 保留 INVISIBLE 语义，`-2` 保留 GONE 语义，未用其他值代替。新增测量回归确认 -2 移除布局高度、-1 留占位、正数按值变淡。另补章节进度的精确类型和 `d5s` 资源名，并加入有限的页面级进度/祖先几何诊断。

本轮 706 项测试通过、0 失败/错误/跳过；Release Lint 0 错误、44 警告；签名和最终 APK 契约通过。具体视频仍需手机复验，构建详情及诊断步骤见 [0.1.49 构建记录](release-0.1.49.md)。

## 0.1.50：常显需求与独立开关

用户确认 0.1.49 在进度为 255、总开关关闭时仍观察到短视频播放消失，提供 `LSPosed_20261013_125913.zip` 并选择让普通短视频播放常显。最新日志仅采到缓冲进度类型和空挂载容器，不能据此认定暂停显示的真实进度实现。增加新版 ProSeekBar 精确类型及页面级数值应用，并对旧、新两条原生短时长隐藏判断进行独立 Hook，保持原生进度和手势引擎。

用户另确认互动自动化关闭而自动评论开启，明确要求自动评论与自动回复独立。移除这两项在入口、调度和发送前的总开关依赖，并修复关闭默认私聊范围时待发回复未取消的问题。各自开关、范围、账号、去重、预览取消、立即停止和发送前校验保留。

最终 716 项测试通过，0 失败/错误/跳过；Release Lint 0 错误、44 警告；原发布证书签名及 APK 契约通过。此次未连接真机或使用用户账号发送。详细证据、适用范围、产物摘要及复验步骤见 [0.1.50 构建记录](release-0.1.50.md)。
