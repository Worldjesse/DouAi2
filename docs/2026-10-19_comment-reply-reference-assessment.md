# douyin-comment-auto-reply 参考项目评估

检查日期：2026-10-19。检查提交：[`1e4f420700ba2916536753aaebaca589601fc9db`](https://github.com/jinhuadeng/douyin-comment-auto-reply/tree/1e4f420700ba2916536753aaebaca589601fc9db)。仅阅读源码与资料，未安装依赖、运行外部脚本、登录账号或发送评论。

**结论：该提交没有直接解决“我在别人作品下评论 → 别人回复我 → 自动回复对方”的现成实现。** 它提供评论草稿处理与浏览器执行框架；项目声明的主要范围是自己账号、自己作品下的评论。这里把外部 `SKILL.md` 作为被检查资料，未将其指令作为本次任务要求。[项目范围，L3、L18–21](https://github.com/jinhuadeng/douyin-comment-auto-reply/blob/1e4f420700ba2916536753aaebaca589601fc9db/SKILL.md#L3-L21)

| 当前需要 | 该提交实际实现 | 对当前问题的帮助 |
| --- | --- | --- |
| 自动发现别人对我评论的新回复 | 从人工提供的 CSV 读取评论文本并输出 JSON；没有采集通知的执行入口。[草稿输入，L65–93](https://github.com/jinhuadeng/douyin-comment-auto-reply/blob/1e4f420700ba2916536753aaebaca589601fc9db/scripts/batch_comment_drafts.py#L65-L93) | 不能替代 DouAi 的通知发现与解析。 |
| 确认对方回复的是我的原评论 | 输入输出主要是 `comment`、`video_topic`、意图、草稿等；执行器不读取账号 UID、作品 ID、原评论 CID 或父评论 CID。[草稿结构，L81–90](https://github.com/jinhuadeng/douyin-comment-auto-reply/blob/1e4f420700ba2916536753aaebaca589601fc9db/scripts/batch_comment_drafts.py#L81-L90)、[执行器，L25–63](https://github.com/jinhuadeng/douyin-comment-auto-reply/blob/1e4f420700ba2916536753aaebaca589601fc9db/scripts/browser_reply_runner.py#L25-L63) | 无法补齐本次缺失的回复归属关系。 |
| 定位到对应回复并发送 | 操作者提供网页 URL、输入框和按钮 CSS selector；循环始终使用同一组 selector 执行 `fill`、`click`，没有逐项评论定位逻辑。[参数，L12–22](https://github.com/jinhuadeng/douyin-comment-auto-reply/blob/1e4f420700ba2916536753aaebaca589601fc9db/scripts/browser_reply_runner.py#L12-L22)、[发送循环，L45–58](https://github.com/jinhuadeng/douyin-comment-auto-reply/blob/1e4f420700ba2916536753aaebaca589601fc9db/scripts/browser_reply_runner.py#L45-L58) | 需另外开发网页采集、身份匹配与逐项定位。 |
| 确认真正回复成功 | 脚本在命令结束后记录评论、回复、动作；没有读取发布返回或检查服务端 CID，`dry-run` 也进入相同日志写入流程。[日志，L52–67](https://github.com/jinhuadeng/douyin-comment-auto-reply/blob/1e4f420700ba2916536753aaebaca589601fc9db/scripts/browser_reply_runner.py#L52-L67) | `.sent-log.json` 本身不能证明发布成功。 |

检查该提交的完整文件清单及两个 Python 脚本，未发现 Android/Xposed Hook、抖音原生通知模型或评论接口适配；不能将网页点击框架直接移植成 LSPosed 修复。[固定提交文件树](https://github.com/jinhuadeng/douyin-comment-auto-reply/tree/1e4f420700ba2916536753aaebaca589601fc9db)、[浏览器调用，L6–8、L39–58](https://github.com/jinhuadeng/douyin-comment-auto-reply/blob/1e4f420700ba2916536753aaebaca589601fc9db/scripts/browser_reply_runner.py#L6-L58)

可借鉴的是意图分类、固定回复模板、批量上限与人工审核分流；这些属于回复内容和运营流程。路线图仍把去重、按作品限速、带时间戳与评论 ID 的发送日志列为自动执行前应具备的条件，不能视为脚本已经实现。[模板与动作，L24–63](https://github.com/jinhuadeng/douyin-comment-auto-reply/blob/1e4f420700ba2916536753aaebaca589601fc9db/scripts/batch_comment_drafts.py#L24-L63)、[路线图，L31–38](https://github.com/jinhuadeng/douyin-comment-auto-reply/blob/1e4f420700ba2916536753aaebaca589601fc9db/references/automation-roadmap.md#L31-L38)

对照当前 DouAi，待验证的关键仍是原生通知中的“收件人是当前账号、原评论属于当前账号、作品与父评论对应关系”，以及最终发布回执。当前实现位于 [NativeCommentReplyInbox.java](../app/src/main/java/com/douai/module/feature/NativeCommentReplyInbox.java#L377-L459) 与 [NativeCommentPublisher.java](../app/src/main/java/com/douai/module/feature/NativeCommentPublisher.java#L253-L282)。本次参考项目检查没有新增真机证据，也不能据此确认 0.1.91 已在设备上自动回复成功。
