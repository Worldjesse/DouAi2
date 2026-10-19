# 0.1.86：统一模型响应方式与推理强度

本轮本地交付版本为 `0.1.86 / versionCode 87`，加入统一响应方式与可选推理强度。215 项定向检查、169 个套件的 1625 项完整回归、Release 构建及签名产物核对全部通过。真实模型和真机行为尚未验证，未远端发布。

## 选择响应方式

在抖音设置 → DouAi → 扩展功能 → AI 模型设置中，编辑模型的“接口协议”“响应方式”和“推理强度”。响应方式可选“非流式”或“流式”，保存并启用后生效。模型编辑页的测试对话可使用尚未保存的草稿；正式功能使用已保存配置，消息联系人使用该联系人对应的模型。

响应方式按模型分别保存为 `responseMode`：`non_streaming` 为非流式，`streaming` 为流式。已有配置缺少该字段时默认非流式。升级不会因曾使用 0.1.85 的 Responses 本地分析而自动把该模型改为流式；需要时由使用者在模型设置中选择。

| 功能 | 使用的模型配置 | 展示或发送时机 |
| --- | --- | --- |
| 模型编辑页测试 | 当前编辑草稿 | 完整回复通过校验后显示 |
| 已启用模型的测试对话 | 已保存并选中的模型 | 完整回复通过校验后显示 |
| 消息页 AI 联系人 | 该联系人对应的已保存模型 | 完整回复通过校验后显示 |
| 私聊、群聊和评论 AI 自动回复 | 已保存并选中的模型 | 完整回复通过校验后按原流程发送 |
| AI 自动评论 | 已保存并选中的模型 | 完整回复通过任务校验后按原流程发送 |
| 本地数据分析 | 准备分析时已保存并选中的模型 | 完整解读通过图表结构和长度校验后显示 |

图片、视频抽帧与文件文本仍先按原流程准备，再通过对应功能的同一模型客户端发送。流式接收不会使附件提前发送，不代表界面逐字显示，也不会把增量片段作为多条自动回复或评论发送。

## 四种协议的接收方式

| 接口协议 | 非流式 | 流式 |
| --- | --- | --- |
| OpenAI Chat Completions | `chat/completions`，`stream=false` | 同一操作路径，`stream=true` |
| OpenAI Responses | `responses`，`stream=false` | 同一操作路径，`stream=true` |
| Claude Messages | `messages`，`stream=false` | 同一操作路径，`stream=true` |
| Gemini | `models/{model}:generateContent` | `models/{model}:streamGenerateContent?alt=sse` |

接口协议决定请求结构、鉴权和响应解析，响应方式决定一次性接收还是分段接收。Responses 的事件处理参照 [OpenAI 流式指南](https://developers.openai.com/api/docs/guides/streaming-responses)与[流式事件定义](https://developers.openai.com/api/reference/resources/responses/streaming-events)；Claude 参照[消息流式文档](https://platform.claude.com/docs/en/build-with-claude/streaming)，Gemini 参照[内容生成接口](https://ai.google.dev/api/generate-content)。

一次生成只提交一次 POST。流式响应如果在同一个连接返回完整 JSON，按对应协议兼容解析，不额外提交请求。流式失败不会自动改为非流式、静默降级或自动重试；需要更换响应方式时由使用者修改模型配置。中转是否支持所选模型和响应方式仍需实际服务验证，流式不能保证解决固定网关时限或上游故障。

完整结果才进入历史、自动发送或本地分析展示。断流、服务端错误或不完整结果不能因为已收到部分文字就当作成功；非流式也会拒绝 `status` 或 `finish_reason` 明确标记为未完成的结果。流式等待受所选模型的绝对总等待预算约束，取消或超时后不再发布迟到结果；仍被平台阻塞的网络资源使用有界后台清理。非流式的超时仍按连接和读取阶段计算。

## 设置推理强度

模型配置中的“推理强度”除“模型默认”外，直接显示英文原始值，并按接口协议提供候选。选择保存在 `reasoningEffort`，模型默认对应 `default`，其他候选按原值保存。测试对话、消息联系人、AI 自动回复、AI 自动评论和本地分析均使用其对应模型的选择。旧配置缺少该字段时使用模型默认，不额外附加强度参数，也不覆盖自定义高级参数中已有的设置。

| 接口协议 | 本轮界面候选 | 显式强度对应的请求字段 |
| --- | --- | --- |
| OpenAI Chat Completions | 模型默认、`none/minimal/low/medium/high/xhigh/max` | `reasoning_effort` |
| OpenAI Responses | 模型默认、`none/minimal/low/medium/high/xhigh/max` | `reasoning.effort` |
| Claude Messages | 模型默认、`low/medium/high/xhigh/max` | `output_config.effort` |
| Gemini | 模型默认、`minimal/low/medium/high` | `generationConfig.thinkingConfig.thinkingLevel` |

这些值是模块按协议提供的候选，不是当前模型或中转已验证的支持清单。推理强度需所选模型和中转同时支持；实际支持的值以服务元数据或对应模型文档为准。它与温度、最大输出 Token 分别设置。增加推理投入可能增加等待时间和 Token 消耗，不保证回答或人物侧写更正确。具体能力与限制分别见 [OpenAI 推理模型说明](https://developers.openai.com/api/docs/guides/reasoning)、[Claude effort 说明](https://platform.claude.com/docs/en/build-with-claude/effort)和 [Gemini Generate Content thinking 说明](https://ai.google.dev/gemini-api/docs/generate-content/thinking.md)。

模型列表接口的能力字段并不统一：[OpenAI GET /models](https://developers.openai.com/api/reference/resources/models/methods/list)没有提供推理档位枚举；[Gemini Model](https://ai.google.dev/api/models)有 `thinking` 支持标记，但没有档位列表；[Claude List Models](https://platform.claude.com/docs/en/api/models/list)当前可提供 `capabilities.effort` 及各档支持标记，但中转未必返回同样字段。本轮保留手动选择实际值，不增加缺少统一数据来源的自动探测按钮，也不把模型自述当作能力元数据。

显式选择任一非默认值时，如果高级参数已设置相同的强度字段，请求会在发送前拒绝，并提示解决冲突。例如 Responses 的高级参数含 `reasoning.effort` 时，不能再从下拉框显式选择强度；可改回模型默认以保留高级参数，或删除该重复字段。`reasoning.summary` 等其他嵌套字段仍会保留，不因设置强度而被整个对象覆盖。

Gemini 的显式 `thinkingLevel` 与高级参数中的 `thinkingBudget` 也互斥，冲突时在发送前拒绝；“模型默认”不附加 `thinkingLevel`，继续保留已有 `thinkingBudget`。

## 保持原有功能规则

本轮统一模型传输选择与可选推理强度，不改变全局提示词与各功能任务提示词、上下文轮数、附件准备、自动回复与自动评论的发送规则。本地分析继续只在主动点击后发送有界统计摘要，不混入聊天历史，仍需返回完整图表解读和人物侧写结构。

语音合成使用独立的系统或第三方语音来源设置，第三方媒体解析使用自身接口配置。它们不随语言模型的接口协议、响应方式或推理强度切换；固定正文或模板回复也不会因此变成模型请求。

## 验证与交付状态

响应方式与推理强度的定向检查按各测试类最新结果汇总，合计 14 个套件、215 项 JUnit 测试全部通过，0 失败、0 错误、0 跳过。原始记录保存在 `artifacts/model-response-0.1.86/final-targeted-junit/`。其中 `ReasoningEffortClientTest` 用 8 项 JUnit 测试覆盖了 108 次成功请求组合；组合数不是额外的 JUnit 测试项，未重复计入总数。Miuix 界面检查包含滚动选择 `max` 并保存。

最终配置与客户端 16 项检查、Release 构建及 Lint 成功，耗时约 2 分 54 秒；Lint 为 0 错误、63 警告。最终 APK 的归档与构建输出 SHA-256 一致，`aapt` 版本元数据及 v3 签名已核对。

使用最终归档 APK 的完整回归运行约 7 分 25 秒，169 个套件、1625 项测试通过，0 失败、0 错误、0 跳过；其中最终 APK 的 3 项契约检查全部执行通过。回归后再次核对归档与构建输出，大小及 SHA-256 未变。原始完整记录位于 `artifacts/model-response-0.1.86/full-final-junit/`，结果汇总见[验证清单](../artifacts/model-response-0.1.86/verification.json)。

| 产物 | 已核对结果 |
| --- | --- |
| APK | `artifacts/DouAi-0.1.86-release.apk` |
| 版本 | `0.1.86 / 87` |
| 大小 | 4,484,869 字节 |
| SHA-256 | `A75EF22249981B8115C4E78B59B89EB78F03072AC59F3EDD41944C08ACDB6D31` |
| 签名证书 SHA-256 | `FAE67C134F4C4FA73B8E34487C47A8C7F3E7C34B840BC245AFAC51585B7DFD47` |

| 项目 | 当前状态 |
| --- | --- |
| 包含推理强度的定向 JVM 与 Miuix 检查 | 14 个套件、215 项通过，0 失败、0 错误、0 跳过 |
| 包含推理强度的最终完整 JVM 回归 | 169 个套件、1625 项通过，0 失败、0 错误、0 跳过 |
| 最终 Release 构建与 Lint | 成功；Lint 0 错误、63 警告 |
| 最终 APK 归档、版本元数据、签名和哈希 | 已核对，归档与构建输出一致 |
| 真实模型与中转服务 | 未由代理请求，待设备实测 |
| 真机界面、自动回复与自动评论 | 待实测 |
| GitHub 与 LSPosed 发布 | 未发布 |
