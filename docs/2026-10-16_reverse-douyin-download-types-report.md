# 抖音 40.6.0 下载媒体分类

分析日期：2026-10-16。分析方式：PowerShell、ripgrep，阅读现有 JADX Java / apktool smali。报告结构采用普通逆向分析（flavor = null）。

## 结论

官方保存流程明确区分普通视频和图集，并为 LivePhoto（实况照片）提供特殊保存分支。LivePhoto 不是与图集互斥的顶层作品类别：该下载链路将其作为图集图片项的能力继续处理。单张实况作品还有专门的保存选项菜单，可选择存为实况、存为视频或图片。

如果问题中的 Live 指直播，则需要分开理解。`Aweme.isLive()` 判断的是 `awemeType == 101`，不同于 LivePhoto。本次只追踪了作品保存链路，没有验证直播下载/录制能力。

## 输入与范围

| 属性 | 值 |
| --- | --- |
| 本地 APK | `抖音-40.6.0_40609900_20260922 (400601).apk` |
| SHA-256（本次重新计算） | `3983779738E1804A83E783CA447069E13F7686A30213EEBD32196915F601A0E8` |
| 本地 Java | `douyin-40.6.0/jadx-dex/classes29/sources/` |
| 本地 smali | `douyin-40.6.0/apktool-no-res/` |
| 授权与边界 | [scope.md](../work/download-types-20261016/scope.md) |
| 分析记录 | [timeline.md](../work/download-types-20261016/timeline.md) |

本文链接到的 `douyin-40.6.0/` 和 `work/` 是本地证据，不随公共仓库分发。此次复核了现存反编译文本及 APK 哈希，没有重新反汇编所有引用类，也没有进行真机验证。

## 类型与实现

| 类别 | 下载链路的判断 | 实现与含义 |
| --- | --- | --- |
| 旧图片类型 | `AbstractC34820xeA.LIZIZ(aweme)`，内部为 `awemeType == 2` | `DownloadAction` 创建 `1W4V` 并调用 `LJ(aweme, false)` |
| 普通视频路径 | 排除旧图片后，`awemeType != 68` | 创建 `1W4H`，经 `LJIJ` 等入口进入视频下载；此处为分流条件，不代表所有非 68 类型都是可下载普通视频 |
| 图集 / 图文 | `awemeType == 68`，使用 `aweme.images` | `C29100xNy` 处理；一张可直接保存，多张走 `MultiImageSelectionDialog` 选择 |
| LivePhoto / 实况 | 图片项有 `clipType == 5`；单张实况特殊菜单额外要求 `awemeType == 68 && images.size() == 1` | `showSaveDialogForLivePhoto`；`save_as_livephoto` 仍进入图集下载，`save_as_video` 进入图像序列转 MP4 流程 |
| 直播 | `Aweme.isLive()`：`awemeType == 101` | 独立的内容识别；本次未追踪直播录制/下载 |

这里能确认的是业务分流与保存模式。通用下载组件另有 `DownloadScene` 枚举，包含 `IMAGE / VIDEO / ZIP / PDF / APK / DEFAULT`，未包含 LivePhoto；不能把这个底层枚举等同于“图集、视频、LivePhoto”三种业务类别。

LivePhoto 也有实际任务分派：`1W4u` 逐项读取 `ImageUrlStruct.clipType`，通过 `0wZx.LJIIJJI` 判断值 5 后创建 `1W4q` 任务；对应执行器 `1W4n` 的日志明确包含 `DownloadLiveExecutor`、`download live photo` 和 `encode live photo`。因此它不仅是菜单名称，但最终容器和相册呈现仍未验证。

## Evidence

下列命令均从项目根目录执行。`content_hash = n/a` 表示以当前本地文本及行号作为定位证据。

| E-id | source_ref | repro_command | content_hash |
| --- | --- | --- | --- |
| E-001 | 原始 APK | `Get-FileHash -LiteralPath '抖音-40.6.0_40609900_20260922 (400601).apk' -Algorithm SHA256` | 上表 SHA-256 |
| E-002 | [ShareActionImpl.java:686](../douyin-40.6.0/jadx-dex/classes29/sources/com/ss/android/ugc/aweme/share/ShareActionImpl.java#L686)、[C28600xMa.java:225](../douyin-40.6.0/jadx-dex/classes29/sources/X/C28600xMa.java#L225)、`AbstractC34820xeA.java:9` | `rg -n 'LJJIIZI\|new C28600xMa' douyin-40.6.0/jadx-dex/classes29/sources/com/ss/android/ugc/aweme/share/ShareActionImpl.java; rg -n 'realDownload\|downloadImage\|getAwemeType\(\) != 68\|new 1W4H\|showSaveDialogForLivePhoto' douyin-40.6.0/jadx-dex/classes29/sources/X/C28600xMa.java; rg -n 'getAwemeType' douyin-40.6.0/jadx-dex/classes29/sources/X/AbstractC34820xeA.java` | n/a |
| E-003 | [AwemeUtils.java:307](../douyin-40.6.0/jadx-dex/classes29/sources/com/ss/android/ugc/aweme/feed/utils/AwemeUtils.java#L307)、[C0xW2.java:49](../douyin-40.6.0/jadx-dex/classes29/sources/X/C0xW2.java#L49) | `rg -n 'LJJJJ\(\|clipType != 5' douyin-40.6.0/jadx-dex/classes29/sources/com/ss/android/ugc/aweme/feed/utils/AwemeUtils.java; rg -n 'LJI\(\|getAwemeType\(\) != 68\|size\(\) != 1' douyin-40.6.0/jadx-dex/classes29/sources/X/C0xW2.java` | n/a |
| E-004 | [C28600xMa.java:618](../douyin-40.6.0/jadx-dex/classes29/sources/X/C28600xMa.java#L618)、[C29100xNy.java:167](../douyin-40.6.0/jadx-dex/classes29/sources/X/C29100xNy.java#L167) | `rg -n 'save_as_video\|save_as_livephoto\|select_photo\|current_photo' douyin-40.6.0/jadx-dex/classes29/sources/X/C28600xMa.java; rg -n 'multi_photo\|MultiImageSelectionDialog\|downPicCollection\|downloadImageListAndConvertToMp4' douyin-40.6.0/jadx-dex/classes29/sources/X/C29100xNy.java` | n/a |
| E-005 | [Aweme.smali:40124](../douyin-40.6.0/apktool-no-res/smali_classes16/com/ss/android/ugc/aweme/feed/model/Aweme.smali#L40124) | `rg -n -A 28 '\.method public final isLive\(\)Z' douyin-40.6.0/apktool-no-res/smali_classes16/com/ss/android/ugc/aweme/feed/model/Aweme.smali` | n/a |
| E-006 | [DownloadScene.smali:26](../douyin-40.6.0/apktool-no-res/smali_classes36/com/ss/android/ugc/aweme/download/component_api/DownloadScene.smali#L26) | `rg -n '\.field public static final enum' douyin-40.6.0/apktool-no-res/smali_classes36/com/ss/android/ugc/aweme/download/component_api/DownloadScene.smali` | n/a |
| E-007 | `smali_classes48/X/1W4u.1.smali:840`、`smali_classes29/X/0wZx.1.smali:1374`、`smali_classes48/X/1W4n.1.smali:928` | `rg -n -e clipType -e LJIIJJI -e 'new-instance.*1W4q' douyin-40.6.0/apktool-no-res/smali_classes48/X/1W4u.1.smali; rg -n -A 34 '\.method public static LJIIJJI' douyin-40.6.0/apktool-no-res/smali_classes29/X/0wZx.1.smali; rg -n -e DownloadLiveExecutor -e 'download live photo' -e 'encode live photo' douyin-40.6.0/apktool-no-res/smali_classes48/X/1W4n.1.smali` | n/a |

## Findings

| F-id | 结论 | severity | evidence_ids | confidence | location | status |
| --- | --- | --- | --- | --- | --- | --- |
| F-001 | 官方作品下载动作区分图片、视频、图集 | n/a_re | E-002, E-004 | high | `C28600xMa.LJIIZILJ`、`C29100xNy.LJII` | validated（静态） |
| F-002 | LivePhoto 是图集体系中的特殊项，有专门任务；单张实况有专门菜单 | n/a_re | E-003, E-004, E-007 | high | `AwemeUtils.LJJJJ`、`C28600xMa.LJIILLIIL`、`1W4u` | validated（静态） |
| F-003 | 直播 isLive 与 LivePhoto 为不同判断，未证明直播可通过上述路径保存 | n/a_re | E-003, E-005 | high | `Aweme.isLive` | validated（静态） |
| F-004 | 通用 DownloadScene 枚举不等于上述业务分类 | n/a_re | E-002, E-006 | high | `DownloadScene`、`C28600xMa` | validated（静态） |

## Path

P-001，`path_type=callflow`：

1. 分享或长按下载入口调用 `ShareActionImpl.LJJIIZI`，得到 `C28600xMa`（原类 `X.0xMa`，DownloadAction）。E-002 / F-001。
2. `LJIIZILJ` 先判断旧图片，再按 68 图集与视频路径分流。E-002 / F-001。
3. 图集分支识别单张实况，且相关实验开启时展示 `showSaveDialogForLivePhoto`。E-003 / F-002。
4. 用户选择后按 `save_as_livephoto / save_as_video / select_photo / current_photo` 分派；前者进入图集保存，视频选项进入 MP4 转换。E-004 / F-002。
5. 图集保存任务逐个识别图片项的 `clipType == 5`，分派专门的 LivePhoto 下载/编码任务。E-007 / F-002。

遗留边界：未验证各机型的实况容器、相册识别或实际下载成功率；未将下载入口或实验配置存在视为任意账号均可使用。

## 完成检查

- [x] 仅检查本地宿主反编译证据，未以模块逻辑代替抖音逻辑。
- [x] 核对视频、图集、单张实况与直播的区别，并保留静态分析边界。
- [x] 记录输入哈希、Evidence、Findings、调用 Path 和复现命令。
- [x] 未修改项目业务代码或 APK。
