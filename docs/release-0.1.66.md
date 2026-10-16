# 0.1.66：修复官方下载的实况选项遗漏

版本：`0.1.66 / 67`，本地交付，基于 0.1.65。本次修复两个已通过失败测试复现的遗漏：部分动态播放地址未读取；只检查图片级 `clip_type=5`，未处理宿主的作品级实况标记。图集中的实况现在可以跨页选择或全部保存。明确标记为实况的图片在缺少动态来源时也保留选项，点击后说明原因。

用户原始作品的模型字段没有出现在所提供的日志中，因此尚不能确定该作品具体命中了哪一种遗漏，也不能把本地验证等同于原作品已经通过真机验收。

## 诊断范围

[scope.md](../work/download-live-diagnosis-20261016/scope.md) 记录用户授权、离线范围与未验证边界；[timeline.md](../work/download-live-diagnosis-20261016/timeline.md) 记录排查顺序。`work/`、`douyin-40.6.0/`、`artifacts/` 是本地证据与产物目录，不随公共源码分发。

输入为 `LSPosed_20261016_223943.zip`，SHA-256：`3C98170A230DFC1CE81DE454692838AA799EDF0F3EA1BF815839461F8602502D`。仅流式读取其中四份模块/详细日志，没有执行附件内容，没有解压或公开原始日志、完整链接、评论正文及凭证。

## 日志能证明什么

以下行号来自 ZIP 中 `log/modules_2026-10-16T20:07:15.755419.log`：

- 6694：22:22:53 会话加载 `DouAi 0.1.65 / API 102`；更早会话中的 0.1.64 不作为本次运行版本。
- 6707、6709、6737：扫描完成，下载扩展已启用，手势工具已注册。
- 7183、8132、8912、9035：右上长按处理了 `action=save_media`。
- 8583：同一作品的自动评论请求使用了 4 张图。这是自动评论输入数量，不能作为原始 `aweme.images` 总数、当前图位置或实况类型证据。

日志未包含宿主版本、`clip_type`、`is_live_photo`、`live_photo_type`、`photosCurPos` 或实况动态地址。没有发现可关联到本次下载分类的异常。自动点赞安装异常单独存在，没有证据将其归为这次问题的原因。

## 修复

1. [DownloadActionHook](../app/src/main/java/com/douai/module/feature/DownloadActionHook.java) 的共享播放地址解析增加 `play_addr_bytevc1`、`play_addr_lowbr`、`bit_rate[].play_addr`、`bit_rate[].play_addr_265`、`bit_rate[].play_addr_bytevc1`，保留原有来源优先级。
2. [OfficialMediaModel](../app/src/main/java/com/douai/module/feature/OfficialMediaModel.java) 统一菜单和保存计划的判断：图片 `clip_type=5`，或作品 `is_live_photo=1` 且该图片存在真实可读动态来源。作品标记不把没有动态来源的静态页变成实况。
3. [OfficialDownloadDialog](../app/src/main/java/com/douai/module/feature/OfficialDownloadDialog.java) 扫描整组图片，增加“选择实况保存／保存全部实况”；仍保留当前图片、选图和全部图片。实况文件名保留图片在原图集中的序号。
4. 选择多张实况时，先检查所有选中来源，再创建任务。缺来源、空选、取消或作品已失效时不开始保存。
5. `clip_type=1/3/4` 且有可读视频的当前图片可以保存动态视频；没有独立实况标记时不作为实况。
6. 实况保存计划的去水印默认值与菜单保持一致，最终仍需宿主编码器确认输出为真正实况。旧版的编码、取消和机型限制见 [0.1.65](release-0.1.65.md)。

新增持久的分类诊断行 `Official download choices`：实际宿主包/版本、图片数、当前序号、作品实况标记、最多 32 项图片类型/播放类型、可读动态来源数量、实况数量和固定菜单标签。它用于后续同类问题核对，不输出媒体地址、凭证或作品正文。

## Evidence → Finding → Path

| E-id | source_ref | repro_command | content_hash |
| --- | --- | --- | --- |
| E-001 | 用户提供 ZIP 与上述启动/手势行号 | `Get-FileHash -LiteralPath 'F:\qq\MobileFile\LSPosed_20261016_223943.zip' -Algorithm SHA256`；使用 .NET ZipArchive 按指定条目流式阅读 | 上述 SHA-256 |
| E-002 | `smali_classes55/.../Video.smali:169,213,501`、`BitRate.smali:86` | `rg -n -e play_addr_bytevc1 -e play_addr_lowbr -e bit_rate douyin-40.6.0/apktool-no-res/smali_classes55/com/ss/android/ugc/aweme/feed/model/Video.smali` | n/a |
| E-003 | `smali_classes16/.../Aweme.smali:2922`；`PhotosServiceImpl.java:1160` | `rg -n is_live_photo douyin-40.6.0/apktool-no-res/smali_classes16/com/ss/android/ugc/aweme/feed/model/Aweme.smali`；阅读宿主 `com/bytedance/ies/ugc/aweme/photos/service/PhotosServiceImpl.java` | n/a |
| E-004 | `LivePhotoRecognitionRegressionTest`，两次红测与修复后回归 | 下方 Gradle 命令；本地日志 `download-live-066-red.log`、`download-live-066-work-marker-red.log`、`download-live-066-focused.log` | n/a |
| E-005 | `OfficialGalleryLiveSelectionTest` | 同下方 Gradle 命令；跨页、多选、缺来源、失效及元数据日志边界断言 | n/a |

| F-id | Finding | severity | evidence_ids | confidence | location | status |
| --- | --- | --- | --- | --- | --- | --- |
| F-001 | 用户实际运行 0.1.65，save_media 已触发；日志无法还原图片模型或确认设备宿主版本 | n/a_re | E-001 | high | 日志行号见前文 | observed |
| F-002 | 旧来源解析能使明确实况只显示图片，新增来源已通过相同用例回归 | n/a_re | E-002, E-004 | high | `DownloadActionHook.playbackUrl` | validated（本地） |
| F-003 | 宿主作品级实况标记与旧图片级判断不同，已统一菜单和计划 | n/a_re | E-003, E-004 | high | `OfficialMediaModel` | validated（本地） |
| F-004 | 只看当前图片会遗漏其他页实况，跨页选择与缺来源提示已覆盖 | n/a_re | E-004, E-005 | high | `OfficialDownloadDialog` | validated（本地） |
| F-005 | 用户原作品究竟使用哪个字段仍待新版本运行证据 | n/a_re | E-001 | medium | 原作品模型未提供 | candidate |

P-001，`path_type=callflow`：手势 `save_media`（E-001）→ 官方选择框 → 共享作品/图片实况判断（E-003/F-003）→ 读取图片自身动态来源（E-002/F-002）→ 单张或跨页选择并预检（E-005/F-004）→ 原生实况编码与结果校验 → 同一命名和保存目录。原始来源缺失时停在预检阶段，不静默改为图片保存。

## 复现与本地验收

```powershell
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$env:DOUAI_TEST_APK = (Get-ChildItem -File -Filter '抖音-40.6.0*.apk' | Select-Object -First 1).FullName
.\gradlew.bat :app:testDebugUnitTest --tests com.douai.module.feature.LivePhotoRecognitionRegressionTest --tests com.douai.module.feature.OfficialGalleryLiveSelectionTest --console=plain
.\gradlew.bat :app:lintRelease :app:assembleRelease --console=plain
$env:DOUAI_VERIFY_RELEASE_APK = (Resolve-Path 'app/build/outputs/apk/release/app-release.apk').Path
.\gradlew.bat :app:testDebugUnitTest --console=plain
```

本地验收包含宿主形状的 Gson 注解字段、原始 APK DEX 字段核验、选择框与编码计划共用来源、多张实况预检、静态页不误判和日志隐私边界。未进行真机安装、该链接的真实下载或相册识别验收；需要安装新包后重启抖音复测该作品。

## 构建产物

- 全量回归：132 个测试套件、1116 项全部通过，0 失败、0 错误、0 跳过；包含原宿主 DEX、最终 APK 契约、实况字段 ABI 和三项原生弹窗渲染检查。
- 针对性回归：114 项通过，0 失败、0 错误、0 跳过。
- R8 Release 构建成功；Release Lint 为 0 错误、48 警告。
- 本地 APK：`artifacts/DouAi-0.1.66-release.apk`，4,140,353 字节。
- APK SHA-256：`BCB3F5F1AD99115B7D726EE502566CF8DB5EA17864C983C55BECCFA0CB8BE7CB`。
- v3 签名通过，证书 SHA-256 为 `FAE67C134F4C4FA73B8E34487C47A8C7F3E7C34B840BC245AFAC51585B7DFD47`，与 0.1.65 签名结果直接比较一致。
- 未发布远端 Release。

验证汇总为本地 `artifacts/download-live-066-validation.json`。归档 APK 与最终全量测试所用 Release 的 SHA-256 相同，完整测试日志为 `artifacts/download-live-066-full-tests.log`。
