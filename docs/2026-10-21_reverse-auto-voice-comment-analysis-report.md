# 自动语音评论消失与原生录音格式分析

> 历史样本说明：本文40.8分析对象是旧40809985构建，不能作为当前40809900的适配证据。用户已更换目标，当前以[9900重新适配记录](2026-10-21_douyin-40809900-adaptation-report.md)为准。

抖音40.6.0与40.8.0的原生评论录音均为 **AAC-LC编码、MPEG-4/M4A容器、44,100 Hz、单声道、96,000 bit/s**。用户确认目前使用MP3，模块评论路径会将提供商原文件交宿主上传器，没有Java侧主动转AAC的步骤，因此两者确有格式差异。这个差异值得做单变量转码对照，但不能单凭静态配置认定服务器仅接受AAC，或认定MP3就是历史语音消失的根因。本轮未修改生产/测试源码、未运行Gradle或操作设备。

## 范围与输入

[scope](../work/voice-empty-20261021/scope.md)限定本地日志、截图、当前0.2.7源码及两个原APK；`network_profile=offline`，普通业务逆向`flavor=null`。日志为`LSPosed_20261021_135248.zip`，SHA-256：`905136925799bbea3c3d56fa63a0bfaa3f8f556156a9582d431c8736f84e2c38`。工作区提交为`79d36d4`。

用户最初反馈40.6、40.8均测试过，随后明确更正：同类语音在40.6版本可以显示，问题与版本有关。因此不能再将两版均不显示作为结论，也不能据此认定历史媒体已经被服务器删除。“自动回复”指评论区回复。截图显示自评行保留身份与时间，但没有文本或语音容器。截图不能独立说明服务器是否保存媒体，也没有可与日志精确关联的CID。

以下`work/`与原APK属于本工作区证据，不随源码分发。证据摘要见[evidence.json](../work/voice-empty-20261021/evidence.json)。没有保存账号、评论/音频标识、正文、Key或原始发布参数。

## 原生评论录音格式

| 项目 | 40.6.0、40.8.0原生配置 |
| --- | --- |
| 编码 | AAC-LC，MIME `audio/mp4a-latm` |
| AAC profile | `2`，本机Android SDK常量`AACObjectLC=2` |
| 输出容器 | MPEG-4音频容器，扩展名`.m4a` |
| Muxer格式 | `0`，SDK常量`MUXER_OUTPUT_MPEG_4=0` |
| 采样率 | 44,100 Hz，即指令中的`0xac44` |
| 声道 | 1，单声道 |
| 编码码率 | 96,000 bit/s，即`0x17700` |

两版配置方法分别为`X.0yaE.LJII(I)V`和`X.147B.LJII(I)V`。独立从原DEX提取的smali中，两个类的配置锚完全同形：2160行MIME、2164行采样率、2169/2173行声道与createAudioFormat、2190/2194行profile、2202/2206行码率、2343行扩展名、2394/2453行MediaMuxer输出格式。

- [40.6原smali](../work/root-voice-empty-20261021/406/original/X/0yaE.smali)，SHA-256 `000c12703e04939505cd03cfd6e4b9cbf675d5af2ebbe20a34280878428571e0`。
- [40.8原smali](../work/root-voice-empty-20261021/408/original/X/147B.smali)，SHA-256 `4459064d9f61ba02ba8902b2393a139c1182af9d9a5cb9e358ecb09794ad2142`。
- [结构化格式记录](../work/voice-empty-20261021/native-format.json)。

录音时长的单位也已闭环：两版录音器用`uptimeMillis - start`，直接box成Long，经RECORD_COMPLETE、预览组件传入AudioModel，未除1000。模块传`MediaMetadataRetriever`取得的毫秒，单位本身一致。原生波形来自录音采样列表的分组均值；模块自动评论使用空列表。宿主构造器允许空列表，不代表服务器一定接受空波形，也不能用当时的成功回调证明长期保留成功。

## 当前MP3上传路径

`SpeechSynthesis.java:99`将提供商响应字节写入本地文件；`SpeechPreparation.prepareComment`传`encoder=null`，77–80行原样返回同一文件；`NativeCommentPublisher.java:198`将该File交宿主音频模型。`CommentVoiceHook`对模块合成文件直接放行。

两版Java上传链将输入路径交`BDVideoUploader.setPathName`后启动，没有发现先创建另一份AAC文件的步骤。40.6上传协程`ASLambdaS28S1301000_35`的3021/3194行与40.8 `ASLambdaS29S1301000_36`的3468/3641行是对应锚。SDK最终进入native，native及云端内部是否转码尚未验证。

因此，MP3改后缀为`.m4a`不会变成原生格式；有效对照需要真正编码AAC-LC并封装M4A。私信语音有独立AAC转码，但本次用户说的是评论区回复，不用私信路径替代本次对照。

## 新日志说明了什么

模块0.2.7下，模块日志去除verbose镜像后有19次语音提交、19次上传接管。40.6共13次，其中12次`code=5`，另1次`code=0/audio_receipt=present`；40.8共6次，6次均为后者。7次成功回调的本地时长为16.164–25.884秒，不能把这批空白现象只归因于“超过30秒”。长音频code 5发布失败与成功回调后语音消失是两类问题。

[脱敏timeline.json](../work/voice-empty-20261021/timeline.json)保存文件编号与行号。`f1`为`log/modules_2026-10-21T11-35-43.314062.log`；40.6阳性回调在4714行，40.8阳性回调在5462、7012、8012、8644、8684、8788行。日志里另有4次文本回退成功、3次文本code=3056失败，不能把用户当前文字显示正常扩大成所有历史文本请求均成功。

`sent`只校验状态码、评论ID和目标关系，不要求音频；`audio_receipt`是在宿主回调处理后检查对象的vid非空与正时长，没有重新拉取评论。本地直接运行当前生产类的[离线回放](../work/voice-empty-20261021/source-replay/result.txt)得到：

```text
root_empty_media: status=sent; audio_receipt=missing
reply_empty_media: status=sent; audio_receipt=missing
same_root_after_local_patch: status=sent; audio_receipt=present
```

回放证明成功判断有媒体确认盲区，第三行只是人为补字段的诊断反例。本轮核查两版成功链的`LJIJI`、`BaseCommentNetRequest`及doOnNext，没有找到将fakeComment音频回填response.comment的写入，不能声称宿主实际发生了这种补齐，更不能用这个回放冒充用户UI故障复现。

## Evidence → Findings → Path

| Evidence | source_ref / 复核方式 | content_hash |
| --- | --- | --- |
| E-log | 新ZIP及timeline中的文件/行号；按profile、upload、callback聚合 | ZIP摘要见上；逐文件见evidence.json |
| E-format | 两版原录音器smali LJII；搜索`audio/mp4a-latm|aac-profile|0xac44|0x17700|m4a|MediaMuxer` | 两类摘要见上 |
| E-wire | 两版CommentRealApi的622/627/632行；CommentPublishRequest的3559/3588/3617（406）及3555/3584/3613（408） | 原始本地smali；本段多文件观察n/a |
| E-ui | AudioCommentComponentV2 406:983/408:1236检查commentAudio；两版182及201–206行决定GONE | 本地原smali，多文件观察n/a |
| E-source | NativeCommentPublisher/SpeechPreparation/CommentVoiceHook；source-diff-findings.json及离线回放 | evidence.json绑定源码分析与回放摘要 |

| Finding / severity | evidence_ids | confidence / location / status |
| --- | --- | --- |
| F-format / n/a_re | E-format,E-source | 高；原生AAC-LC/M4A与当前MP3输入不同；静态配置已确认，根因未确认 |
| F-initial / n/a_re | E-log,E-wire | 高；两版均进入音频上传并有初次媒体回调；已确认，不等于后续保留 |
| F-status / n/a_re | E-source | 高；无音频也能被判sent；诊断盲区已复现，未修改成功策略 |
| F-container / n/a_re | E-ui | 高；该音频组件先以commentAudio存在与否决定挂载。未知列表/绑定时对象究竟在哪一层丢失 |
| F-cause / n/a_re | E-log,E-ui,E-source | 未确认；缺同CID重新加载的数据，不能断言风控、MP3拒绝、空波形拒绝或渲染Bug |

P-1（callflow）：TTS文件 → 宿主AudioModel → 上传取VID → `audio_vid/audio_duration/audio_wave` → 发布回调 → 评论重新拉取 → DTO/界面语音容器。当前缺口在发布之后到重新展示之间。根与评论回复共用NativeCommentPublisher，差异为回复目标p/b/e、来源标记n与work/context；没有独立的根评论音频清空分支。

P-2（callflow）：原生按住录音 → 44100 Hz单声道PCM采集 → AAC-LC/96000编码 → MPEG-4/M4A → 同类原生上传。编码格式证据是客户端实际配置，不是服务器格式白名单。

时间线：13:22起日志选择40.6；13:33有该版本音频成功回调；13:34后选择40.8并记录6次音频成功回调。随后用户报告历史自动语音均不显示，手动语音与文字仍在，并明确自动回复指评论区；最后确认当前使用MP3。本轮据此完成只读双APK格式与发布/显示链核对。

## 最小后续对照

先用已有评论工具长按一条空白自动语音与一条正常手动语音，选择“复制评论源数据”，只对比`comment_audio`是否存在及其结构；它是当前客户端模型，不应再称原始HTTP响应。源码位置是`CommentToolsHook.java:143`。不需要先重复发送评论。

- 当前模型无`comment_audio`：继续抓同CID的原始列表响应，区分服务端未返回与客户端解析丢失。
- 当前模型有`comment_audio`：优先查绑定/容器可见性和自定义隐藏配置；关闭模块后重启并刷新同条评论可提供独立UI对照。
- 格式对照使用同一段源音频，真正转成AAC-LC/M4A、44.1kHz单声道96kbps，再观察发布回调与重新加载后是否仍有媒体。不能靠改扩展名，也不能同时改时长/内容/码率后把结果只归因于格式。

仅凭当前日志不能确认语音在40.8不显示的具体根因。以上是0.2.7源码的只读分析记录；后续按用户最终要求保留评论原文件发送，不做本地转换，仅新增MiniMax实验AAC API选项与设置分组，见[0.2.8本地记录](release-0.2.8.md)。原生格式差异不代表40.8显示问题已修复。
