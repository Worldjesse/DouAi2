# 抖音40.6 / 40.8自动语音评论差分

> 历史样本说明：本文40.8分析对象是旧40809985构建，不能作为当前40809900的适配证据。用户已更换目标，当前以[9900重新适配记录](2026-10-21_douyin-40809900-adaptation-report.md)为准。

当前没有找到能直接解释“40.6显示、40.8空白”的发送参数或模型字段变更。本次从两个原APK重新比较了模型指令、完整发布API、上传配置、原生库、音频配置、DTO与音频组件，而非只复用规则唯一命中结果。上传回调结构和ASR监控确有变化，但已核对的语义不构成丢失音频字段的证据。根因仍需同一条评论在两版重新读取后的音频数据定位；不能据此认定MP3被拒绝、风控或服务器删除。

## 范围

见[scope.md](../work/voice-version-diff-20261021/scope.md)。用户授权本地双APK兼容分析，network_profile=offline，flavor=null。没有修改模块源码或配置、构建APK、执行Gradle或发送评论。读取ADB设备清单时无在线设备，未做设备Hook或现场UI复现。

原APK完整摘要及大小在[inputs.json](../work/voice-version-diff-20261021/inputs.json)。40.6 SHA-256为 `3983779738e1804a83e783ca447069e13f7686a30213eebd32196915f601a0e8`；40.8为 `80a0e14b0cceb150e99ea6cd4d7672f9805538808693958a0b076fd6eff985a7`。日志沿用此前用户提供的13:52归档，原[语音分析记录](2026-10-21_reverse-auto-voice-comment-analysis-report.md)保留当时证据。用户已更正40.6能显示，旧的“两版都不显示”假设不再使用。

## 发送侧对照

| 项目 | 40.6 / 40.8核对结果 | 证据 |
| --- | --- | --- |
| 发布模型、附件、AudioModel | File/List/Long仍写localFile/waveList/duration；没有新增codec或媒体类型必填字段 | [模型语义对照](../work/voice-version-diff-20261021/module/model-semantic-comparison.json) |
| 发布API及音频参数 | 普通/evaluate API的注解相同；仍提交audio_vid、audio_duration、audio_wave，取值与空值分支相同 | [40个静态片段](../work/voice-version-diff-20261021/upload/equal-paths.json) |
| 其他参数组装 | action_type取model.l，publish_scene取x，enter_from取n；未发现新增必填参数或默认分支 | [上传/发布报告](../work/voice-version-diff-20261021/upload/2026-10-21_reverse-upload-publish-report.md) |
| 动态上下文 | common_flags/common_data仍从作品feedCommentConfig直接透传；26个context helper同构 | [context对照](../work/voice-version-diff-20261021/upload/context-helpers.json) |
| 上传SDK调用 | domain、space、process=0、重试、超时、512KiB分片与所用setter相同 | [上传/发布报告](../work/voice-version-diff-20261021/upload/2026-10-21_reverse-upload-publish-report.md) |
| 原生上传库 | libbdvideouploader.so两版SHA-256均为a529a524…7480de；libttffmpeg.so也逐字节相同 | [原生库完整摘要](../work/voice-version-diff-20261021/native-libraries.json) |
| 原生录音配置 | 都是AAC-LC/M4A、44.1kHz、单声道、96kbps；时长沿链以毫秒传递 | [原生格式证据](../work/voice-empty-20261021/native-format.json) |
| comment_audio_config本地模型/默认值 | 只归一化已验证的配置与Companion类名后，字段、常量和构造指令相同 | [配置对照](../work/voice-version-diff-20261021/audio-config-comparison.json) |

这里的“相同”限定静态组装逻辑。运行时服务器下发的common_data、AB开关、作品上下文、输入文件和HTTP响应并未被证明相同。FFmpeg相同也不等于所有播放库相同，部分其他音频/播放器so摘要有变化，本轮没有做其native内部语义逆向。

## 真实变化及其影响

上传成功回调从旧构造的`(request, model, dispatchId)`变为`(model, request, dispatchId)`，但旧构造内部交换保存，新构造直接保存，最终两版都为`l0=model,l1=request`。模块不手动构造这个回调，只在宿主上传入口检查其所有权；提供的40.8日志中已实际命中所有权并进入上传。因此不能将这个ABI变化直接认定为当前模块填反参数。

旧上传listener的dispatcher分支拆成40.8独立的`X.13jQ`，成功分支仍取mVideoId，写回AudioModel，再发起发布。普通发布doOnNext也更换wrapper及分支编号；精确抽出的两分支未发现把本地fakeComment音频回填response.comment的写入。详见[上传差分报告](../work/voice-version-diff-20261021/upload/2026-10-21_reverse-upload-publish-report.md)。

全APK的`comment_audio`/`audio_comment`字符串引用扫描，旧版53个键全部保留，新增`comment_audio_asr`和`comment_audio_asr_consume_monitor_enable`。这仅是该字符串范围的扫描结果，不代表找到了所有远程开关。新增ASR监控发生在已有音频组件的识别分支中，不是初始容器创建条件；见[配置引用差分](../work/voice-version-diff-20261021/flag-diff.json)。

## 显示侧已确认的边界

Comment中的JSON名仍为`comment_audio`；CommentAudioStruct仍映射`vid/content/wave/duration/asr_status/asr_text/timbre_text`。已查普通音频组件先根据commentAudio对象是否存在决定是否挂载；没有发现将MP3后缀作为初始容器显示门槛。

空波形也不是已发现的版本差异：两版CommentAudioWaveProgress的空/null wave分支都使用内置31项波形列表，再按时长计算条数。测量、绘制逻辑相同；波形进度模型旧0yF8与新13nI归一化后相同。原始证据及差分在[render目录](../work/voice-version-diff-20261021/render)。以上限定已查的组件/模型路径，不能代替用户现场实际绑定分支的快照。

继续检查的播放失败回调也没有发现整容器隐藏：旧`X.0yI9.LJ`与新`X.13tS.LJ`均在436–695行清进度、回到IDLE并回调；新V2在1796行仍把之前的audio-present标记带入新状态。ASR成功/失败的copy掩码两版同为0x0f/0x6f，保留vid/content/wave/duration。已查列表merge旧0y79/0zcr与新13WX/15FU的音频替换逻辑同构。新增ASR监控因此不是已经证实的空白原因。

提供日志中的自定义隐藏快照均为`custom=0`，模块也没有基于自动音频格式主动清空commentAudio的代码证据。此结论不声称当前全部配置或所有其他模块行为均已验证，见[模块审计](../work/voice-version-diff-20261021/module/module-audit.md)。

## 为什么成功日志仍不能解释空白

提供日志中40.8的6次语音请求都有发布code=0和audio_receipt=present。`sent`只检查发布状态、CID及目标关系；receipt额外检查当时对象的VID和正时长，读取时点在宿主回调处理之后。它们没有验证同一条评论重新加载后的comment_audio、绑定对象或容器状态，也不是未经客户端处理的原始HTTP响应。源码为NativeCommentPublisher.responseStatus与audioReceiptDiagnostic。

日志里的40.6和40.8也不是同一音频文件的单变量对照。按字节数/时长估算，40.6阳性样本约129kbps；40.8阳性样本中约129kbps与约64kbps都有。估算不能替代音频头解析，亦不能据此把码率当原因。

要继续定位，应在两版重新加载**同一条**自动语音评论后复制评论源数据，比较comment_audio整体、vid/content/wave/duration及isLocal等状态。这是当前客户端对象，不是原始HTTP抓包。若对象只在40.8缺失，继续区分列表响应和解析；若两版对象均完整，转向实际holder/绑定分支及可见性。不要用新发的两条不同评论代替同CID对照。

## Evidence / Findings / Path

| E-id | source_ref / 复核方法 | content_hash |
| --- | --- | --- |
| E-APK | inputs.json；对原APK完整文件SHA-256 | inputs.json内完整摘要 |
| E-NATIVE | Zip内arm64库逐字节SHA-256 | native-libraries.json逐项摘要 |
| E-MODEL | 原smali及model-semantic-comparison.json；仅验证过的descriptor映射 | 文件摘要见本轮evidence.json |
| E-WIRE | equal-paths.json / verify_equal_paths.py；API注解与指令段比对 | JSON中各片段normalized_sha256 |
| E-FLAG | ScanAudioFlags.java逐DEX扫描；flags-406/408.tsv | evidence.json |
| E-RENDER | 原DTO、组件与波形smali；render目录的归一化差分 | evidence.json |
| E-LOG | 此前timeline.json及NativeCommentPublisher状态读取 | 此前evidence.json；本轮未新增真实请求 |

| F-id / severity | evidence_ids | confidence / location / status |
| --- | --- | --- |
| F-STATIC / n/a_re | E-APK,E-NATIVE,E-MODEL,E-WIRE | 高；已查静态音频链未见破坏性迁移差异；已验证 |
| F-CALLBACK / n/a_re | E-MODEL,E-WIRE,E-LOG | 高；构造ABI有变但最终绑定与模块观察契约不变；已验证 |
| F-WAVE / n/a_re | E-RENDER | 高；空波形兜底并未在40.8删除；已验证 |
| F-PLAYER / n/a_re | E-RENDER | 高；已查Java播放失败回调保留音频存在标记，ASR copy保留媒体字段；已验证，未覆盖全部native或现场路由 |
| F-CAUSE / n/a_re | E-LOG,E-RENDER | 未确认；缺同CID列表/模型/绑定对照；待现场数据 |

P-1，path_type=callflow：提供商原文件→AudioModel→上传VID→发布audio_*→回调对象→重新拉取列表→Comment DTO→实际组件绑定。前半段有静态/旧日志证据，后半段缺现场同CID数据。

时间线：按用户更正采用“40.6可显示”作为现场观察→重新核原APK与native库→比对模型/API/上下文→排查新增ASR与空波形路径→保留未确认的根因边界。工具使用本机JDK21、apktool内置baksmali、smali-dexlib2和Python；全程未运行Android全量验收。
