# 当前 40809900 自动语音评论无气泡排查

分析日期：2026-10-21。flavor=null，普通 APK 逆向与模块故障调查。范围见 [scope](../work/voice-blank-9900-20261021/scope.md)，过程见 [timeline](../work/voice-blank-9900-20261021/timeline.md)。

当前可以确认：用户描述的是“评论行已创建，但整个语音气泡不显示”。最新 0.2.9 日志已真实加载新 40809900，229 条规则全部唯一，并有两次音频成功回执。用户提供的空白评论截图和完整 JSON 证明音频、播放信息与识别文字仍存在；同对象内的手动回复有 22 点波形，空白自动主评论波形为空。追到 KMP 外层后已找到对应的显示条件：wave 为空会直接跳过音频组件。0.2.10 修正生成音频的空波形元数据，并补充全局自动诊断与评论查看器；本地路径证据充分，设备效果仍需用户新发一条评论验证。

## 输入与依赖

| 输入 | 身份与 SHA-256 |
| --- | --- |
| 当前宿主 | 40.8.0 / 400801 / UPDATE_VERSION_CODE=40809900；`222b0a37d505060902980d5c3b9f89fcfd49232ae03a40d563c65b292f64d53f` |
| 旧版对照 | 40.6.0 / 400601 / 40609900；`3983779738e1804a83e783ca447069e13f7686a30213eebd32196915f601a0e8` |
| 最新日志 ZIP | `LSPosed_20261021_202928.zip`；`00b8adc0af16ebb6ba3965d726961b03a34536c3069bdf72c7952b99d2e135e2` |
| 已安装模块 | 0.2.9 / 110；`1586318bc4f26a7da4a9c14a6f93f503379dd21f69cbb38a92d190166555350f` |

工具为本机 JDK21、dexlib2 3.0.9、原 APK 直接 smali 提取、Gradle 与 Android build-tools。该路径依赖 Android View/RecyclerView、宿主发布 Request 与 CommentAudioStruct、BDVideoUploader、Kotlin 回调、Rx 请求链；本轮没有 native SO 分析需求，也没有设备或服务端重现。

旧 40809985 不作为当前适配证据。早先含该构建的 40.8 比较材料仅保留历史意义。

## Evidence → Finding → Path

| E-id | source_ref | repro_command | content_hash |
| --- | --- | --- | --- |
| E1 | `work/voice-blank-9900-20261021/input.json`、`triage/target-timeline.json` | `python -X utf8 work/voice-blank-9900-20261021/triage/parse_timeline.py` | 输入 ZIP 哈希见上表 |
| E2 | `publish/2026-10-21_reverse-9900-voice-publish-report.md` 与原 smali | 按报告行号读取当前 `CommentPublishRequest`、`14eK`、API 声明 | 当前 APK 哈希见上表 |
| E3 | `render/2026-10-21_reverse-9900-comment-render-report.md`、`VoiceRenderBindContract.java` | `powershell -File work/voice-blank-9900-20261021/render/verify-bind.ps1` | 两版原 APK 哈希见上表 |
| E4 | `triage/release-dex/binary-reflection-audit.md`、`r8-before/TEST-com.douai.module.scan.ApkArtifactContractTest.xml` | 设置 `DOUAI_VERIFY_RELEASE_APK` 为 0.2.9 后运行新增两个 APK Kotlin 合同测试 | 0.2.9 APK 哈希见上表 |
| E5 | `triage/confirmation-triage.md` 与 `publish/` 的二次确认提取 | 以报告中的类名/时段在原 smali、当前日志搜索 | 输入 APK/ZIP 哈希见上表 |
| E6 | `render/folded/outer-gate-findings.md`、`manual-reply-tree.json` | 按原 smali 行号复核 KMP mapper、state constructor、Compose consumer；完整 JSON 仅在用户本地附件中读取 | 用户完整文本 SHA-256 `3a79ec1a7cc2d98854a3bedc93f33f478e2060a928e2ddf3839aa2f1d0d0d6d5` |

| F-id | severity | evidence_ids | confidence | location / status |
| --- | --- | --- | --- | --- |
| F1 | n/a_re | E1、E2 | 高 | 两次 owned audio 均 code0/present/publish；只证明提交回执，显示问题未结案 |
| F2 | n/a_re | E2、E3、E6 | 高 | KMP 外层 wave 为空则 shouldShowAudio=false，直接绕过整个语音组件；与空白自动项/正常手动项对照吻合；已修正生成音频空波形 |
| F3 | n/a_re | E4 | 高 | R8 把宿主 Unit/Function2 名称改为模块 pv1/z60；与隐藏/点赞日志吻合；0.2.10 保留宿主 Kotlin 类名与成员 |
| F4 | n/a_re | E5 | 中 | 已定位草稿/附件切换二次确认，处于发布前；未证实与当前空白有关 |

## 用户新提供的空白评论字段

用户明确确认这 8 张解析截图是 **40.8.0 / 40809900 的无气泡自动主评论**。截图自身的创建/资源时间与 202928 归档内两条请求不相同，不能假装已经按 CID 与前面的两次日志对齐。以下仅记录无身份字段，不重录 commentToken、原始 CID/VID、音频 URL、ASR 正文或用户资料：

| 字段 | 观测 |
| --- | --- |
| 主评论 | enterFrom=douai_auto，level=1，replyId/replyToReplyId=0，commentType=1，contentType=8，status=1，text为空 |
| commentAudio | 非空，a/VID非空；b是完整远端播放 JSON，message=success、media_type=audio |
| 播放媒体 | video_duration=15.999；video_meta.vtype=m4a，code_type=aac，audio_sample_rate=44100，size=260664 |
| 时长/波形 | e=16056ms，d为空 |
| ASR/状态 | f=1，g有识别文字，h有音色文字，hasRequestedAsr=false，isFolded=true，isLocal=false |

这证明该条空白评论并非完全没有音频数据；播放资源元数据也已标为 M4A/AAC。它不能倒推出 TTS 原始文件一定是哪种编码，也不证明实际可播放或最终 View 正常。内嵌播放 JSON 的 status=10 不是评论发布接口的 status_code，不能混作发送失败。原 DEX 核对发现 isFolded=true 是新旧 CommentAudioStruct 构造默认值，ASR 控制器用它切换文字“展开/收起”；它不控制整个语音气泡，因此不强制改为 false。

## 空波形的外层显示门槛与修正

早期只追内层绘图，看到默认波形就排除了空波形，这个结论过宽。完整路径见 `render/folded/outer-gate-findings.md`：新 `0Mbr.LIZ` 约 914–938 行要求 `commentAudio.wave != null && wave.length() > 0`，才置 `shouldShowAudio=true`；`0MTi:1507–1604` 在 false 时跳过 `0MTk.LIZ`（KCmtCellAudioComponentContainer）。组件内部的默认波形没有执行机会。

用户新给的完整 JSON 包含两个直接可比对象：root 为 `douai_auto` 主评论，27,324ms，wave 空；`replyComments[0]` 为手动回复，2,117ms，wave 22 点，数值为归一化幅度。两者 contentType=8、status=1、isFolded=true；两份播放资源都为 M4A/AAC。这与外层 wave 门槛直接对应。40.6 也包含同一个 KMP 门槛，但 V2 分支仅要求 audio 对象存在；具体两次设备运行为何选择不同路线，仍没有直接的 AB 实测记录。不能说只有 40.8 才有该判断。

修正 `NativeCommentPublisher` 和 `NativeCommentSpeechHook` 的生成语音 AudioModel：空 List 改为 `[0f]`，上传的 `audio_wave` 成为合法非空的 `0.0`。原生 V2 与 shared 两路都把小于目标柱数的短 List 替换为自己的默认波形。该值是显示兜底标记，不是假称采集的真实波形；不解析、不转码、不重写音频，不改时长或 isFolded。不把宿主默认显示高度 4–16 直接发送为幅度：有效采样分支要求归一化幅度，直接写高度会越界。

P2（path_type=callflow）：旧 emptyList → 原生 join 得空 audio_wave → 服务端 DTO wave 空 → KMP shouldShowAudio=false → 无气泡。修正后 `[0f]` → `0.0` → 通过非空门槛 → 宿主短 List 默认波形绘制。既有发布与回复目标校验保持不变；已用原发布调用回归先记录空波形失败，修后专项验证见构建记录。

P1（path_type=callflow）：TTS 原文件 → `13Zi.A.audio/14eK` → checked request → 原生 uploader 写 VID → `LJIJJ` 原生发布 → `LJIJI` 成功回调 → Comment 列表读取 → 普通行绑定 → 音频 state/apply。现有日志只覆盖到回调；0.2.10 新增后四处只读摘要以收窄丢失边界。

## 关键事实与限制

20:03:02.023、20:28:43.764 两条请求分别为 23,400ms/376,116B、27,324ms/438,900B，均成功。20:29:17.636 最后一条只记录到上传 accepted，不能判失败。日志里的 2.292 秒 AAC 原生编码会话与 18.288 秒自动生成并发，不能归到自动 TTS 文件。文件平均码率也不能证明音频编码。

原生上传/普通 publish/evaluate 均沿用同一个 AudioModel，并取 `audio_vid/audio_duration/audio_wave`。当前静态链未找到需要发布后再确认一次才补上语音的证据。成功 doOnNext 不补音频；模块当前 Request 没有 UI listener，因此也没有证据将现有 present 直接解释成 fakeComment 补写。自动与手动在输入上下文、场景、enter_from、商业化字段、波形等仍有实际差别，未经运行时证明不改这些发送参数。

二次确认截图文字为“回复评论二次确认”，未明确语音。当前宿主已找到的清草稿/附件确认由点击回复或切换输入触发，确认后才继续动作；权限失败 toast 也在上传前。另有真正的发布后二次验证：`13Vt.checkValid()` 仅当 status_code=3299 时读取 `second_verify_type` 并抛出 `TwiceVerifyApiServerException`；其他风险码也有各自异常分支。两个当前已完成样本均 code0，不满足此触发码。现有证据不支持这两种确认导致空白，但静态调查不能排除其他模块未记录的行为。

## 0.2.10 调试功能

`[DouAi.AudioTrace]` 分别记录 prepared、upload_before、publish_before、success/failure before/after、audio_read、row_bound、audio_state、audio_bound。audio_read 只表示 DTO 被读取，不等价于 UI 已绘制；audio_bound 才额外记录已有 View 的 visibility/shown/尺寸，不触发 lazy inflate。

日志包含音频对象是否存在、VID 是否存在/是否与本次上传一致、duration、波形长度、content 类型、isLocal/折叠/ASR 状态、已有本地文件状态。12 字节文件头分类只能提供容器/帧头线索：ID3 不足以独立证明 MP3 编码，MP4 也不独立证明 AAC。

音频摘要仅关联进程内最近 8 个 owned 请求与其至多 4 条直接回复，15 分钟过期；每个请求至多 48 条不同摘要。摘要不打印原始 CID/VID/URL/正文。重启会清空关联。Hook 失败独立降级，诊断不改成功判定、文件清理或原音频字节，不主动请求网络。

用户随后明确要求全局调试：关闭只新增简要日志；开启自动记录全部功能已注册 Hook 的执行阶段与现有业务日志，并自动记录评论对象 JSON，包含发布回调、行绑定等相关阶段，无需主动查看。完整评论仅通过宿主 Gson 序列化，令牌、密钥、Authorization、链接签名等脱敏后才进入文件或 LSPosed；有界队列、容量、截断与限流会明确标注，不承诺无限历史。模块内提供开关、查看、刷新、复制、清空和完整保留日志导出。主动评论查看器也保留，复用既有“复制源数据”开关。

R8 独立修复保留 `kotlin.Unit` 与 `kotlin.jvm.functions` 名称/成员，解决已证实的宿主 ClassLoader 名称误写；该修复不能等同于语音显示已修复。

## 如何取得下一份有效日志

覆盖安装 0.2.10 并完全重启抖音；新发一条自动语音，观察评论气泡。修正针对新发送的元数据，历史服务端空波形不会被改写。如仍有问题，在“关于 → 调试与日志”开启调试，复现自动行为，再导出完整日志；评论 JSON 会自动记录。8.0 仍以当前 40809900 为准，40.6 继续支持。

用户最后要求跑一次全量，最终按该要求执行完整本地验收；未执行手机安装或真实评论操作。具体产物及结果见 [0.2.10 构建记录](release-0.2.10.md)。
