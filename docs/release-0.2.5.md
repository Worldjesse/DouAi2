# DouAi 0.2.5 本地候选：发送状态核对与设置优化

**0.2.5 / 106 为已完成严格本地验收的候选，本轮不发布远端。** 既有正式版仍为 0.2.4 / 105，统一状态入口见[构建与当前状态](releasing.md#当前状态)。本轮沿用包名 `io.github.yudigaga.douai`、长期签名、libxposed API 102 和抖音 `40.6.0 / 400601` 适配，最低 Android 9。

本轮已在 Android 15 / API 35 的 x86_64 模拟器执行三项 Android 平台合同，全部通过；没有执行真实账户调用、评论或私信投递，也没有取得手机宿主性能结果。平台执行证据与宿主验收分别记录。

## 核对未知发送结果

入口为抖音设置 → DouAi → 自动评论或自动回复 → 发送与记录 → 评论历史记录或回复历史记录 → **发送状态**。打开、刷新和核对页面都不会补发消息。

页面按当前登录账号列出可归属的账本记录，显示全部账号占用的容量、待核对、损坏、旧记录未归属及存储状态。发送账本 v2 增加账号范围和进程会话；新预留结果区分成功、重复、容量满、存储失败和无效参数，调用方只有成功持久化预留后才能调用宿主。未知或损坏的记录仍保留防重，清空展示历史也不会解除防重。

只有**此前进程留下、已经等待至少两分钟、尚未确认成功**的记录允许人工核对。选择“已核对发送成功”或“已核对未发送”后，还要再次确认：前者标记本地成功并保留防重，后者解除防重、允许后续任务再次发送；当前页面不会触发重试。来自本次进程、记录已变化或账号已切换时不应用旧操作。

旧 v1 记录只在已有本地历史能够证明属于当前账号时参与核对。无法归属及损坏的记录继续保留，页面不提供一键删除全部未知记录。解除自动评论防重时还核对并处理对应旧版保护记录，防止只有一侧状态被释放。

恢复页文字通过统一主题绑定处理，新增深色切回浅色的回归，避免保留旧主题文字颜色。

发送回调中的账本操作转入受控后台执行：自动评论、私信回复及评论回复保留发送前落盘、attempt 校验、未知结果保留、迟到回调不覆盖新任务等契约。评论回复的扫描/账本链路补充容量与存储状态；本轮历史页人工核对入口仅用于自动评论和私信回复，不把评论回复扫描记录混入其中。

## 更快找到设置

普通界面与 Miuix 使用同一份 **21 项**搜索目录，保留稳定搜索 ID。除 AI、语音和下载外，新增自动评论、私信自动回复、评论回复、配置备份、本地数据分析/报告、倍速、互动自动化、视频筛选、组件隐藏、续火花、消息防撤回、手势和评论工具等入口。

下载方式、保存位置、下载任务、文件名模板和无水印下载会滚动到具体设置行并短暂高亮；高亮到期、切页或关闭页面后恢复，旧回调不能作用于新页面。若当前选择第三方下载，搜索到官方专属设置会说明原因并定位“下载方式”，不会切换下载来源或写入其他设置。

`ConfigurationCatalog` 集中原有可备份键及类型、功能热更新依赖，并先收敛倍速和下载等明确默认值。设置预览与倍速保存校验共用目录，`ConfigKeys` 和运行时差异分发引用同一目录。原备份格式、可迁移 key、旧手势列及配置迁移保持；操作令牌与运行记录仍排除在配置备份之外，旧范围值不会因为新增界面校验而被备份格式拒绝。

## 成员头像加载

成员列表使用独立头像加载路径，按显示尺寸采样解码，复用 `ChatImageLoader` 的 EXIF 方向处理，不再经由 AI 图片序列化路径生成头像。相同 URL 的进行中请求合并，采用有界图片缓存；行复用或页面关闭后取消无用请求，迟到结果不会写入已复用的行。

该变化减少了重复请求和无用图片转换，但尚未形成手机滚动帧耗时或峰值内存的对照数据，不能据此声称实际帧率改善。

## Android 平台烟测与指标

新增 `PlatformSmokeTest` 与 `tools/run-platform-smoke.ps1`，只操作隔离的合成配置和本次新建的报告文件：

| 平台合同 | 检查范围 | 明确边界 |
| --- | --- | --- |
| Android 原生正则与备份 | 默认导出去除合成凭据、保留占位符；检查并应用到隔离配置后核对结果 | 不读取或迁移用户当前配置，不调用提供商 |
| PNG 保存 | Android 10+ MediaStore 发布状态、MIME、独立解码、尺寸/尾部内容与删除 | Android 9 需要交互式 SAF，不以模拟路径代替 |
| PDF 保存 | Android 10+ MediaStore 发布状态、系统 `PdfRenderer` 页数/末页渲染与删除 | 不代表外部接收应用能读取分享 URI，也不覆盖真实长报告峰值内存 |

每项记录执行结果、SDK、耗时和 Java 堆前后快照；运行脚本保存设备指纹、ABI、模拟器标识、已安装 APK 摘要、仪器化日志及 JSON 汇总。Android 9 的无人值守存储项跳过后汇总为 `partial`。这些指标不包含 P95 帧耗时、宿主冷启动、原生/GPU 内存峰值或持续性能。

最终 0.2.5 源码的 Debug 与 Android 仪器化测试 APK 已重新构建成功（AndroidX runner 1.5.2），并于 2026-10-21 10:42（Asia/Shanghai）在 `emulator-5556` 实际执行。设备为 **Android 15 / API 35 / x86_64 模拟器**，三项均为 `passed`，仪器化退出码 0，汇总状态 `passed`。证据目录为 [`smoke-aa5d93ecd2f84ae2842c01b300a27c6b`](../artifacts/platform-smoke/smoke-aa5d93ecd2f84ae2842c01b300a27c6b/summary.json)，包含设备指纹、实际安装摘要、日志与逐项 JSON。

| 合同 | 本次单次耗时 | Java 堆使用量：前 → 后 |
| --- | ---: | ---: |
| PDF 保存、解析与清理 | 879.48 ms | 3,290,704 → 3,467,408 字节 |
| PNG 保存、解码与清理 | 136.49 ms | 3,532,944 → 3,799,184 字节 |
| Android 原生正则备份往返 | 179.48 ms | 3,864,720 → 7,133,552 字节 |

这三个耗时仅是本次合成夹具的一次执行，不是重复统计的性能基准；Java 堆快照不等于峰值内存。宿主帧率、冷启动、LSPosed 实际 Hook、真实账号发送、外部分享接收应用及 API 28 交互式 SAF 仍未验证。

本地构建 APK 与模拟器实际安装 APK 的 SHA-256 一一相等：

| APK | 本地及实际安装 SHA-256 |
| --- | --- |
| Debug 模块 | `bfb1685a698037971d2c475ed1044cabdf8a9a567f0f583e46aa082f73e9e7b2` |
| Android 仪器化测试 | `c40a48f49873ee96595a731b5a869ee510c1c9bb4561f3543ca594f0cb2e362d` |

复现入口见[Android 平台烟测](releasing.md#android-平台烟测)。本次平台结果属于上述 Debug APK，不把它替代为最终签名 Release APK 或宿主环境的执行证据。

## 语音保持原行为

用户最终要求为：**超过 30 秒继续尝试提交，有时候能够成功，本轮不做处理。** 本轮不修改全局提示词、自动评论提示、生成字数、语音时长处理、语速、码率、重试次数或回退策略，不新增截断、自动加速或自动压缩。

此前的成功/失败日志与单变量试验讨论继续保留在[语音现场记录](voice-2026-10-21.md)，它们是当时的样本与分析，不构成本轮改变语音策略的依据。

## 最终验证与历史修正

首轮完整严格运行实际执行 **185 套件、1970 次，1 失败、0 错误、0 跳过**，因此不属于验收通过。唯一失败为 `NativeAutoReplyIntegrationTest.unavailableDurableReservationForbidsNativeSendAndDoesNotSpendAllowance`：本轮已明确区分容量满与落盘失败，旧断言仍期望 `LEDGER_WRITE_FAILED`，实际正确返回 `LEDGER_FULL`。禁止调用宿主的断言已通过；修正仅更新这一处原因断言，保留不消耗额度的检查，没有修改生产发送逻辑。首轮失败证据保留在 [`20261021T021044Z-5317db23/failure.json`](../artifacts/release-verification/20261021T021044Z-5317db23/failure.json)。其后 `20261021T022645Z-2d5bb5b5` 在完成前为修正恢复页深浅主题切换而中断，[中断记录](../artifacts/release-verification/20261021T022645Z-2d5bb5b5/interruption.json)保留为 `interrupted`，也不算通过。加入主题回归、复用 EXIF 处理并完成预检后，重新构建 Debug/仪器化 APK、重跑上述平台合同，再完成以下最终严格验收。

最终严格运行目录为 [`20261021T024248Z-5a07a8a2`](../artifacts/release-verification/20261021T024248Z-5a07a8a2/manifest.json)，2026-10-21 11:04（Asia/Shanghai）完成，状态 **`verified-local`、`published=false`**。这份最终签名 APK 的本轮全量结果为 **185 套件、1971 次执行，0 失败、0 错误、0 跳过**。

| 项目 | 最终状态与证据范围 |
| --- | --- |
| 配置目录与搜索 | 原可备份类型清单完整保留；21 个搜索 ID 唯一；普通/Miuix 定位与生命周期回归通过 |
| 阶段聚焦与最终预检 | 修后 4 套件/147 项通过，见 `work/optimization-0.2.5-refocus.log`；主题等最终预检 3 套件/12 项通过，见 `work/optimization-0.2.5-final-preflight.log`。阶段结果不与全量计数相加 |
| 全量 JVM 与宿主/APK 合同 | **185 套件/1971 次执行，0 失败/错误/跳过**；包含本地宿主 DEX 与最终签名 APK 合同，不沿用旧版计数 |
| 发布工具回归 | **34 项通过**，见最终运行的 `tool-tests.log` |
| 原生 ICU | 两个正则编译与 **16 项合成匹配合同通过**，见最终运行的 `android-icu-placeholder.json` |
| Release Lint 与增量预算 | **0 错误、57 条既有警告、0 新增**，预算通过，未扩大 baseline |
| 最终 Release、签名、对齐与封存 | 原证书 v3 签名、16 KiB ZIP 对齐及归档完整性通过；最终运行、canonical APK 与摘要一致 |
| Debug 与 Android 仪器化 APK | 最终源码重新构建成功；本地与模拟器实际安装 APK 摘要一一相等，见上述平台记录 |
| Android 平台实际执行 | Android 15 / API 35 x86_64 模拟器三项均通过，汇总 `passed`；仅模块平台合成夹具 |
| 手机宿主、真实服务及剩余平台范围 | LSPosed、真实账号发送、外部分享接收应用及 API 28 交互式 SAF 未执行；无手机性能结论 |
| GitHub/LSPosed 发布 | 本轮不执行；既有正式版保持 0.2.4 / 105 |

严格入口的 `deviceValidation` 与 `realServiceValidation` 保留其未执行宿主/真实服务验收的状态；上述平台模拟器执行单独记录，并由候选归档元信息引用，不将模拟器平台合同改写为宿主真机验收。

## 本地签名归档

最终 APK 为 [`artifacts/DouAi-0.2.5-release.apk`](../artifacts/DouAi-0.2.5-release.apk)，**4,665,556 字节**，SHA-256：

```text
8F0AD1B5799CD9E4B530BF250A4425414BEFFA603DB89A31982ADCD56EC39163
```

包名 `io.github.yudigaga.douai`，版本 `0.2.5 / 106`。长期证书 SHA-256 为 `FAE67C134F4C4FA73B8E34487C47A8C7F3E7C34B840BC245AFAC51585B7DFD47`，v3 签名通过。旁文件为[归档元信息](../artifacts/DouAi-0.2.5-release.json)、[SHA256SUMS](../artifacts/DouAi-0.2.5-SHA256SUMS.txt)与[16 KiB 对齐结果](../artifacts/DouAi-0.2.5-zipalign.json)。旧失败与中断运行保留，不能将其中的 APK 或计数替代这份最终归档。

## 实现证据与后续检查

| 证据入口 | 已实现行为 | 后续验证 |
| --- | --- | --- |
| [SubmissionLedger](../app/src/main/java/com/douai/module/config/SubmissionLedger.java) / [恢复页](../app/src/main/java/com/douai/module/ui/SubmissionRecoveryDialog.java) | 账号/会话范围、预留结果、保守防重与双重确认核对 | 当前账号、旧版本归属、迟到回调、存储失败和恢复限制回归 |
| [配置目录](../app/src/main/java/com/douai/module/config/ConfigurationCatalog.java) / [设置界面](../app/src/main/java/com/douai/module/ui/ModuleSettingsScreen.java) | 类型/依赖共目录、主要页面直达和下载定位 | 两套界面导航、持久值不变、关闭取消与备份兼容回归 |
| [头像加载](../app/src/main/java/com/douai/module/feature/AvatarImageLoader.java) / [成员列表](../app/src/main/java/com/douai/module/ui/MemberListView.java) | 有界解码、请求合并、复用与取消 | 合成图片/本地 HTTP 及行复用回归；真实网络与设备性能待验 |
| [平台烟测](../app/src/androidTest/java/com/douai/module/ui/PlatformSmokeTest.java) / [实际汇总](../artifacts/platform-smoke/smoke-aa5d93ecd2f84ae2842c01b300a27c6b/summary.json) | API 35 模拟器三项通过，保存单次耗时/堆快照及安装摘要 | 人工 SAF、手机宿主、外部接收应用与真实性能场景单独验收 |

本记录封存 0.2.5 的实现、验证与产物事实，后续修复另行记录。构建和复核方法继续使用[严格本地发布验收](releasing.md#严格本地发布验收)，该入口不创建标签、不上传远端。
