# DouAi video-filter category expansion report

> Date: 2026-10-23 (Asia/Shanghai)  
> Flavor: null (ordinary APK reverse analysis and module implementation)  
> Scope: [existing 40809900 adaptation scope](../work/readapt-40809900-20261021/scope.md)

## Summary

The 40.8.0 internal build 40809900 Aweme model exposes separate product-card, shopping-card, hotspot-rank-card, nearby-ad-card and Life SPU predicates that the module's filter surface did not fully expose. DouAi 0.2.15 adds independent product/shopping and hotspot-card filters, includes nearby ad cards under the existing ad switch, and narrows the type-140 merchant rule to the host's Life SPU predicate. The new switches default off. Strict local verification passed; device behavior was not tested.

## Scope

The referenced scope authorizes local analysis of the 40.8.0 / 40809900 host APK and continued 40.6 compatibility. The host APKs were read-only evidence. This task edited only DouAi source, tests and local documentation; it did not patch either host APK or publish a release.

## Evidence

| ID | Evidence | Reproduction / digest |
| --- | --- | --- |
| E-001 | 40.8.0 / 40809900 identity and SHA-256 recorded in the existing [adaptation report](2026-10-21_douyin-40809900-adaptation-report.md) | `Get-FileHash -Algorithm SHA256 -LiteralPath '抖音-40.8.0_40809900_20261019 (400801).apk'`; SHA-256 `222B0A37D505060902980D5C3B9F89FCFD49232AE03A40D563C65B292F64D53F` |
| E-002 | 40.8 `Aweme.smali`: product type 112 at `isProductCard`, shopping type 9001 at `isShoppingCardAweme`, hotspot type 104 at `isHotSpotRankCard`, nearby-ad struct at `isAdNearbyCard`, and type-140 Life SPU scene at `isLifeSpuCard` | `work/readapt-40809900-20261021/semantics/new-original/com/ss/android/ugc/aweme/feed/model/Aweme.smali`; SHA-256 `C72A9C24C3947449ADD3F6603AC829D49E9DF56830A4B2AEF04726683C22EDCB` |
| E-003 | 40.6 `Aweme.smali` also contains the same five classifier methods, establishing compatibility for both configured host families | `douyin-40.6.0/apktool-no-res/smali_classes16/com/ss/android/ugc/aweme/feed/model/Aweme.smali`; SHA-256 `4E035662DDAA1C2DCAA3EA2AE613AD098F7FEBB57C33D1E52AD9D0E790FAF003` |
| E-004 | Direct original-DEX contract test asserts the three card type predicates, nearby-ad struct, and exact Life SPU scene | `FeedFilterHostContractTest.productShoppingHotspotAndLifeSpuPredicatesMatchTheOriginalHost`; 40.8 run and 40.6 run both passed, zero skipped |
| E-005 | Local policy, response-adapter, backup, settings and Miuix regressions | `FeedFilterPolicyTest`, `FeedAdFilterHookTest`, `ConfigurationCatalogTest`, `ConfigTransferTest`, `ModuleSettingsReferenceTest`, `ComponentHideSettingsPageTest`, `MiuixHostSettingsTest` |
| E-006 | Complete signed-release verification, including the exact 40.8 host, full JUnit, Release Lint, signature, alignment and artifact sealing | `artifacts/release-verification/20261022T170629Z-8912dc2f/manifest.json`; manifest SHA-256 `7407745648BCE2DB8EA8B1F5D18E2B230D76030996A492900A820F2CEDFE6359` |

## Findings

| ID | Severity | Status | Confidence | Finding |
| --- | --- | --- | --- | --- |
| F-001 | n/a_re | validated | high | Types 112 and 9001 are host-classified product/shopping cards; type 104 is a hotspot rank card. The module now offers separate opt-in switches rather than folding these into existing recommendation-card behavior. |
| F-002 | n/a_re | validated | high | Nearby ad cards have a dedicated host predicate and structure. The module now includes them in the existing ad filter. |
| F-003 | n/a_re | validated | high | Type 140 alone is too broad for the merchant toggle: the host's `isLifeSpuCard()` also requires POI card data and scene `FeedConcern_LifeGoodsSpu`. The module now uses that predicate, preserving other type-140 content such as audio D-cards. |
| F-004 | n/a_re | validated | high | New switches default off and are included in settings, backup transfer, configuration dependency tracking and runtime filter gating. |
| F-005 | n/a_re | validated | high | Full local release verification passed 2,222 tests across 212 suites, with zero failures/errors/skips; Lint had zero errors and 56 existing warnings within budget. Device and live-service validation were not run. |

## Path

### P-001: Host classification to filtered Feed response

- `path_type`: callflow
- `start`: original host `Aweme` instances in the main Feed response
- `goal`: remove only enabled card/ad categories from the host response list
- Steps:
  1. The exact 40.6/40.8 `Aweme` predicates establish card types and Life SPU classification (E-002, E-003).
  2. `FeedAdFilterHook.shouldHide` combines host markers and type values with saved `LocalOptions` (E-005).
  3. `FeedFilterPolicy` applies independent product/shopping and hotspot switches; existing main-feed filtering and empty-page protection remain in the response adapter (E-005).
  4. Original-APK contract tests and strict local release verification pass (E-004, E-006).
- Residual risks: no phone/LSPosed run confirms the host response reaches this hook for all Feed variants; host classification does not establish how frequently each card occurs.

## Timeline

| Date | Event |
| --- | --- |
| 2026-10-22 | Static review identified missing product/shopping and hotspot filters and the broad type-140 merchant predicate. |
| 2026-10-22 | Added independent filters, narrowed Life SPU handling, added settings/backup/runtime and original DEX contracts. |
| 2026-10-23 | Strict local verification sealed 0.2.15 / 116 as `verified-local`, `published=false`; device validation remained not run. |

## Candidate artifact

Local APK: [`DouAi-0.2.15-release.apk`](../artifacts/DouAi-0.2.15-release.apk), 4,853,040 bytes, SHA-256 `8AA8CBE6DF64EA4386402CA51C61713051C96710254AF63EF162F25E4275AB62`. It uses the existing release certificate with v3 signing. It was not published.
