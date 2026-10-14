# 抖音 40.6.0 评论语音限制分析

分析日期：2026-10-14。目标为本地抖音 APK 的普通作品评论语音，涵盖录音、入口、上传、发布和转文字。本文不分析模块实现，也不把其他产品的私信、直播语音规则套用于普通评论。

本文的 `work/` 和 `douyin-40.6.0/` 路径是本地证据位置，第三方 APK、反编译文件和日志不随公共仓库分发；对应链接仅在包含这些本地输入的工作区可用。

## 结论

抖音客户端默认只录制 1 至 30 秒的评论语音，25 秒后提示倒计时；这些值来自 `comment_audio_config`，可以由设置覆盖。最长录音通过界面计时器到期停录实现，最短录音在控制器停止时再次校验。这个 30 秒是已经确认的客户端录音限制，不能直接当成已确认的服务端发布上限。

语音入口受实验、作品下发权限、回复设置、场景和作品创建时间控制。正常录音输出 AAC-LC / M4A，44.1 kHz、单声道、96 kbit/s。本次检查的 Java/smali 上传和发布路径没有发现按音频时长、总文件字节数或扩展名进行的明确硬校验；上传 native SDK 和服务端的限制仍未知。上传成功也不等于评论已发布、可被他人看见或可播放。

## 范围与样本

范围见 [scope.md](../work/comment-voice-limits-20261014/scope.md)。授权来自用户查看本地反编译文件的请求；`network_profile=offline`。没有进行设备操作、网络请求、实账号评论测试或样本修改。

| 属性 | 值 |
| --- | --- |
| 样本 | `抖音-40.6.0_40609900_20260922 (400601).apk` |
| 样本大小 | 358,852,038 字节 |
| APK SHA256 | `3983779738e1804a83e783ca447069e13f7686a30213eebd32196915f601a0e8` |
| `classes30.dex` SHA256 | `7a06923174217bf8d8846ed2bb46762125e750510e2bd6eb3c5121214f1e80ba` |
| 静态工具 | ripgrep、PowerShell、Java 21、apktool.jar 内置 dexlib2/baksmali |
| 证据核验 | 直接从 APK 重新反汇编 9 个关键类，与现存对应 smali 的 SHA256 完全一致 |
| 真实性边界 | 核对的是所提供 APK 与反编译文件的一致性，未验证发行签名或官方渠道来源 |

本次普通业务分析以 DEX 类及 Android 媒体 API、Retrofit 注解作为依赖/调用锚点，不涉及 native 导入表分析。关键锚点是 `AudioRecord`、`MediaCodec`、`MediaMuxer`、`CountDownTimer`、`SettingsManager`、`ABManager`、`BDVideoUploader` 和 `CommentRealApi`。

## 录音限制

| 项目 | 客户端默认值/行为 | 配置或位置 |
| --- | --- | --- |
| 最长录制 | 30 秒 | `comment_audio_recorder_max_second` |
| 最短录制 | 1 秒；低于阈值删除录音并回报 `RRCORD_ERROR`，错误码 `-1` | `comment_audio_recorder_min_second` |
| 倒计时提示 | 录到约 25 秒后提示剩余约 5 秒 | `comment_audio_recorder_threshold_second` |
| 回复禁语音 | 默认 0；设为 1 且正在回复时隐藏入口 | `reply_comment_forbid_provide_audio_comments` |
| 采集 | 44,100 Hz、单声道、PCM 16 bit | `AudioRecord(…,44100,16,2,…)` |
| 编码 | AAC-LC、44,100 Hz、单声道、96,000 bit/s | `audio/mp4a-latm`、`aac-profile=2` |
| 容器 | MPEG-4 音频容器，`.m4a` 文件 | `MediaMuxer(path,0)` |
| 文件大小 | 本次检查的录音/业务上传路径未找到明确总大小上限 | `max-input-size` 是编码缓冲区，不能当总文件上限 |

配置字段及默认值见 [0yaR.2.smali](../douyin-40.6.0/apktool-no-res/smali_classes30/X/0yaR.2.smali) 第 14、23、29、35、114、130、145 行；设置入口见 [0yaQ.2.smali](../douyin-40.6.0/apktool-no-res/smali_classes30/X/0yaQ.2.smali) 第 7 行，实际读取 `SettingsManager.getValueSafely` 见 `smali_classes30/Y/AObjectS343S0000000_35.smali:1075`。静态默认值不等于任意账号当前收到的配置值。

最长录制执行路径有两套界面：

1. `AudioCommentRecordingAnimModule2:168` 用 `0yaQ.LIZ()` 返回的最大毫秒数建立 `0yRr` 计时器。到期后经 `0yRt.run()` 调用 `AudioRecordAnimView.LJIIIIZZ()`，最终 `0yaE.LJIIJJI(false)` 停录。
2. `AudioCommentRecordingAnimOptModule:329` 使用同一最大值建立计时器；`0yRq` 到期调用 `LJJIJL(false)`，停止同一录音控制器。

最短录制检查在 [0yaE.2.smali](../douyin-40.6.0/apktool-no-res/smali_classes30/X/0yaE.2.smali) 第 1434 行起，比较 `uptimeMillis() - startTime` 与 `minSecond * 1000`。音频采集在第 615 行起，编码在第 2155 行起，文件扩展名在第 2343 行。96 kbit/s 录 30 秒约 360 KB 再加容器开销，这是估算，不是硬性大小阈值。

## 入口限制

主判断是 [0yJf.1.smali](../douyin-40.6.0/apktool-no-res/smali_classes30/X/0yJf.1.smali) 第 291 行的 `LJ(LX/0yK6;)Z`。该方法返回 false 表示这个评论键盘路径不展示语音能力，并不单独证明服务端会拒绝同类请求。

| 条件 | 入口行为 | 证据位置 |
| --- | --- | --- |
| `comment_support_provide_audio_comments` 的整数实验值不等于 1 | 隐藏 | `smali/X/01EJ.smali:8`；`smali/Y/AObjectS308S0000000.smali:87783`；`0yJf.1:400` |
| XRTC 音视频通话中，或音视频 Feed 共享页 | 隐藏 | `0ye7.1.smali:243`；`0yJf.1:388` |
| 输入参数 `D.s == true` | 隐藏；本次未给该混淆字段强行命名 | `0yJf.1:422` |
| 热点相关上下文 `0xr4` / `0yAT` | 隐藏 | `0yJf.1:462`、`:483` |
| 回复禁语音设置等于 1，且有被回复评论 | 隐藏 | `0yJf.1:491` |
| `audioCommentPermission == 1` 或 `blockAudio == true` | 隐藏 | `0yAP.smali:3049`；调用点 `0yJf.1:561` |
| 上下文能力 `0yEu.LJ()` 为 false | 隐藏 | `0yJf.1:584` |
| `0 < createTime < 0x5bcf7dc0` | 隐藏 | `CommentExtensionsKt.smali:4185`；`0yJf.1:636` |
| POI 融合评论或启用电商融合设置时的特定 1201 评论类型 | 隐藏 | `0yJf.1:351`、`:376`；`0xyq.1.smali:130` |
| 未取得 `android.permission.RECORD_AUDIO` | 录音受 Android 权限约束 | `AudioCommentRecordBtnModule2.smali:74` |

`audioCommentPermission` 对应 JSON 字段 `audio_comment_permission`；`blockAudio` 对应 `block_audio`。分别见 `smali_classes16/com/ss/ugc/aweme/FeedCommentConfigStruct.smali:33` 和 `CommentBlockConfigStruct.smali:45`。其中值 1 在此处走的是禁止分支，不能按字段英文名直觉解读为允许。

作品创建时间阈值 `0x5bcf7dc0` 为 Unix 秒时间戳 1540324800，即北京时间 2018-10-24 04:00:00；条件要求创建时间大于 0，创建时间未知/为 0 不命中这一分支。

这条入口判断中未发现直接按年龄、粉丝数或账号等级作比较的代码，不能反推服务端没有账号资格限制。也未找到普通图文一概禁止语音的判断。Story 路径存在启用音频能力的配置，不能断言 Story 一概禁用；具体还要经过上述门控。

## 上传与发布限制

调用链为：

```text
CommentPublishRequest
  -> AudioUploader.LJFF(localPath, callbacks)
  -> 复用未过期授权，或 GET /aweme/v1/comment/upload/auth/?content_type=1
  -> BDVideoUploader.setPathName(localPath)
  -> native uploader.start()
  -> 回调拿到 mVideoId，写入音频模型 vid
  -> POST /aweme/v1/comment/publish/
       audio_vid + audio_duration + audio_wave
  -> 处理发布响应、风险提示或失败
```

上传授权含 `access_key`、`secret_key`、`session_token`、`expired_time` 等。客户端有效期判断是 `currentTimeMillis() < expired_time * 1000`，缓存键为 `comment_audio_upload_auth_config`。这里没有实际凭证取值。

实际上传协程在 `smali_classes30/kotlin/coroutines/jvm/internal/ASLambdaS28S1301000_35.smali:2670` 起，variant 0。第 3021 行传路径，第 3074 行配置 `aweme_comment` 空间，第 3117 行设置 512 KiB 分片，第 3194 行启动上传。512 KiB 是分片大小，不能解释为文件总大小上限。

[CommentRealApi.smali](../douyin-40.6.0/apktool-no-res/smali_classes30/com/ss/android/ugc/aweme/comment/api/CommentRealApi.smali) 第 622、627、632 行声明 `audio_vid`、`audio_duration`、`audio_wave`，第 796 行声明发布接口，第 3127 行声明取授权接口。`CommentPublishRequest` 从音频模型直接取时长及波形；本次检查的这条路径没有发现发布前再次比较 30 秒的代码。

因此目前能确认的是：

- 文件先上传，再提交评论，两个阶段都可能失败。
- 这条业务 Java/smali 路径没有找到明确的总字节数上限、后缀白名单、1 秒/30 秒发布校验。这里的“未找到”仅限已追踪路径。
- 最终上传转入 `BDVideoUploaderBase` 的 native 实现；本次没有反编译该 native SDK。
- 发布响应存在 `left_quota`、`comment_publish_retry_time_interval`、`risk_remind_info`、`second_verify_type` 等通用字段，见 `smali_classes30/X/0y7U.smali:57` 起。这说明客户端处理服务端配额、重试及风险结果，但不能据此虚构语音专属次数、审核或时长规则。

AAC/M4A 是原生录音输出格式，不是已经证实的服务端唯一接受格式。超过 30 秒的文件能否上传、发布、被他人播放，以及服务端是否重新计算/裁剪时长，本次静态材料无法确定。

## 转文字

消费端另有 `comment_consume_audio_to_text` 实验开关，默认 true，见 `smali_classes30/X/0yF5.smali:8`。转写使用 `GET /aweme/v1/comment/audio_asr/`，参数 `comment_id`，见 `CommentRealApi.smali:3466`。

`comment_consume_audio_to_text_asr_timeout` 默认值为 15 秒，见 `smali/X/00vM.2.smali:8`、`:49`，调用点是 `AudioCommentComponentV2.smali:740` 后的异步超时配置。这个 15 秒属于等待转写响应的时间，不能当作语音文件时长限制。

## APK 一致性复核

现有 `0yQy.1.smali` 包含 `local_test` / `lark_inhouse` 渠道下读取 `/data/local/tmp/comment_audio_test.m4a` 的测试分支。直接从所提供 APK 的 `classes30.dex` 重反汇编后，该分支仍存在，且匹配整类 SHA256。因此不能把它直接认定为现有反编译目录被修改的证据，也不能据此声称普通生产渠道可导入固定音频。

复核工具为 [VerifyVoiceDex.java](../work/comment-voice-limits-20261014/VerifyVoiceDex.java)，输出在 `work/comment-voice-limits-20261014/original-sequential/`。重新反汇编时使用顺序标签，与原目录匹配。

| 类 | 重新反汇编 SHA256 | 与现存 smali 一致 |
| --- | --- | --- |
| `LX/0yaE;` | `000c12703e04939505cd03cfd6e4b9cbf675d5af2ebbe20a34280878428571e0` | 是 |
| `LX/0yaR;` | `f6df7f0dce1046aa3db7e4ed1607602caf06348e4b499b8ccd7382e41d3996ff` | 是 |
| `LX/0yaQ;` | `8f3bcf3559d612844682d2bc9150892d73511ac7694841bcb7e27c9d8f9c422b` | 是 |
| `LX/0yQy;` | `eb4830b79f58dd9fca6977e0f6feec046e00f157d8db311f70b6078cec38d156` | 是 |
| `LX/0yJf;` | `a3b7c62d9bb1a9b927bd46f01a2995e3060368149f65e9d6c490c32539ad54d1` | 是 |
| `LX/0yAP;` | `b524fb21cb02ea4b0809a31a9d746394dd31bd2a26a54547132405164936f01a` | 是 |
| `AudioUploader` | `6a8ed4bfe841f548500a17367b7bf6cdfbd3a568b5c01354c28f3856b218adfc` | 是 |
| `CommentRealApi` | `3fc49134c19154fa2aab90d356991f0a0e274a46e285b4d31e47a5bec7bb3afd` | 是 |
| `CommentPublishRequest` | `358d2b5367d27036ed7fddb6fe0292316d7cfe8b1209755097108e738969d2c1` | 是 |

## Evidence / Findings / Path

以下证据均来自 2026-10-14 本地静态读取，`source_type=file`。除 E-001 的样本/上表固定哈希外，表中 `content_hash=n/a`，因为该项是多文件观察而非单个冻结附件。没有动态发布成功证据。

| E-id | source_ref | repro_command（在项目根目录运行） | content_hash |
| --- | --- | --- | --- |
| E-001 | APK 与 `original-sequential/` | 见下方直接 DEX 复核命令；`Get-FileHash -Algorithm SHA256 -LiteralPath '抖音-40.6.0_40609900_20260922 (400601).apk'` | 见样本表及类哈希表 |
| E-002 | `smali_classes30/X/0yaR.2.smali`、`0yaQ.2.smali`、`0yaE.2.smali` | `rg -n 'comment_audio_recorder|0x1e|0x19|RRCORD_ERROR' douyin-40.6.0/apktool-no-res/smali_classes30/X -g '0ya*.smali'` | n/a |
| E-003 | `0yJf.1`、`0yAP`、`CommentExtensionsKt` | `rg -n 'audioCommentPermission|blockAudio|5bcf7dc0|LJIJI' douyin-40.6.0/apktool-no-res/smali_classes30/X/0yAP.smali douyin-40.6.0/apktool-no-res/smali_classes30/com/ss/android/ugc/aweme/comment/util/CommentExtensionsKt.smali` | n/a |
| E-004 | `0yaE.2.smali` | `rg -n 'audio/mp4a-latm|aac-profile|bitrate|m4a|MediaMuxer|AudioRecord' douyin-40.6.0/apktool-no-res/smali_classes30/X/0yaE.2.smali` | n/a |
| E-005 | 上传协程、`CommentRealApi`、`CommentPublishRequest` | `rg -n 'setPathName|setSliceSize|fetchUploadAuth|start\(' douyin-40.6.0/apktool-no-res/smali_classes30/kotlin/coroutines/jvm/internal/ASLambdaS28S1301000_35.smali` | n/a |
| E-006 | `0yF5`、`00vM.2`、`AudioCommentComponentV2` | `rg -n 'comment_consume_audio_to_text|0xf' douyin-40.6.0/apktool-no-res/smali/X/00vM.2.smali douyin-40.6.0/apktool-no-res/smali_classes30/X/0yF5.smali` | n/a |

| F-id | 结论 | severity | evidence_ids | confidence / status | location |
| --- | --- | --- | --- | --- | --- |
| F-001 | 客户端默认 1 至 30 秒录音，可由设置覆盖 | n/a_re | E-001,E-002 | high / validated（静态行为） | `0yaR`、`0yaQ`、录音计时器及控制器 |
| F-002 | 入口有实验、作品、回复、场景及创建时间门控 | n/a_re | E-001,E-003 | high / validated（静态行为） | `0yJf.LJ`、`0yAP.LJIJI` |
| F-003 | 原生输出为 AAC-LC / M4A，44.1 kHz 单声道 96 kbit/s | n/a_re | E-001,E-004 | high / validated（静态行为） | `0yaE.LJII` |
| F-004 | 未找到该业务路径的发布时长/总大小硬校验；不能推出服务端无限制 | n/a_re | E-001,E-005 | medium / candidate（覆盖范围有限） | 上传协程、`CommentPublishRequest` |
| F-005 | ASR 另有开关与默认 15 秒响应超时 | n/a_re | E-006 | high / candidate（未作独立 DEX 复核及动态验证） | `00vM`、`AudioCommentComponentV2` |

P-001，`path_type=callflow`：键盘资格判断（E-003/F-002） -> 录音与计时停录（E-002/F-001） -> AAC/M4A 编码（E-004/F-003） -> 授权上传 -> 填充 vid/时长/波形 -> 发布及响应（E-005/F-004）。起点是用户录制评论语音，终点是客户端处理服务端发布响应；残余不确定性为 native SDK 校验、实际下发设置、服务端规则及发布后的可见性。

Timeline：2026-10-14 定位现存反编译产物；同日按用户补充限定为抖音自身；同日并行复核录音、入口和发布路径；同日完成 9 类 APK 直接反汇编一致性检查并形成报告。

## 复核命令

以下 PowerShell 命令从原 APK 读取指定 DEX，写入独立证据目录，不更改原 APK：

```powershell
java -classpath 'C:\Users\95300\Tools\apktool\apktool.jar' `
  'work/comment-voice-limits-20261014/VerifyVoiceDex.java' `
  '抖音-40.6.0_40609900_20260922 (400601).apk' classes30.dex `
  'work/comment-voice-limits-20261014/original-sequential' `
  'LX/0yaE;' 'LX/0yaR;' 'LX/0yaQ;' 'LX/0yQy;' 'LX/0yJf;' 'LX/0yAP;' `
  'Lcom/ss/android/ugc/aweme/comment/helper/AudioUploader;' `
  'Lcom/ss/android/ugc/aweme/comment/api/CommentRealApi;' `
  'Lcom/ss/android/ugc/aweme/comment/data/publish/CommentPublishRequest;'
```

该辅助程序适配本机 apktool.jar 暴露的 dexlib2 API，其他版本可能需要调整。第 1 次尝试因 API 差异编译失败，修正后上述命令退出码为 0。对比时需采用相同标签风格；标签命名不同会导致文本哈希不同，即使业务指令一致。
