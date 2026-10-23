# DouAi 日志系统本地验收（2026-10-23）

以下保留 0.2.15 日志改造阶段的原始测试、失败批次与日志 v1 产物。后续 schema 2 缺口修复及 [0.2.16 正式构建](release-0.2.16.md)分别记录；本文件中的通过次数及 APK 不能代替 0.2.16 的本地验收。

改造基于当前 0.2.15/116 工作区。按用户顺序，首先把原有筛选/播放/手势及候选文档提交为 `04c8dce`（25文件），之后实施日志重构。该提交未推送；本次日志变更留在工作区，未发布远端。

## 交付范围与来源

- [54项业务能力覆盖矩阵](logging-coverage.md)：全部28个配置消费者族，加独立功能及模块自身操作。规则数与Hook数量不是功能数量。
- [统一事件规范与排查方法](logging-events.md)：字段、结果、关联、脱敏、容量和中断语义。
- [自动化完整链路](logging-automation.md)：自动评论/评论回复/私信群聊/续火花/语音、重试与原生回调。
- [媒体与配置链路](logging-media.md)：第三方/官方选择及保存、音频生成、存储、配置和本地分析。

附件作为现状参考与验证线索，实施以本次请求和当前源码为准。重新读取 ZIP 中指定5份 modules_* 日志（不合并verbose/full）：DouAi物理行31,862，Hook明细13,481，进度快照9,324，后两类占71.6%；包含0.2.10至0.2.14多版本。物理行不是业务任务数，这些旧日志不能当0.2.15源码的覆盖或故障根因证明。

## 验证命令

在项目根目录 PowerShell，使用本机已配置的JDK与签名：

```powershell
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$env:DOUAI_TEST_APK = Join-Path (Get-Location) '抖音-40.8.0_40809900_20261019 (400801).apk'
$env:DOUAI_VERIFY_RELEASE_APK = Join-Path (Get-Location) 'app\build\outputs\apk\release\app-release.apk'
.\gradlew.bat :app:assembleRelease :app:lintRelease :app:testDebugUnitTest -PreleaseVerification=true --console=plain
python tools/lint_budget.py check --report app/build/reports/lint-results-release.xml
```

宿主是40.8.0/40809900原APK。本次不安装或驱动手机，不请求真实AI/第三方服务，不推送GitHub/LSPosed。网络用MockWebServer，存储用测试provider，Hook用fixture及原DEX合同。

## 最终本地结果

| 验证 | 结果与范围 |
| --- | --- |
| 全量回归 | 2,256项 / 217套件，0错误、0跳过；4项旧Logcat明文断言失败，其余2,252项通过。4项已迁移为结构化trace/字节数/脱敏引用/业务终态断言，保留原UI结果及隐私检查 |
| 末轮专项复测 | **246项 / 36套件，0失败、0错误、0跳过**；覆盖迁移断言、最新核心、私有进程导出、日志UI/选择器、语音关联等。最后的journal容量收敛与自动正文快照移除也包含在此轮。末轮采用受影响范围复测，全量历史记录按原结果保留 |
| 最终APK合同 | 包装完成后独立运行 **5项，0失败、0错误、0跳过**，使用本次最终Release APK；与并行构建时的旧产物明确区分 |
| Release构建 | R8构建完成；版本0.2.15/116，本地候选 |
| Release Lint | **0错误，56警告，0新增未审查项**；53预算项、3依赖版本提示，预算通过 |
| 签名/对齐 | 原长期证书v3校验通过；`zipalign -c -P 16 4`通过 |
| 文本/源码检查 | `git diff --check`通过；生产源码没有新增旁路Log.*或println；传输仅由统一sink调用框架 |
| 设备/CI/发布 | 未执行真机或真实服务端验收；未运行远端CI、未推送或发布 |

本机JVM重复观察测试：10,000次调用耗时**246ms**，输出1条、计数抑制9,999条。它包含本机调度/测试开销，只证明该fixture的限流和耗时，不代表手机性能。

证据封存：[`artifacts/logging-validation-20261023`](../artifacts/logging-validation-20261023/manifest.json)。其中`full/`保留全量原始结果（含4条已迁移断言），`focused/`保留246项末轮结果，`apk-contract/`保留最终5项合同；`source-snapshot.json`记录250个构建源文件摘要。可直接查看[真实跨线程重试样例](../artifacts/logging-validation-20261023/examples/async-retry-unconfirmed.jsonl)，以及同目录六种终态样例；全部由合成fixture生成，不是手机回执。

最终APK：[DouAi-0.2.15-logging-v1-release.apk](../artifacts/DouAi-0.2.15-logging-v1-release.apk)，**5,000,492字节**。SHA-256：`027950A39DB6FE226E2F6BC754A7AFD990BF79949AEDFA575978E3BCC16FB7B9`。证书SHA-256：`FAE67C134F4C4FA73B8E34487C47A8C7F3E7C34B840BC245AFAC51585B7DFD47`。与改造前0.2.15候选为不同本地产物，未覆盖原封存APK。

## 新增验证关注点

| 行为 | 本地验证方式 |
| --- | --- |
| 全功能台账及启用/支持/安装分离 | FeatureLogCatalogTest枚举全部消费者，共享键，关闭且不支持，安装失败及Hook映射 |
| 根终态并发唯一/迟到 | 12线程竞争同一Operation，只能有一个根终态；迟到计数不覆盖原结果 |
| 跨线程/重试/子span | 显式Operation.wrap/call/Consumer，attempt变化保留trace；注册表重接纳隔离新旧任务 |
| 六种状态 | success/failed/skipped/cancelled/timed_out/unconfirmed分别断言，debug关闭仍保留 |
| HTTP200不是业务成功 | 无效音频最终失败；保存失败最终失败；无保存证据最终未确认 |
| 上传成功不是发布成功 | CommentVoiceLoggingTest验证code=0且非空ID仅说明音频上传；缺ID/超时/迟到不伪造发布 |
| 长按延迟确认 | 原点击已结束后，新语音操作明确linked_trace_id；claim/恢复守卫不变 |
| 筛选真实结果 | Fixture响应列表过滤/回滚/实际变化回读；不以Hook返回代替结果 |
| 高频与优先级 | 10,000次相同观察只发首条并计9,999次抑制；洪水填满队列，容量有界且优先保留终态 |
| 隐私/注入 | 合成key、Cookie、Bearer、签名、异常正文、无前缀英文正文、换行与控制字符；原文不出sink |
| 分片完整性 | 1200字节内的帧共享event_id/trace；重组后与原JSON一致且SHA-256校验一致 |
| 满容量导出/清空 | 完整性头预留预算；清空generation拒绝上游残留事件回流 |
| 中断及故障 | 注入旧会话未闭合journal、不可写路径、sink抛异常；业务异常传播不变，损失可见 |
| 容量 | 核心/存储/快照队列、活动span、窗口、journal文件及轮转文件上限 |

## 证据边界

本地测试覆盖统一状态机和有代表性的实际业务分支；不能把共同日志机制的通过当作54项功能在手机上所有组合都通过。逐功能的所有开/关/不支持/成功/跳过/失败组合未做真机逐项矩阵验收；真实账号、宿主异步回执、接收端播放、ROM覆盖View效果、权限和LSPosed裁剪仍未验证。

存储满以不可写路径/写失败模拟，进程重启以旧会话journal注入模拟，未执行真实强杀/OOM/拔盘。性能数字只代表本机JVM fixture，不代表Android主线程CPU、内存或耗电预算。框架外部损失未知；导出是有界、非原子时间窗，不能承诺绝对完整或零丢失。

源码保持业务选择、请求参数、实际重试策略和原回调的业务返回规则；日志新增的结果证据限定本地事实范围。现有业务无法观察最终效果的地方，明确为unconfirmed/开放操作，不额外请求接口或无限轮询来凑成功。
