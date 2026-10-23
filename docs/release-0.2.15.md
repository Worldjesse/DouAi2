# DouAi 0.2.15 本地候选：视频分类筛选扩展

本地候选 **0.2.15 / 116**，包名 `io.github.yudigaga.douai`。仅本地构建与验证，未上传 GitHub 或 LSPosed，未创建发布标签。

## 筛选变化

- 新增默认关闭的“去商品/购物卡片”开关，过滤 40.6/40.8 宿主 `isProductCard()` 类型 112 与 `isShoppingCardAweme()` 类型 9001。
- 新增默认关闭的“去热点榜单卡”开关，过滤宿主 `isHotSpotRankCard()` 类型 104。
- 附近广告卡纳入现有“去广告”开关。
- “去推荐商家”限定为类型 140 且宿主 `isLifeSpuCard()` 确认的生活 SPU 场景，避免将同类型音频 D 卡一并过滤。
- 保留 0.2.14 自动滑动晚安装恢复、暂停 Activity 保护和长按抬手事件消费修复。

新增开关默认关闭；已有设置不会因升级而新增过滤条件。

## 宿主证据

40.8.0 / 内部构建 40809900 原 APK SHA-256：`222B0A37D505060902980D5C3B9F89FCFD49232AE03A40D563C65B292F64D53F`。原始反编译证据位于 `work/readapt-40809900-20261021/semantics/new-original/com/ss/android/ugc/aweme/feed/model/Aweme.smali`；40.6.0 对照位于 `douyin-40.6.0/apktool-no-res/smali_classes16/com/ss/android/ugc/aweme/feed/model/Aweme.smali`。两版均检查到上述分类方法。新增 `FeedFilterHostContractTest` 直接读取原始 DEX，验证 112、9001、104、附近广告结构标志和生活 SPU scene 条件。

其他可辨识类型不做宽泛过滤：104 是独立热点榜单卡；112/9001 是商品/购物卡；127/128 已由长视频筛选覆盖；140 需生活 SPU 分类确认；145 维持原推荐卡设置。普通热点、带货元数据、系列内容和视频横竖屏属性不等于卡片，因此没有新增为过滤条件。

## 本地验证

严格本地验收使用 40.8.0 / 40809900 原 APK，执行 Release 构建、全量 `:app:testDebugUnitTest`、Release Lint、APK/Dex 合同、签名、对齐和 ICU 检查。

- **2,222 项测试、212 个套件**：0 失败、0 错误、0 跳过。
- Release Lint：0 错误、56 条既有警告，增量预算通过。
- 新增分类合同在 40.6.0 与 40.8.0 原 APK 上分别通过。
- APK 使用原长期证书 v3 签名；包名、版本、ZIP 对齐和 APK 摘要检查通过。
- 手机安装、LSPosed 运行与真实推荐流场景未执行。

验收目录：`artifacts/release-verification/20261022T170629Z-8912dc2f/`。清单状态 `verified-local`、`published=false`，基于提交 `59e02085c3fd4f7b972aca8d224d02665df06c7c` 加未提交工作区。

## APK

[DouAi-0.2.15-release.apk](../artifacts/DouAi-0.2.15-release.apk)，**4,853,040 字节**，SHA-256：`8AA8CBE6DF64EA4386402CA51C61713051C96710254AF63EF162F25E4275AB62`。

签名证书 SHA-256：`FAE67C134F4C4FA73B8E34487C47A8C7F3E7C34B840BC245AFAC51585B7DFD47`。验收清单和 SHA256SUMS 位于上述验收目录。
