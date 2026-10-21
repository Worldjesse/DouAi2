# 2026-10-21：隐藏视频提示后跳走与列表移除分析

抖音 40.6.0 存在一条安全校验触发的自动移除路径，它没有经过 DouAi 0.2.5 原有的两个 `LJLJL` 删除 Hook。当前项会先写全局召回 Set，再同步触发列表删除；非当前项使用另一项 Feed 数据删除任务。修复前本地夹具实际执行 12 项、5 失败，说明原保护不能覆盖这条已识别的宿主调用形态。0.2.6 补充两项具体任务保护后，这五项转绿，最终签名包 186 套件/1993 次执行全部通过。用户描述的“提示后跳走、上一条也消失”与列表移除结果一致，但**中文提示的具体现场归因仍未得到设备调用证据**。本报告采用普通逆向结构（`flavor=null`），不将其描述为漏洞、权限恢复或已验证的手机修复。

## Scope 与样本

- `auth`：用户在当前对话提出并授权检查、修复本项目“不允许系统跳过已隐藏视频”失效的反馈。
- `in_scope`：本地 DouAi 源码、既有合法取得的抖音 40.6.0 APK/smali、静态调用链、模块行为夹具与适配合同；本轮不修改宿主 APK。
- `network_profile`：离线分析与本地测试；未调用真实账号接口或提交评论/私信。
- `scope.md`：本轮沿用上述对话授权与明确范围，未另建 case 授权文件；本报告按上述范围记录已执行的本地验证和平台模拟器合同，不声明手机 LSPosed 现场验收已完成。

样本包名 `com.ss.android.ugc.aweme`，版本 `40.6.0 / 400601`。原 APK 为 `抖音-40.6.0_40609900_20260922 (400601).apk`，SHA-256：

```text
3983779738E1804A83E783CA447069E13F7686A30213EEBD32196915F601A0E8
```

样本身份来自已封存 [0.2.5 inputs.json](../artifacts/release-verification/20261021T024248Z-5a07a8a2/inputs.json) 的 `hostApk`，不是从文件名推断。静态分析只读取已有 `douyin-40.6.0/apktool-no-res` 与选定反编译文本，没有进行整包重新反编译。

## Evidence

| E-id | source_ref | content_hash / artifact_path | repro_command |
| --- | --- | --- | --- |
| E-001 | [0.2.5 输入封存](../artifacts/release-verification/20261021T024248Z-5a07a8a2/inputs.json) 的 `hostApk` | APK SHA-256 `3983779738E1804A83E783CA447069E13F7686A30213EEBD32196915F601A0E8`；artifact_path 为上述本地原 APK，不分发第三方包 | `Get-FileHash -Algorithm SHA256 -LiteralPath '抖音-40.6.0_40609900_20260922 (400601).apk'` |
| E-002 | [静态流向摘录](../work/hidden-video-20261021/flow-evidence.md)及下文精确 smali 位置 | `167836E618ECB99BF7EC8B59046BEA64942BA8F0ADB101F15E48B79E80AED4CD`；`work/hidden-video-20261021/flow-evidence.md` | `Get-Content -LiteralPath 'work/hidden-video-20261021/flow-evidence.md'`；smali 重查命令见后文 |
| E-003 | [修复前 JUnit XML](../work/hidden-video-red-junit/TEST-com.douai.module.feature.HostBehaviorHiddenVideoTest.xml) | `ED59F38637F4F6B14D2F6401FE671D2822567B04AB4419CAFDAE54FED938417F`；`work/hidden-video-red-junit/TEST-com.douai.module.feature.HostBehaviorHiddenVideoTest.xml` | `Get-Content -LiteralPath 'work/hidden-video-red-junit/TEST-com.douai.module.feature.HostBehaviorHiddenVideoTest.xml'` |
| E-004 | [RED 上下文](../work/hidden-video-red-context.json) | `6CAE346315B9C62A9A34B8F9281CE9C0BED350AD6175DC35D3CFD06E8459876A`；`work/hidden-video-red-context.json` | `Get-Content -LiteralPath 'work/hidden-video-red-context.json'` |
| E-005 | 用户现场描述 | n/a，manual；提示文字与上滑后条目缺失由用户描述，没有新增设备 trace | 本轮未执行手机复现，不能将本地重放当成该提示的来源证明 |
| E-006 | [修后行为预检 XML](../work/hidden-video-preflight-junit/TEST-com.douai.module.feature.HostBehaviorHiddenVideoTest.xml) | `30BAE1C97C7ACCBB9F766004D7E7600B2FA1834372206996BD89FFFAAC72C789`；`work/hidden-video-preflight-junit/TEST-com.douai.module.feature.HostBehaviorHiddenVideoTest.xml` | 用下文 Gradle 命令运行当前行为测试；归档中 15 项全部通过 |
| E-007 | [原 APK DEX 合同 XML](../work/hidden-video-preflight-junit/TEST-com.douai.module.scan.HiddenVideoHostContractTest.xml) | `B3E516A3D5DA044D30D558E8990ABF6A90DCDD951698C242BF1F2124022757E8`；`work/hidden-video-preflight-junit/TEST-com.douai.module.scan.HiddenVideoHostContractTest.xml` | 设置合法本地 `DOUAI_TEST_APK` 后运行 `HiddenVideoHostContractTest`，归档中 6 项通过、无跳过 |
| E-008 | [0.2.6 平台汇总](../artifacts/platform-smoke/smoke-1cf5621894204842a12e05098f643982/summary.json) | `F9689BEFDE4724EE63E68A030E7D7E8331B53486550A0F6C33E525F16AC4A691`；`artifacts/platform-smoke/smoke-1cf5621894204842a12e05098f643982/summary.json` | `tools/run-platform-smoke.ps1` 的 API 35 模拟器结果；不作为抖音隐藏场景证据 |
| E-009 | [最终严格 manifest](../artifacts/release-verification/20261021T033649Z-d32fbaa1/manifest.json) | `83FEC030A1E7C751E7F474DC1A55F8F5270E0AAC8280EAD1FF205D9F40A5896C`；`artifacts/release-verification/20261021T033649Z-d32fbaa1/manifest.json` | `python -B tools/release_verification.py integrity --run-dir artifacts/release-verification/20261021T033649Z-d32fbaa1` |
| E-010 | [签名 APK 标记/规则合同](../artifacts/release-verification/20261021T033649Z-d32fbaa1/signed-hidden-hook-contract.json) | `1418D07F75EDB5E30916D864180753D0D4C00E7A59E67B33D509BBCD44C80EB1`；同运行目录 `signed-hidden-hook-contract.json` | 读取该 JSON 与 E-009 的证据摘要；只验证 APK 标记和打包规则，不验证运行时行为 |

E-004 的生产源码摘要为 `E0FE727FD6252AA135D87A1A3AB1CB15EB832D7B1BA1F04300E5AB120F88B5B8`，与 E-001 同次 inputs 中已封存的 `HostBehaviorHooks.java` 摘要一致；红测版本字段保留 `0.2.5`。其测试源码摘要为 `59E00656B1422DF7EDFA89679ACEEECE068703206722CCBE0FBADAC3D29EF0E6`，避免把后续修改后的测试源码误认作修复前输入。

## Findings

| F-id | severity | evidence_ids | confidence | location | status |
| --- | --- | --- | --- | --- | --- |
| F-001 | n/a_re | E-001、E-002 | high | `06hv.LIZ` → 两项安全自动移除任务 → 列表删除 | validated：原 APK 静态链已确认 |
| F-002 | n/a_re | E-002、E-003、E-004 | high | 现有 `HostBehaviorHooks` 仅旧 `LJLJL` 两规则，行为夹具出现 5 个目标失败 | validated：本地覆盖缺口已复现，尚非手机触发确认 |
| F-003 | n/a_re | E-002 | high | `06R8.LJI(String)` 的 Set 写入与 `LIZIZ(List,boolean)` 的 Iterator.remove | validated：只挡末端可能留下后续过滤标记 |
| F-004 | n/a_re | E-005、E-002 | medium | “该视频被隐藏无法观看”提示与上述安全召回链的关联 | candidate：集合效果相符，缺具体 Toast/设备调用关联 |
| F-005 | n/a_re | E-006、E-007、E-009、E-010 | high | 0.2.6 两安全任务覆盖、适配诊断与最终签名包 | validated：本地行为/原 APK 合同及完整门禁通过；手机症状仍不在结论内 |

`AwemeStatusModel` 在此 APK 是播放器首帧状态上报：`sendRequest(Object[])` → `ACallableS1049S0100000_17` 的 `$t=2` → `0xQj.LIZIZ(...):void`，不能把它当作可见性响应。`AwemeStatus.b` 对应 `is_delete`，权限归一化还综合 `private_status`、`part_see`、`user_status`，不能把这些状态统一改为“未隐藏”或“可播放”。

## Path

### P-001：当前条目自动召回

`path_type=callflow`。起点为安全校验返回 `safety_code==1` 且 item_id 非空，终点为 Feed 实际列表移除；对应 E-002/F-001。

```text
VideoSafetyCheckItem.safety_code == 1 + 非空 item_id
→ AConsumerS73S1300000_47 收集失效 ID
→ LX/06hv.LIZ(ArrayList, LX/06fP):void
→ 04Ov.LIZJ(List) bookkeeping
→ MainThreadHelper 排队 ALambdaS32S1500000_4（$t=0）
→ ALambda.invoke():Object
→ LX/06R8.LJI(String):void
→ 全局召回 Set.add(aid) + FeedRecallCallBack
→ BaseListFragmentPanel.j():void
→ LX/0xfU(aweme, eventType, "recall")
→ FullFeedFragmentPanel.onDeleteAwemeEvent → A0
→ LX/06Ls.hM(Aweme):boolean
→ BaseFeedListFragment.hM → BaseListPresenter.LJIILLIIL(Object)
→ BaseListModel.deleteItem(Object)
→ getItems().remove(aweme) + observer.LJJJJJ(index)
```

| 精确位置（相对 `douyin-40.6.0/apktool-no-res/`） | 合同 |
| --- | --- |
| `smali_classes45/com/ss/android/ugc/aweme/feed/helper/VideoSafetyCheckItem.smali:12`、`:111` | `safety_code`/`safety_msg`/`item_id` 字段，`LIZLLL():int` 返回 safety_code |
| `smali_classes43/Y/AConsumerS73S1300000_47.smali:1220`、`:1923` | 筛选 code=1 和非空 ID，交给回调；另有同形分支 `:559`、`:999` |
| `smali_classes45/X/06hv.1.smali:151`、`:524`、`:544` | 先 bookkeeping，再提交当前项任务；`$t=0` |
| `smali_classes45/kotlin/jvm/internal/ALambdaS32S1500000_4.smali:579`、`:608` | `$t=0` 对应 `:pswitch_1`，调用 `06R8.LJI`，其他分支不能混入 |
| `smali_classes45/X/06R8.smali:1053`、`:1139` | Set.add 与通知回调 |
| `smali_classes29/com/ss/android/ugc/aweme/feed/panel/BaseListFragmentPanel.smali:33486`、`:34013` | `j():void` 发出 source=`recall` 的独立事件 |
| `smali_classes21/com/ss/android/ugc/aweme/feed/panel/FullFeedFragmentPanel.smali:23751`、`:2070`、`:2411` | 订阅入口、`A0(0xfU):void` 与实际 `hM` 调用 |
| `smali_classes37/com/ss/android/ugc/aweme/feed/ui/BaseFeedListFragment.smali:1898` | `hM(Aweme):boolean` 委托 presenter |
| `smali_classes37/com/ss/android/ugc/aweme/common/presenter/BaseListPresenter.smali:705` | `LJIILLIIL(Object):boolean` 调用 model |
| `smali_classes27/com/ss/android/ugc/aweme/common/presenter/BaseListModel.smali:102`、`:168` | `List.remove(Object)` 与位置通知 |

A0 在删除前核对 eventType，相等或前缀匹配才继续；此前还更新 Aweme 缓存并记录状态，读取 `Mi/y1` 生成方向布尔。普通分支先调用 `hM`，成功后才执行 `LLZZ()` 更新界面。`source="recall"` 只保留在事件中，进入 `hM(Aweme)` 后不再作为参数传递，因此在最末端全局拦 `List.remove` 或 `hM` 不能可靠区分具体来源。Story 分支有异步回调，简单用调用栈 ThreadLocal 包围事件入口也不覆盖所有任务。

### P-002：非当前条目与下次数据过滤

`path_type=callflow`；对应 E-002/F-001/F-003。

```text
06hv.LIZ 中非当前命中项
→ LX/06ht.invoke():Object
→ IFeedDataContext.LJIIIIZZ(aid, "safety_check"):DeleteResult
→ Feed 数据层删除

当前项留下召回标记时的后续路径
→ 06R8.LIZIZ(List, boolean):void
→ boolean=true 分支读取同一召回 Set
→ contains(aid) 且不在宿主例外分支
→ Iterator.remove()
```

非当前任务见 `smali_classes45/X/06hv.1.smali:651` 与 `smali_classes45/X/06ht.1.smali:52–85`。召回过滤函数为 `06R8.LIZIZ(Ljava/util/List;Z)V:217`：`:352` 检查 Set，`:384` 日志为 `deleteFilterRecallAwemeForSetData`，`:438` 使用 `Iterator.remove()`。此处还保留 story/错误类型等例外条件，不是无条件过滤全部不可播放条目；六项 DEX 合同已验证该函数 true 分支读取同一 Set 并实际到达 Iterator.remove；这不应被扩大为已测试全部 setData 调用者。

## RED、GREEN 与验证边界

本地重放使用可变 `[A,B,C]`、当前 index=1 和异步任务队列。修复前逻辑中当前 B 召回后变为 `[A,C]`，index 1 对应 C，再上滑只剩 A；非当前 C 直接删除后变为 `[A,B]`。这些是夹具中确实发生的集合结果，没有改写为手机已经发生的调用证据。

E-003 实际结果为 **12 项、5 失败、0 错误**。五个失败分别是：当前条目及返回导航；不留下下次数据过滤的召回标记；非当前项保留；排队后关闭→开启；热开启。另 7 个负例通过，包括关闭开关、关闭功能、`$t=1` 非目标分支以及播放状态/响应缓存不被重写。修复后上述 12 项已全部转绿，增加三个安装/形态失败守护后行为回归共 15 项通过（E-006）；真实原 APK 的六项 DEX 合同通过且无跳过（E-007）。联合预检为 7 套件/51 次执行零失败/错误/跳过，含 229 条规则唯一匹配；Lint 0 错误/57 条既有警告/0 新增，34 项工具测试通过。最终签名包严格门禁已完成 **186 套件/1993 次执行，0 失败/错误/跳过**（E-009）；另 34 项工具、原生 ICU 16 项匹配、Lint 0 错误/57 条既有警告/0 新增、v3 原证书和 16 KiB 对齐及完整性通过。这是本地验证完成，仍不声明手机症状已修复。

当前验证命令如下。它会检查当前源码；修复前 RED 固定以 E-003/E-004 的归档为准，不要求回滚当前工作区重现历史输出。

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.douai.module.feature.HostBehaviorHiddenVideoTest --console=plain
```

静态合同可在具备上述本地样本材料时复查：

```powershell
rg -n 'safety_code|safety_msg|item_id' douyin-40.6.0/apktool-no-res/smali_classes45/com/ss/android/ugc/aweme/feed/helper/VideoSafetyCheckItem.smali
rg -n 'LJI\(Ljava/lang/String;\)V|safety_check_recall' douyin-40.6.0/apktool-no-res/smali_classes45/kotlin/jvm/internal/ALambdaS32S1500000_4.smali
rg -n 'safety_check|LJIIIIZZ' douyin-40.6.0/apktool-no-res/smali_classes45/X/06ht.1.smali
rg -n 'deleteFilterRecallAwemeForSetData|Iterator;->remove' douyin-40.6.0/apktool-no-res/smali_classes45/X/06R8.smali
```

实现限定到两项安全任务执行时点：`ALambdaS32S1500000_4.invoke()` 仅 `$t=0`，以及 `06ht.invoke()`。保留任务之外的 `04Ov` bookkeeping、`$t=1` 与其他调用路径，开关开启时返回宿主 Unit；不拦所有 VideoEvent/recall，不改播放状态、权限或语音。任务执行前的开关变化、关闭生命周期、安装失败回滚及下一次数据过滤已纳入行为回归。既有两个较宽的 LJLJL 保护继续保留，本轮新增覆盖较窄不代表所有手动删除均不受影响；`$t=1` 在本文只称其他分支，不作手动删除的普遍映射。

诊断也已落地：`hidden_skip_enabled`、`hidden_skip_installed`、`hidden_skip_current_hits/blocked`、`hidden_skip_background_hits/blocked` 以及受控失败原因，通过模块入口适配状态读取。四条必需规则是旧两个 LJLJL 与新增两个安全任务；开关请求和完整安装分别呈现，后续手机复核可判断是否真正触达并被拦截，不能只凭 `installed` 认定用户症状已覆盖。

## Timeline 与剩余边界

| 日期 | 事件 | 证据 |
| --- | --- | --- |
| 2026-10-21 | 0.2.5 原优化完成严格本地验收，作为本轮生产基线封存 | E-001 |
| 2026-10-21 | 用户补充隐藏提示、自动跳走及上滑条目缺失现象 | E-005 |
| 2026-10-21 | 静态分析锁定安全任务、召回 Set 与独立列表删除链 | E-002 |
| 2026-10-21 | 修复前行为夹具实际跑出 12 项/5 失败/0 错误 | E-003、E-004 |
| 2026-10-21 | 原五个行为失败转绿，增补守护后 15 项行为与 6 项原 APK 合同通过；联合预检 7 套件/51 次通过 | E-006、E-007 |
| 2026-10-21 | 0.2.6 重新构建 Debug/仪器化 APK，平台模拟器三项通过 | E-008 |
| 2026-10-21 11:52（Asia/Shanghai） | 最终严格门禁 186 套件/1993 次执行通过，19 个案例证据文件绑定 manifest；保持本地候选、未发布 | E-009、E-010、[0.2.6 记录](release-0.2.6.md) |

最终 APK 为 4,665,659 字节，SHA-256 `454DE744ECA2EF22AA3535F230F76A9A5CB5A0061D2FF461BB40420FD53BD9BA`。签名 APK 合同只证明新规则/诊断标记已入包且规则资产与最终源码一致，不将其当成运行时回调成功。最终归档和证书见 [0.2.6 本地记录](release-0.2.6.md#最终本地归档)。

尚无手机 LSPosed 调用轨迹把用户看到的具体中文提示与该任务连接起来；平台模拟器合同不覆盖这一宿主行为。保留列表条目不等于服务端重新授权，也不保证媒体可以播放。语音按用户要求完全不动，超过 30 秒仍继续尝试提交。
