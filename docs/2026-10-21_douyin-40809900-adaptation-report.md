# 抖音40809900重新适配记录

用户更换了工作区40.8 APK，并明确移除旧40809985适配目标。本轮以新包完整SHA-256 `222b0a37d505060902980d5c3b9f89fcfd49232ae03a40d563c65b292f64d53f`为依据重新提取DEX和资源。旧40809985的分析只作差分参考，不作为新包适配证据。最终交付为[0.2.9](release-0.2.9.md)。

## 范围与身份

[scope.md](../work/readapt-40809900-20261021/scope.md)记录用户授权与“不跑全量验收、构建后交付”的约束。没有操作手机、发送评论或远端发布。

新包为 `com.ss.android.ugc.aweme / 40.8.0 / 400801`，Manifest内部UPDATE_VERSION_CODE=40809900，56个DEX，360,503,779字节。旧包同名版本及versionCode相同，但内部号为40809985，SHA-256为80a0e14b…f985a7。输入完整元信息在[inputs.json](../work/readapt-40809900-20261021/inputs.json)。新日志为LSPosed_20261021_191624.zip，SHA-256为af10eafe…50ee1f。

## 迁移结果

规则扫描从229条中的155唯一、74缺失，变为229全部唯一。75行规则改变，部分原先虽唯一的裸混淆类也已根据实际调用者、字段和方法体重新校准。新规则指纹为 `ab5215b527416778cf4a773880af43d9d073ce0512935ee99dfbd6e89c55a6f1`；40.6规则保持原指纹与原始资产字节。

评论发布模型迁至13Zi、附件13aW、音频输入14eK，上传完成回调迁至AObjectS307S0200000_36。原构造虽然换版，最终仍为l0=model、l1=request；原文件、波形列表、时长字段与模块ownership语义已按新DEX核对，未擅自调整语音生成、格式、时长或重试策略。

隐藏安全保护改用ALambdaS38S1500000_35的$t=0以及独立12bW后台任务，普通删除$t=1继续透传。资源表共86项、51项feed控件，以及4份关键布局均重新核对，所用ID不变；原生聊天和相册18个类/方法别名按新APK迁移。详见[资源证据](../work/readapt-40809900-20261021/resources/summary.json)和[语义审计](../work/readapt-40809900-20261021/semantics/semantics-audit.md)。

为避免再次把同名版本当成同一构建，HostApkProfile直接解析Manifest中的application/meta-data。实际APK检查结果为：40809900选中，40809985拒绝，40609900继续选中。未知或缺失内部号的40.8不加载9900规则；原生聊天的PackageInfo版本兜底也已移除。此读取不受模块PackageInfo版本伪装影响。

## 新日志的边界

新日志当前会话加载0.2.8，三次扫描均155唯一/74缺失，自动评论、评论回复、手动#语音相关Hook未成功安装。当前会话没有新的自动语音提交、上传启动或发布回执；log.old中的旧发送记录不能冒充当前9900证据。因此，本轮确认并修正了适配构建不匹配，但没有声称在手机上验证空白语音已经恢复。

## Evidence → Findings → Path

| E-id | source_ref / 复核方式 | content_hash |
| --- | --- | --- |
| E-INPUT | inputs.json、host-badging.txt、manifest-tree.txt；原文件SHA-256/aapt | inputs.json内完整摘要 |
| E-RULE | rules/scan-status.json及原APK流式DEX扫描 | 新canonical fingerprint见上；规则资产摘要随构建记录 |
| E-UI | resources/summary.json、ui-aliases.json、native-layouts.json与新原smali | 对应目录记录 |
| E-SEM | semantics/semantics-audit.md、new-original原smali | 对应目录原始指令 |
| E-ID | identity-check.log与HostApkProfileTest的7项检查 | 本地build evidence |
| E-BUILD | build-inputs.json/build-result.json、签名/对齐日志 | 最终APK SHA-256见0.2.9交付记录 |

| F-id / severity | evidence_ids | confidence / location / status |
| --- | --- | --- |
| F-BUILD / n/a_re | E-INPUT,E-RULE,E-SEM | 高；两个40.8内部构建不同，旧规则对新包74缺失；已确认 |
| F-REPLACE / n/a_re | E-RULE,E-UI,E-SEM | 高；新9900规则/别名与安全分支已迁移；已完成本地静态核对 |
| F-GATE / n/a_re | E-ID,E-INPUT | 高；新9900接受、旧9985拒绝、40.6保留；已验证 |
| F-DEVICE / n/a_re | E-BUILD | 未执行；真实Hook、投递与语音显示需手机现场验证 |

P-1，path_type=callflow：真实Manifest构建号→40.6或新9900专属规则→原DEX扫描→反射与资源合同→原生评论/上传/展示。新规则指纹与模块版本变更使旧缓存不再匹配。

时间线：读取用户新APK与19:16日志→确认同版本号而内部构建不同→复现155/74→新DEX定锚及资源核对→229全唯一→确认新旧构建门禁→原证书构建与归档。工具为本机JDK21、apktool/baksmali、smali-dexlib2、aapt、apksigner和Python。没有运行全量Android验收。
