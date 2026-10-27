# DouAi 0.9.99：抖音 40.5.0 实机验证版本

版本 **0.9.99 / 125**，包名 `io.github.yudigaga.douai`。本版内容为 0.2.23 引入的抖音 **40.5.0 / 400501（内部构建 40509900）** 宿主适配，本次升级仅提升版本号并补充实机验证结论，未改动适配规则、资源表或类名映射。

## 实机验证

| 项 | 结论 |
| --- | --- |
| 设备宿主版本 | 抖音 40.5.0（用户实机安装） |
| 验证内容 | **最高画质视频下载可正常工作** |
| 结论来源 | 用户实机反馈 |
| 未覆盖 | 评论回复、原生私信自动回复、隐藏视频保护、原生聊天界面、相册选择器均未逐项实机验证 |

实机通过的范围只包含下载路径，不能据此推断其余功能的运行时行为。

## 适配范围（与 0.2.23 相同）

| 项目 | 结果 |
| --- | --- |
| 规则资产 | 229 条，`HostRuleSet` 语义指纹 `35c76cd658ff0257a7516bd80def9a34a78b1ece5a4fbd04e375e8d33dcdde31`，回扫唯一 209 / 未适配 20 |
| Feed 资源 | 51 项名称全部唯一定位，实际映射 50 项（`w05` 无 40.8 规范对应） |
| 类名 | `viewFactory` → `LJJ`、`avatarLoader` → `LJIIIZ`；其余 18 项返回 `X.DouAiUnadapted405` 走未适配路径 |
| 版本锁 | 仅内部构建 `40509900` 加载 40.5.0 配置 |

## 40.5.0 上仍不可用的功能

- `comment_reply`：通知、自己评论、楼中楼取数（缺 5 条规则）
- `native_auto_reply`：被动收消息触发（缺 `adv_im_global_received`）
- `prevent_skip_hidden`：普通 feed 面板删除拦截（缺 `adv_ui_delete_aweme`）
- 原生聊天布局、相册选择器、头像加载：18 个混淆类名无法唯一确定

## 签名与产物状态

本版**尚未生成正式签名包**：仓库要求的四项发布签名配置（`douaiReleaseStoreFile`、`douaiReleaseStorePassword`、`douaiReleaseKeyAlias`、`douaiReleaseKeyPassword` 或对应 `DOUAI_RELEASE_*` 环境变量）在本机不存在，密钥库也未随仓库分发。

因此当前可得产物为：

- GitHub Actions 的 unsigned Release APK（`assembleRelease` 产物，未签名，不可直接安装）
- GitHub Actions 的 debug APK 工件（可安装，但为调试签名，不能覆盖正式版升级）

正式包需要在具备长期发布密钥库的环境中执行 `:app:assembleRelease` 并用 `tools/verify-release.ps1` 校验。发布证书 SHA-256 期望值为 `FAE67C134F4C4FA73B8E34487C47A8C7F3E7C34B840BC245AFAC51585B7DFD47`，签名后必须与该值一致。
