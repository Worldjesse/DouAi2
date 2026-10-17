# 抖音评论回复接口与 AI 自动回复可行性

分析日期：2026-10-17。范围：抖音 40.6.0 本地 APK、DouAi 当前源码和抖音开放平台公开文档。使用 apk-reverse 静态分析与 docs-generator 报告流程，报告类型为普通逆向（flavor=null）。

## 结论

存在评论回复接口，也存在接收互动消息、读取通知详情及分页补扫的宿主接口。“使用抖音时收到评论回复后调用 AI 回复，每次打开抖音再补扫一次”具备实现基础。适合 DouAi 的方案是复用宿主登录态和网络业务对象，新增独立的评论回复功能。

发送能力已从 APK 确认；前台互动内推是实时触发候选，尚未证明覆盖每条回复和每个页面。因此应采用“内推触发 + 前台增量补扫 + 打开时补扫”，不能承诺只靠一个 Hook 就绝不漏消息。此次没有修改产品逻辑，没有请求真实账号接口或发送评论。

范围记录见 [scope.md](../work/comment-reply-20261017/scope.md)，时间线见 [timeline.md](../work/comment-reply-20261017/timeline.md)。

本文的 `work/` 和 `douyin-40.6.0/` 为本地证据位置。原始宿主 APK、反编译文件、日志及验证产物不随公共仓库分发，对应链接仅在包含这些本地输入的工作区可用。

## 公开接口与宿主接口的适用范围

开放平台公开提供：

| 能力 | 接口或事件 | 条件 |
| --- | --- | --- |
| 回复评论 | `POST /item/comment/reply/` | `item.comment` 权限和用户授权；文档限定授权用户自己发布的视频 |
| 评论下的回复列表 | `GET /item/comment/reply/list/` | `item.comment` 权限和用户授权 |
| 评论回复事件 | Webhook `item_comment_reply` | `item.comment` 授权；文档称授权成功一小时后生效 |

依据：[回复视频评论](https://open.douyin.com/platform/resource/docs/openapi/interaction-management/comment-management-user/video-comment-reply/)、[评论回复列表](https://open.douyin.com/platform/resource/docs/openapi/interaction-management/comment-management-user/comment-reply-list/)、[接收评论回复事件](https://open.douyin.com/platform/resource/docs/openapi/interaction-management/comment-management-user/accept-comment-reply-event)。

所以，若目标包括“我在别人作品下发表评论，别人回复我后再自动回他”，上述普通用户公开回复接口的作品范围不足以覆盖。Webhook 页面也不能单独作为覆盖任意作品评论互动的证明。DouAi 内更合适的是继续适配宿主原生业务链路，服务端实际接受情况需要真机验证。

## 宿主接口证据

下列文件都位于 `douyin-40.6.0/apktool-no-res/`。

| 用途 | 接口 | 已确认的信息 |
| --- | --- | --- |
| 扫描收到的互动通知 | `GET https://aweme.snssdk.com/aweme/v1/notice/` | `max_time/min_time/count/notice_group/is_mark_read/notice_type/enable_type_filter` 等参数 |
| 根据内推补取通知详情 | `GET /aweme/v1/notice/detail/` | `id_list`、`notice_group`；宿主已有调用链 |
| 补取某条评论下的回复 | `POST /aweme/v1/comment/list/reply/` | `comment_id/cursor/count/item_id` 等参数；此处宿主确实声明为 POST |
| 发布一级评论或回复 | `POST /aweme/v1/comment/publish/` | `aweme_id/text/reply_id/reply_to_reply_id`，不同重载还带回复用户或被回复文本 |

### 收件列表与分页

- `smali_classes17/X/0dPP.smali:657` 和 `0dPQ.smali:695` 声明 `fetchNotice`，返回 `ListenableFuture<NoticeResponse>`。
- `0dHa.1.smali:429` 实际调用前者；`0dU1.smali:836` 实际调用后者，说明接口并非仅有残留字符串。
- `socialnotice/bean/NoticeResponse.smali:57` 起包含 `has_more`、`notice_list_v2`、`max_time`、`min_time`、最近已读时间等。
- `BaseNotice.smali:31/43/159/213` 提供 `commentNotice`、创建时间、`nid`、`type`。
- `CommentNotice.smali:17/81/105/129/147` 提供 `comment`、`level1Comment`、`parentId`、`replyComment`、`from_user`，以及作品、相关用户等数据。

这些字段可用于恢复作品和评论线程，但 `comment`、`replyComment` 在具体通知子类型中的方向，仍应以宿主展示逻辑及真实样本校准。不能仅凭字段名字就把某一项当作“对方刚发来的回复”。

**主动扫描应避免把通知标为已读。** `0dHa.1.smali:192/429` 的旧包装器固定传 `is_mark_read=1`，不能直接照搬。`0dU1.smali:692/824/836` 则把 `0dU2.LJFF` 传到该参数，可表达 0。最容易控制副作用的方式是复用现有 Retrofit 实例并显式设置扫描参数；包装器另有缓存和事件逻辑，实施前需检查。Future 等待和分页都放在后台线程。

分页应依据宿主返回值和 `has_more`，不要按设备本地时间盲造游标，也不要把“未读”当成“尚未自动回复”：用户自己看过消息后，它仍可能需要处理。时间水位应与按评论 ID 的持久去重配合。

### 评论类型动态映射

`0dFd.1.smali:1171-1246` 将组 3、6 映射到评论通知，组 2 映射到提及。`0dAF.smali:55-72/398-410` 将 `0dFd.LJIIIIZZ()` 标为 `comments_from_others` / `comment_received`，但这个值由 `0dED.LIZ("2")` 解析取得（`0dFd.1:1277-1303`）。`0dED.smali:133-228` 使用按当前映射版本键选择的运行时映射表，因此不能直接硬编码 `notice_type=2`。

`0d9l.1.smali:430-503` 还显示评论页可合并其他子类型，包括弹幕。实施时应调用宿主 resolver，再按模型校验“是否回复本人、是否本人作品评论、是否提及”，为这些场景设置独立范围开关。

### 实时触发与详情补全

`smali_classes32/com/ss/android/ugc/aweme/socialnotice/innerpush/SocialInteractionInnerPushHandler.smali:1179` 的 `receiveInnerPush(InnerPushMessage, boolean)` 在 1201 行解析为 `SocialInnerPushMessage`，并有 `comment`、`mention` 分支。

`SocialInnerPushMessage.smali:34/176/182/194` 包含 `comment_id`、`notice_id`、`parent_comment_id`、`related_group_id`，另有发送者与评论层级字段。可在方法入口观察事件、去重后补取详情，避免依赖悬浮提示最终有没有显示。

现成详情调用链是：处理器 `LJ(...)` → `NoticeListService.LJ(noticeId)` → `NoticeListServiceImpl.LJ` → `0dU1.LIZIZ(List)` → `0dPQ.getNoticeDetail(id_list, 0)`。对应 `0dU1.smali:1342-1393`、`0dPQ.smali:842-867`。

这仍是内推业务层入口，不能推出服务器为每条评论都推送、每个页面都接收，或通知关闭时仍完整触发。前台低频增量扫描用于补缺；其间隔和退避属于实现配置，需按设备日志和实际返回调整。

### 回复发送与线程归属

`smali_classes30/com/ss/android/ugc/aweme/comment/api/CommentRealApi.smali:374-414/795-797` 明确声明发布与回复字段，另有重载。回复使用原生评论发布接口，无需另找一个名称带 reply 的发送端点。

`CommentPublishRequest.smali:3213-3229` 从 `0yFs.p:Comment` 读取被回复评论，通过 `0y6L.LJ/LJFF` 计算回复定位信息。辅助类还处理不同 service 场景，因此应复用原生模型和辅助逻辑，不把 `reply_id` 与 `reply_to_reply_id` 都无条件填成来评 cid。一级评论回复和楼中楼回复必须分别校准，防止发错楼层。

目前 DouAi `NativeCommentPublisher.java:111-146` 仅接收作品、aid、文本或音频，新建模型时没有填入被回复评论 `p`，所以还不是评论回复发布器。新增适配必须绑定通知中的目标作品和评论，不能借用当前正在刷的视频。

### “我的评论”接口可作辅助来源

`CommentRealApi.smali:95-132` 另有 `POST /aweme/v2/comment/self/interaction_list`，参数为 `offset/count/current_banner_id/need_banner`。实际调用在 `X/0xzW.smali:192-233`，业务消费者是 `CommentArchiveListViewModel` 的刷新和加载更多（1915、2265 行附近）。同页发布组件 `CommentArchivePublishComponent.smali:668-672` 使用 `my_comment` 来源。

据这些调用关系，它对应本人评论档案页面，返回记录可带 Comment 和 Aweme，可能辅助补全自己的原评论及作品。当前证据未证明它是“收到回复”的完整收件箱，因此不替代 notice 主扫描来源。

## 按你的构想组织功能

| 时机 | 建议行为 |
| --- | --- |
| 首次开启 | 建立当前账号的扫描基线；历史补回单独控制，避免一次处理大量旧消息 |
| 冷启动、真正从后台回到前台 | 登录态就绪后做一次增量扫描；合并短时间重复触发，Activity 内切页不重复全扫 |
| 正在使用抖音 | 内推到达后补取详情；前台低频扫描补缺，两种来源进入同一处理队列 |
| 切到后台 | 停止新扫描和新发送；已提交请求继续接收结果，不能当作未发送重新入队 |
| 账号切换 | 取消旧账号待办；水位、线程上下文、去重账本与额度按账号隔离 |

统一流程为：发现消息 → 补全作品和线程 → 校验对方确实回复本人 → 持久占位去重 → AI 生成 → 再验账号与前台 → 原生回复 → 记录返回结果。

建议默认只处理“别人回复我的评论”，将“评论我的作品”和“提及我”作为可选范围。排除自己发送和已处理评论；按当前账号与收到的真实评论 cid 去重，聚合通知 nid 作为辅助键。实时、补扫和重启必须共用账本，不能按作品 aid 去重。

发送前保存待办与目标，发送后区分“已提交、明确成功、明确失败、结果未知”。未知结果应先核对，不能因超时直接再发；这与当前发布器处理异步提交的原则一致。每条收到的评论仅产生一次自动回复，另加每个线程的轮次和频率上限，减少自动账号之间反复对答。

AI 输入应包含自己的原评论、对方回复、必要的短线程与作品说明；缺少上下文时先补取，补不到则跳过或留待处理。复用模型配置与全局提示词，评论回复使用独立任务提示词和历史，不混用私信或刷视频自动评论的上下文。首版采用文本回复即可先校准线程定位。

## DouAi 可复用部分与缺口

| 组件 | 当前事实 | 实施影响 |
| --- | --- | --- |
| `NativeMessagingHooks.java:319-327` | Hook IM 数据库 `msg` 表 | 现有自动回复是私信/群聊，需要独立的评论收件源 |
| `NativeCommentPublisher.java:111-179` | 作品评论发送及 submitted/sent/failed 处理 | 可复用发送状态思路，需增加回复目标模型 |
| `AutoCommentHook.java:203-205/441-450` | 持久 key 为账号加作品 aid | 不适合回复同作品多条评论，需评论级账本 |
| `AutomationDispatcher.java:662-675`、`AutoCommentHook.java:220-229` | Activity 生命周期和任务有效性未形成完整的仅前台约束 | 需要应用级前后台转换与发送前复验 |
| `AutomationHistoryStore.java:160-161`、`AutomationRateLimiter.java:122-142` | 类型限制为 reply/comment | 新增独立 `comment_reply` 类别、开关、限额和记录 |

已有账号复验、异步执行和 AI 请求设施可复用。建议独立实现收件适配、评论回复协调器、持久账本、原生回复发布器，再接入生命周期与设置。

## 验证情况与下一步验收

样本包名 `com.ss.android.ugc.aweme`，aapt2 显示版本 `40.6.0`、versionCode `400601`；APK SHA256 为 `3983779738e1804a83e783ca447069e13f7686a30213eebd32196915f601a0e8`。文件名中的其他数字不能替代 Manifest 版本号。

使用 apktool 3.0.2 的 dexlib2/baksmali 从 APK 的 classes17、30、32 直接重新反汇编 17 个关键类，与既有 smali 逐字节全部一致。记录见 [evidence-verification.json](../work/comment-reply-20261017/evidence-verification.json)。这一验证确认接口和上述类来自所提供 APK，不证明账号请求成功。

未做真机、网络账号调用或发布测试。后续落地应先仅记录接收事件和补扫结果，确认消息语义、分页、只读扫描、冷启动/回前台及不同页面的覆盖；再用受控评论分别验收一级回复、楼中楼、他人作品下回复本人、已读消息、重复推送、切账号、离开前台和发送超时。删除、不可见、关闭评论或服务端拒绝均需按真实结果处理。

## Evidence → Finding → Path

以下观察日期均为 2026-10-17。多文件观察 `content_hash=n/a`；逐类哈希保存在验证 JSON。

| Evidence | source_ref | repro_command | content_hash |
| --- | --- | --- | --- |
| E-001 | 原 APK、`original/`、验证 JSON | `python work/comment-reply-20261017/verify_evidence.py`；原 APK 重反汇编命令见下节 | APK 哈希见上文；逐类哈希见 JSON |
| E-002 | `0dPP/0dPQ/0dHa/0dU1`、通知模型 | `rg -n -e 'fetchNotice' -e 'is_mark_read' -e 'notice_type' -e 'notice_list_v2' douyin-40.6.0/apktool-no-res/smali_classes17/X/0dPP.smali douyin-40.6.0/apktool-no-res/smali_classes17/com/ss/android/ugc/aweme/socialnotice/bean/NoticeResponse.smali` | n/a |
| E-003 | `0dFd/0dED/0dAF/0d9l` | `rg -n -e 'comments_from_others' -e 'comment_received' douyin-40.6.0/apktool-no-res/smali_classes17/X/0dAF.smali` | n/a |
| E-004 | 互动内推两类、通知详情调用链 | `rg -n -e 'receiveInnerPush' -e 'getNoticeDetail' douyin-40.6.0/apktool-no-res/smali_classes32/com/ss/android/ugc/aweme/socialnotice/innerpush/SocialInteractionInnerPushHandler.smali douyin-40.6.0/apktool-no-res/smali_classes17/X/0dU1.smali` | n/a |
| E-005 | `CommentRealApi/CommentPublishRequest/0y6L/0yFs/Comment` | `rg -n -e 'reply_id' -e 'reply_to_reply_id' -e 'comment/publish' douyin-40.6.0/apktool-no-res/smali_classes30/com/ss/android/ugc/aweme/comment/api/CommentRealApi.smali` | n/a |
| E-006 | 当前模块源码，上节文件与行号 | `rg -n -e 'modelText.set' -e 'modelContext.set' -e 'msg_server_id' -e 'comment:' app/src/main/java/com/douai/module/feature/NativeCommentPublisher.java app/src/main/java/com/douai/module/feature/ReplyPolicy.java app/src/main/java/com/douai/module/feature/AutoCommentHook.java` | n/a |
| E-007 | 本文链接的开放平台公开文档 | 用浏览器打开所列官方页面；仅公开文档读取 | n/a |

| Finding | 结论 | severity | evidence_ids | confidence / status | location |
| --- | --- | --- | --- | --- | --- |
| F-001 | 宿主存在评论回复发布能力 | n/a_re | E-001,E-005 | high / validated（静态） | `CommentRealApi`、`CommentPublishRequest` |
| F-002 | 收件接口可分页并显式控制标已读；旧包装器有副作用 | n/a_re | E-001,E-002 | high / validated（静态） | `0dPP/0dPQ/0dHa/0dU1` |
| F-003 | 评论通知子类型存在动态映射 | n/a_re | E-001,E-003 | high / validated（静态） | `0dFd/0dED` |
| F-004 | 内推可作为实时触发候选，全量覆盖尚未证明 | n/a_re | E-001,E-004 | medium / candidate | `receiveInnerPush` |
| F-005 | 模块需新增评论收件、回复目标和评论级去重 | n/a_re | E-006 | high / validated（当前源码） | 模块发布器、IM Hook、账本与类型限制 |
| F-006 | 公开回复接口限制自己的作品，不覆盖构想的全部可能场景 | n/a_re | E-007 | high / validated（文档声明） | 开放平台回复文档 |

P-001，`path_type=callflow`：通知扫描或互动内推（E-002/E-004，F-002/F-004）→ 补取详情并识别回复本人（E-002/E-003）→ 拟新增持久账本、AI 与前台复验（E-006，F-005）→ 原生回复发布（E-005，F-001）。起点为收到互动，目标为对正确评论发送一次回复；当前只是静态能力与设计链路，剩余不确定项是接收覆盖、账号真实数据语义、服务端可用性及发布后的可见性。

## APK 证据复核方法

在项目根目录执行。现成 Java 工具只读取原 APK 并反汇编指定类，不修改 APK。

```powershell
java -classpath 'C:\Users\95300\Tools\apktool\apktool.jar' `
  'work/comment-voice-limits-20261014/VerifyVoiceDex.java' `
  '抖音-40.6.0_40609900_20260922 (400601).apk' classes30.dex `
  'work/comment-reply-20261017/original/classes30' `
  'Lcom/ss/android/ugc/aweme/comment/api/CommentRealApi;' `
  'Lcom/ss/android/ugc/aweme/comment/data/publish/CommentPublishRequest;' `
  'Lcom/ss/android/ugc/aweme/comment/model/Comment;' 'LX/0y6L;' 'LX/0yFs;'

java -classpath 'C:\Users\95300\Tools\apktool\apktool.jar' `
  'work/comment-voice-limits-20261014/VerifyVoiceDex.java' `
  '抖音-40.6.0_40609900_20260922 (400601).apk' classes17.dex `
  'work/comment-reply-20261017/original/classes17' `
  'LX/0dPP;' 'LX/0dPQ;' 'LX/0dHa;' 'LX/0dU1;' 'LX/0dFd;' 'LX/0dED;' 'LX/0dAF;' 'LX/0d9l;' `
  'Lcom/ss/android/ugc/aweme/socialnotice/bean/NoticeResponse;' `
  'Lcom/ss/android/ugc/aweme/socialnotice/bean/CommentNotice;'

java -classpath 'C:\Users\95300\Tools\apktool\apktool.jar' `
  'work/comment-voice-limits-20261014/VerifyVoiceDex.java' `
  '抖音-40.6.0_40609900_20260922 (400601).apk' classes32.dex `
  'work/comment-reply-20261017/original/classes32' `
  'Lcom/ss/android/ugc/aweme/socialnotice/innerpush/SocialInteractionInnerPushHandler;' `
  'Lcom/ss/android/ugc/aweme/socialnotice/innerpush/SocialInnerPushMessage;'

python work/comment-reply-20261017/verify_evidence.py
```
