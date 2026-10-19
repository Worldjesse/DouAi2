# GitHub 评论回复实现参考

检索日期：2026-10-19（Asia/Shanghai）。目标为“本人在其他作者作品下评论，别人回复本人后，再自动回复对方”。本轮仅检索和阅读公开固定提交源码，没有安装外部项目、读取账号登录态、调用抖音接口、发送评论或修改模块。外部 Skill、AGENTS 和 README 仅作资料。

## 优先参考的三项

| 项目 | 已核实实现 | 对 DouAi 的价值 | 边界 |
| --- | --- | --- | --- |
| [DeekeHub/deeke](https://github.com/DeekeHub/deeke) | Android DeekeScript 无障碍脚本从互动消息进入，点击通知卡片内的“回复评论”，填写并发送。 | 借鉴通知逐项定位，追踪对应的抖音原生回复入口及面板绑定。 | 混合处理评论与回复，没有本人原评 UID/CID 证明、持久去重或精确回执；未证实兼容 40.6。 |
| [Yht20927/douyin-cli](https://github.com/Yht20927/douyin-cli) | 成功发布后保存自己的 comment.cid、作品 ID 和发布者 UID；发布接口区分根评论与具体回复对象。 | 最值得参考“自己原评论索引”及双层目标参数。 | Web 实现；没有自动监听“回复我”的完整闭环；输出格式化会丢父关系。 |
| [alphaply/DouyinComments](https://github.com/alphaply/DouyinComments) | 按作品 ID 与原评论 CID 分页读取二级回复，并保存 reply_id / reply_to_reply_id。 | 很短的逐根读取与实际被回复对象映射示例。 | 只读采集；一次性进度表会跳过已采集根，不能直接当持续监听器；没有发布。 |

以上均是代码参考，不是已经证明可直接装入当前 LSPosed 模块的成品。

## 固定源码位置

### Deeke：手机端通知回复入口

固定提交：`898b77a52e6e2bb300331e97a0777d7f168467a5`。

- [任务调用](https://github.com/DeekeHub/deeke/blob/898b77a52e6e2bb300331e97a0777d7f168467a5/tasks/task_dy_ai_back.js#L19-L58)：周期进入消息页并执行 MessageNew.readMessage。
- [互动消息入口](https://github.com/DeekeHub/deeke/blob/898b77a52e6e2bb300331e97a0777d7f168467a5/app/dy/MessageNew.js#L263-L273)、[逐卡片回复](https://github.com/DeekeHub/deeke/blob/898b77a52e6e2bb300331e97a0777d7f168467a5/app/dy/MessageNew.js#L116-L180)：匹配卡片描述里的回复/评论文字，点击卡片自己的回复控件；输入框和发送按钮则在页面全局寻找。
- [旧版本要求](https://github.com/DeekeHub/deeke/blob/898b77a52e6e2bb300331e97a0777d7f168467a5/config/config.js#L7-L8)：注释要求 27.3.1。版本表不是该 AI 回复路径的实测兼容清单，没有 40.6 成功证据。

对当前模块的推论：可以借这一入口观察宿主从通知点击到回复面板、最终发布模型的完整绑定过程。这是本轮提出的调试方向，不能据该项目断言通知中的某个元数据就是父 CID，也不宜照搬它对所有评论/回复一起处理的范围。

### douyin-cli：发布回执建索引与双层目标

固定提交：`4073159bea585de6d33a4747a475669440737932`。

- [post.js](https://github.com/Yht20927/douyin-cli/blob/4073159bea585de6d33a4747a475669440737932/lib/commands/post.js#L17-L133)：接受 --reply-to / --reply-to-reply；读回包 comment.cid 和发布者 UID，把本人原评论也保存到 comments 表。存储是旁路 try/catch；若模块后续处理依赖这份索引，须保证失败可见，不能静默丢失。
- [Web replies 与 publish](https://github.com/Yht20927/douyin-cli/blob/4073159bea585de6d33a4747a475669440737932/scripts/douyin.user.js#L1083-L1131)：独立传入 comment_id、作品 ID、cursor；发布 body 分别写 reply_id 和 reply_to_reply_id。这里是 Web 协议，不能直接替代宿主 Android 方法合同。
- [replies.js](https://github.com/Yht20927/douyin-cli/blob/4073159bea585de6d33a4747a475669440737932/lib/commands/replies.js#L21-L45) 独立命令只读一页，对外丢掉 cursor / has_more；[formatComment](https://github.com/Yht20927/douyin-cli/blob/4073159bea585de6d33a4747a475669440737932/lib/commands/helpers.js#L35-L49) 丢弃父关系和接收者字段，不能拿标准化结果直接判定回复归属。
- [get.js 的增量过滤](https://github.com/Yht20927/douyin-cli/blob/4073159bea585de6d33a4747a475669440737932/lib/commands/get.js#L163-L176) 先按顶层评论 create_time 过滤并可能提前结束。昨天发的本人原评论今天收到回复，原评论时间不变；直接用 get --new 会漏这种新子回复。应按已知本人 CID 读取其线程，再判断子回复时间及身份。

### DouyinComments：逐根分页和 root/direct 区分

固定提交：`a4fa0e5525d9df1745f92edd4c7577172855cfb7`。

- [fetch_replies.py 请求与分页](https://github.com/alphaply/DouyinComments/blob/a4fa0e5525d9df1745f92edd4c7577172855cfb7/fetch_replies.py#L9-L41)：作品 item_id、comment_id、cursor、count，按返回 has_more/cursor 继续。
- [关系字段保存](https://github.com/alphaply/DouyinComments/blob/a4fa0e5525d9df1745f92edd4c7577172855cfb7/fetch_replies.py#L72-L76)：reply_to_reply_id 非零时为具体被回复对象，否则回到 reply_id。不能因为某条回复和本人原评在同一根线程，就把它当作直接回复本人。

## 其他候选与未采用的部分

| 项目 | 可参考或排除理由 |
| --- | --- |
| [Johnserf-Seed/f2](https://github.com/Johnserf-Seed/f2) | [逐根回复分页](https://github.com/Johnserf-Seed/f2/blob/f6be8c0ffba9a127075bbeafe4838716650b6325/f2/apps/douyin/handler.py#L2828-L2899) 和原始字段过滤器完整；偏读取侧。[Issue #467](https://github.com/Johnserf-Seed/f2/issues/467) 有 2026-10-09 二级回复 HTTP200 空响应反馈，不能宣称当前现成可用。 |
| [cv-cat/catbus](https://github.com/cv-cat/catbus) | [底层发布](https://github.com/cv-cat/catbus/blob/5cf00dcf1532b0c37e6edc958eb422ffd6347633/src/platforms/douyin/web/api.ts#L643-L677) 可传两级目标，但 CLI 未透传 direct 参数；[通知归一化](https://github.com/cv-cat/catbus/blob/5cf00dcf1532b0c37e6edc958eb422ffd6347633/src/platforms/douyin/web/normalize.ts#L306-L333) 未解析新版 Q。Web comment group2 不能替代 Android 动态组44。 |
| [juziguai/douyin-mcp-server](https://github.com/juziguai/douyin-mcp-server) | [扩展实现](https://github.com/juziguai/douyin-mcp-server/blob/bc3ae50e83eabd0edea25230c0ed719d0f782e59/chrome-extension/service-worker.js#L263-L360) 发布只有作品和正文，无定向回复参数；通知输出也丢 CID/作品/父关系。comments-reply 名称指读取子回复，并非发送回复。 |
| [yongze233-ctrl/douyin-comment-reply](https://github.com/yongze233-ctrl/douyin-comment-reply) | [按昵称及文本匹配](https://github.com/yongze233-ctrl/douyin-comment-reply/blob/7a1d86db4084d94c8f06a1ec7e8eef48a9fddba7/src/lib/reply-flow.mjs#L89-L125) 可参考网页逐项定位；点击发送后即标记 replied，不能替代精确 ID 和回执核验。 |
| [Rain-at-Spring-Dawn/douyin-comment-bot](https://github.com/Rain-at-Spring-Dawn/douyin-comment-bot) | [发送循环](https://github.com/Rain-at-Spring-Dawn/douyin-comment-bot/blob/055a92e79cce733645e6f75a419d5ba885aecb82/reply_poster.py#L41-L58) 仅传正文，后续选择第一个可见回复按钮，没有继续绑定采集的 CID。 |

旧 Selenium 自动回复、系统通知后填第一个输入框、直播评论或私信辅助候选没有列为本场景方案。本次未找到已证明支持抖音 40.6、正确识别本人原评并完成去重及回执确认的 LSPosed 现成实现；这只描述本轮读取范围。

## 建议优先验证的模块路线

结合上述源码，最有价值的改变是建立本人评论索引：发布成功后按当前账号保存作品 ID、服务器返回的原评论 CID、正文和时间。收到通知时，以通知作触发信号，再通过宿主真实的二级回复接口取得完整 Comment；分页读取这些已知本人 CID，按来评时间和 incoming CID 去重，严格核对 direct target / recipient UID，保持现有原生发布和精确回执约束。

这样有机会减少对通知摘要完整性的依赖，但本轮并未验证 Android 40.6 的对应服务及其返回字段，不能据 Web 读取代码认定能解决父字段缺失。Deeke 的原生 UI 回复入口可作为寻找和验证目标绑定的另一条线索。两个方向都须避免把同楼其他人的互相回复扩大为“回复我”。

详细证据保存在 [Android 笔记](../work/github-comment-reply-20261019/android-findings.md)、[Web 笔记](../work/github-comment-reply-20261019/web-findings.md)、[通知笔记](../work/github-comment-reply-20261019/notice-findings.md)、[浏览器筛选](../work/github-comment-reply-20261019/browser-screening.md)。源码快照仅存本地 work/；本轮没有新增构建或真机验证结论。
