# RiftLab dev.71 — 全局赛事数据查漏补缺

## 2026-09-10 · Qualification semantics repair

- Added an explicit qualification-mechanism layer: Championship Points, direct placement, regional qualifier, participant origin, mixed and unknown are no longer collapsed into one concept.
- International Tournament Editions now expose observed participants as provider-backed participation facts while keeping Region / Seed / Qualification Origin pending until a trusted mapping exists.
- Non-points systems no longer render an empty Championship Points card. Unknown regional rules are labelled as unknown rather than as missing points.
- 2026 LPL remains a mixed official model because its current path combines direct champion qualification, annual Championship Points and the regional qualifier.
- Archive/UI wording was made mechanism-neutral so the same surface can correctly represent LPL, LCK, LCP and international target events without inventing rules.

> 目标：不是只修 UI 中出现“待同步”的卡片，而是从整个 APP、整个赛事数据链路反查所有缺口。
>
> 原则：无可信来源时明确标记未知；不拿 LPL 数据替代其他赛区，不用结构推导冒充官方规则，不为了填满页面编数据。

## 1. dev.70 实机首批暴露问题

当前实机观察已经确认，下面这些只是首批样本，不代表完整缺口清单：

| 域 | 当前问题 | 初判类型 | dev.71 目标 |
| --- | --- | --- | --- |
| Tournament Patch | 多个赛事显示“比赛版本待同步” | 数据源/映射缺口 | 建立赛事 Patch 的可靠 Provider + 历史保存 |
| Participants | 2026 Worlds 等赛事存在“等待参赛战队数据” | 数据源/实体映射缺口 | 统一参赛队来源并关联 Team identity |
| Qualification source | 目前多为“部分”，主要依赖 LPL 2026 特例 | Provider 覆盖不足 | 扩展到各主要赛区与国际赛事 |
| Championship Points | LCK 等赛区未接入，Worlds 页面只能显示未接入提示 | 数据源缺口 | 分赛区接年度积分，不拿 LPL 数据替代 |
| Rulebook | LCK 等赛事规则主要是 Riot Schedule/Standings 结构推导 | 官方规则源缺口 | 官方规则优先；推导内容降级为 DERIVED |
| Final placements | 部分名次仍从 Bracket 结构推导 | 结果源缺口 | 接可信最终名次/冠军/亚军/季军等结果源 |
| MVP / FMVP / POG | 多数赛事显示待同步 | 奖项源缺口 | 独立 Awards Provider；禁止按 KDA/伤害自动猜 |
| Historical event archive | 历史赛事有赛程/排名，但 Patch、积分、战队、规则等覆盖不对称 | 全链路覆盖不一致 | 每届 Tournament Edition 独立保存完整槽位 |
| Cross-region consistency | LPL 数据明显多于 LCK/Worlds 等赛区 | 赛区特例过重 | 所有赛区进入统一 Provider/Graph 流程 |

## 2. 审计范围

本次 dev.71 必须覆盖整个赛事数据域，而不是只覆盖当前截图：

- Tournament / Tournament Edition
- Season / Stage / Date / Patch
- Format / Rules / Rulebook
- Participants / Team identity / Roster
- Schedule / Series / Game / BO length
- Live state / game-start state
- Standings / ranking / league points
- Championship Points
- Qualification routes / locked-contending-eliminated state
- Bracket / draw / seed / slot
- Final placement / champion / runner-up
- MVP / FMVP / POG / official awards
- Draft / BP
- Per-game stats / per-series stats
- Timeline / objectives / economy / towers / dragons / Baron / Elder
- VOD / replay / timeline alignment
- Broadcast / official stream entry
- Historical team/player relationships
- Provenance / freshness / source timestamp / stale status

## 3. 数据链路审计方法

对每一个字段/页面同时检查下面 6 层：

```text
Source exists?
→ Provider fetches it?
→ Model has a field?
→ Identity maps correctly?
→ Store persists it?
→ UI actually renders it?
```

任何一层断掉都记为缺口，不能只看 Provider 是否“已经写过”。

## 4. 缺口分类

- SOURCE_MISSING：没有可信源
- FETCH_MISSING：有源但 Provider 没抓
- MODEL_MISSING：源有数据但模型没有字段
- IDENTITY_MISMATCH：Tournament / Team / Player / Series identity 对不上
- STORE_MISSING：抓到了但没有归档/持久化
- UI_MISSING：数据存在但页面没显示
- REGION_SPECIAL_CASE：只给某个赛区写了特例
- STALE_OR_EMPTY：源偶发空、过期、国内网络失败
- DERIVED_ONLY：只有结构推导，没有官方确认

## 5. dev.71 验收标准

dev.71 不要求全世界所有年份一次性达到 100%，但必须做到：

1. 每个受支持 Tournament Edition 都能明确显示 Coverage，而不是空白。
2. LPL/LCK/LEC/LCS/LCP/PCS/VCS/LJL 与国际赛事都走统一数据模型，不允许 LPL 特例污染其他赛区。
3. 能拿到的赛事数据必须真正接进 Provider → Graph → Store → UI 全链路。
4. 暂时拿不到的数据必须显示缺失原因和来源状态。
5. 官方数据、第三方 Provider、RiftLab 推导严格分层。
6. 历史届次不能因为当前赛季刷新而被覆盖或降级。
7. 先完成数据底座修复，再在其上建设“算分”模拟器。

## 6. “算分”功能关系

算分功能已经确定要做，但不在数据仍大量缺失时强行完成。它最终依赖：

```text
真实赛制规则
+ 当前 Championship Points
+ 剩余赛程
+ 各名次对应积分
+ 资格名额/种子规则
→ 输入假设赛果
→ 重算积分
→ 重算资格状态
→ 展示世界赛/资格赛路径变化
```

所以 dev.71 先补全这些底层事实数据；算分引擎在数据可信后接入。

## 2026-09-10 · PRE context tranche

- PRE now separates **confirmed/uniquely resolvable starting five** from the broader roster pool; a multi-player same-role roster is never silently treated as a starting lineup.
- Team staff/management supplied by the existing team provider chain is surfaced with its own source fields.
- Recent completed Series and recent H2H are derived only from the verified-completed portion of the current Unified Schedule history window, with an explicit warning that this is not the full historical database.
- Rank, injuries/absence, transfers and lineup-change claims remain empty until an independent trustworthy source is connected.

## 2026-09-10 · Riot League Handbook tranche

- Added a verified 2026 Riot League Handbook governance source for LPL, LCK, LCP, LEC, LCS and CBLOL Split 3 plus First Stand / MSI / Worlds.
- Tournament rules now prefer an official Handbook snapshot over Riot Schedule/Standings structural inference when the edition identity matches.
- 2026 Worlds can recover already-confirmed qualifying participant codes from Riot's official Handbook even before the event appears in the normal schedule window.
- Qualification coverage is now `PARTIAL` when the Handbook explicitly establishes the mechanism; team seed/origin remains unknown unless Riot explicitly states it.
- LEC's Handbook currently contains wording that does not fully explain the third Worlds place. RiftLab preserves that ambiguity instead of inventing the missing mechanism.
