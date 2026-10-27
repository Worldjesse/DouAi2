# DouAi 0.2.23：新增抖音 40.5.0 宿主适配

版本 **0.2.23 / 124**，包名 `io.github.yudigaga.douai`。本版在保留 **40.6.0 / 400601** 与 **40.8.0 / 400801（内部构建 40809900）** 两套独立配置的前提下，新增第三套 **40.5.0 / 400501（内部构建 40509900）** 适配。本版只做本地构建与静态分析，未在手机或模拟器上运行，未发布 GitHub/LSPosed。

## 样本与身份

| 项 | 值 |
| --- | --- |
| 原 APK | `抖音 v40.5.0 (400501).apk`，用户本地提供 |
| 字节数 | `355,369,816` |
| SHA-256 | `ED29C18F48CAD27F602158D54A0A71BF03EE4A6CB1EC105661C0C8F87EBC39C4` |
| 二进制 Manifest | `com.ss.android.ugc.aweme` / `40.5.0` / `400501` |
| `UPDATE_VERSION_CODE` | `40509900`（元数据实测，非文件名推断） |
| DEX 数量 | 54 |
| `resources.arsc` | `12,052,524` 字节，SHA-256 `1cb9fc8e949c0f1852019319c8e80f1ccb11434346ae0dc0562ca023604fb17d` |

## 规则适配

以 40.6.0 的 229 条规则为基线，直接在 40.5.0 的 54 个 DEX 上按 `HostRule`/`HostRuleSet` 的匹配语义复算：

| 阶段 | 结果 |
| --- | --- |
| 基线（40.6.0 规则 × 40.5.0 APK） | 唯一 **146** / 歧义 0 / 未命中 **83** |
| 交付资产回扫 | 唯一 **209** / 歧义 0 / 未适配 **20** |

- 交付资产 `app/src/main/assets/douyin-host-rules-40.5.0.tsv`：229 行 × 15 列，文件 SHA-256 `7ba7c85090e95248fb05d9903f780883f00bd8cd5be92e5f5995188ebc302637`。
- 语义指纹按 `HostRuleSet.computeFingerprint()` 复算为 `35c76cd658ff0257a7516bd80def9a34a78b1ece5a4fbd04e375e8d33dcdde31`；该复算脚本先用 40.6.0（`ed977e2e…`）与 40.8.0（`ab5215b5…`）两个已知指纹验证一致后才用于 40.5.0。`HostRuleAssetProfilesTest` 在 CI 中再次用真实解析结果核对。
- 改写 63 条、未适配 20 条。未适配行按原描述符保留，保证 229 条规则 ID 覆盖不变，运行时按既有诊断报告 `NOT_FOUND` 而不是崩溃。

### 40.5.0 上不可用的 Hook（20 条规则，feature 契约内 7 条）

| 功能 | 缺失规则 | 影响 |
| --- | --- | --- |
| `prevent_skip_hidden` | `adv_ui_delete_aweme` | 普通 feed 面板的删除入口拦不住；长按菜单与后台隐藏任务规则已改写可用 |
| `native_auto_reply` | `adv_im_global_received` | 被动收消息触发不可用，其余 19 条已改写 |
| `comment_reply` | `comment_reply_notice_provider`、`comment_reply_own_provider`、`comment_reply_received_group`、`comment_reply_thread_fetch`、`comment_reply_thread_builder` | 通知、自己评论、楼中楼的取数与服务提供方缺失；模型/映射/发布层已改写 |

其余 13 条未适配规则不属于任何 feature 契约（`interface`、`playback`、`message_voice` 分组的附加锚点）。

## 资源与类名

- 40.6.0 的 51 项 Feed 资源名在 40.5.0 的 `id` 类型中**全部存在且唯一**（缺失 0、多候选 0），但其中 **48 项 ID 与 40.6.0 不同**，因此按资源名重新定位，不按旧 ID 套用。
- 实际写入 `HostResourceProfile.IDS_405` 的是 **50 项**：`w05` 在 40.6.0→40.8.0 之间改名（40.8.0 为 `w01`），没有可对齐的规范编号，故不写入；该点由 `HostAdaptationTest` 以「40.5.0 为 50 项、其余为 51 项」显式断言。
- `comment_container`（0x7f0a2512→0x7f0a2544，Δ+50）与 `comment_layout`（Δ+51）明显偏离其余条目的 −1…−12 位移，静态解析无法判定是否同一控件，**需真机确认**。
- 章节挂载点 `0x7f0a3fd6` 没有 40.5.0 证据，`HostResourceCatalog` 对 40.5.0 显式返回未适配，而不是套用其它版本的资源名。
- 原生界面类名只解析出两项：`viewFactory` → `LJJ`、`avatarLoader` → `LJIIIZ`（后者与 40.6.0 同名，且反射签名 `(AvatarImageView, UrlModel, Z, I, String, I)V` 与 `User.avatarThumb` 类型完全对上）。其余 18 个 `X.xxxx` 类名**无法在 40.5.0 唯一确定**，代码返回哨兵值 `X.DouAiUnadapted405`，让原生聊天、相册选择器与头像加载在该版本上走既有「尚未适配」失败路径，而不是误绑其它版本的混淆类。

## 边界与风险

1. 全部结论来自静态分析（DEX 指令行走 + `resources.arsc` 解析），**没有真机、模拟器或真实服务验证**。
2. 3 条基线唯一命中属「同名巧合」（`component_bottom_navigation`、`auto_message_plain_text_mode`、`comment_reply_thread_request`），规则内没有结构锚点，不建议当作已验证语义。
3. 3 条测速规则（`adv_gesture_player_get_speed`、`adv_player_get_speed`、`adv_player_manager_get_speed`）为单证推断；`adv_gesture_long_press` 的 `invoke_owners` 在 40.5.0 无客观对应，已置空而非猜测。
4. `HostResourceProfile.LEGACY_IDS` 中另有 31 条（原生聊天/相册/颜色等类型）未纳入本次 40.5.0 定位，40.5.0 对这些条目保持恒等映射。

## 验证方式

本版通过 GitHub Actions 的 `Android CI`（`:app:assembleRelease`、`:app:testDebugUnitTest`、`:app:lintRelease`）与 `Platform highest quality build`（定向测试 + `:app:assembleDebug` + APK 工件）验证；具体运行与工件摘要见提交记录。原 APK 相关契约测试需要 `DOUAI_TEST_APK` 指向真实安装包，CI 上没有该输入，因此与设备相关的一致性仍待本地复验。
