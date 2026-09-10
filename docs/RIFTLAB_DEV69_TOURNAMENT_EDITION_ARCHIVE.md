# RiftLab dev.69 — Tournament Edition / 年度赛事档案

## 目标

`dev.68` 建立了统一赛事数据图；`dev.69` 把其中的 Tournament 从“当前赛程上下文”升级为可以长期存在、持续补全、不会被下一赛季覆盖的 **Tournament Edition**。

RiftLab 对“年度赛事档案”的定义不是一张静态介绍卡，而是一个稳定实体：只要 Riot Tournament Directory 曾经返回过该届次，RiftLab 就可以为它保留 identity，并在后续 Schedule / Standings / Governance / Research 数据到达时继续补全。

## 核心约束

1. **旧届次不被新届次覆盖。** 在线同步采用 append/merge，而不是把本地档案替换成“本次 API 返回列表”。
2. **Tournament ID / editionKey 是实体身份，不用页面标题充当主键。** 同一赛事不同年份、不同赛段必须能并存。
3. **缺字段就保持缺失。** Patch、资格来源、最终名次、MVP/FMVP/POG 没有可信来源时显示 `待同步`，禁止按日期、KDA、伤害或参赛名单猜。
4. **来源等级不混淆。** Riot/Cito Provider、官方规则快照、结构推导和本地缓存各自保留 provenance；本地持久化只证明“RiftLab 曾见过/保存过”，不把缓存升级成官方事实。
5. **历史档案与当前比赛解耦。** 当前比赛可以继续走 PRE/LIVE/POST，年度实体负责承载跨时间的赛事结构。

## 本地档案

文件：应用私有目录 `tournament_editions_v1.json`

Schema v1 每届至少保存：

- `tournamentId`
- `slug`
- `leagueId / leagueSlug / leagueName`
- `family`
- `seasonYear`
- `editionKey`
- `displayName`
- `stage`
- `startDate / endDate`
- `participantTeamCodes`
- `scheduleSeriesCount`
- `firstSeenEpochMs / lastSeenEpochMs`

写入采用临时文件后替换；解析失败时放弃损坏缓存并等待在线重建，不拿损坏 JSON 继续运行。

## Edition 固定槽位

每个 Tournament Edition 都有独立槽位：

| 槽位 | dev.69 行为 |
| --- | --- |
| 届次 / 年份 | Riot Tournament identity + `TournamentIdentityResolver` |
| 比赛版本 | 仅接可信明确 Patch；没有就待同步 |
| 参赛队 | Schedule + Standings 中已经出现的真实队伍 |
| 资格来源 | 预留；dev.70 接 Championship Points / qualification graph |
| 规则 / 赛制 | 复用 `TournamentGovernanceProvider`，官方与结构推导分开 |
| 分组 / 签位 | Riot Bracket / 已核实官方签位；不按排名猜对阵 |
| Schedule | 当前可获取的该届 Series |
| Standings / Bracket | Riot Standings 当前已拿到的阶段/签位结构 |
| 最终名次 | 只有可信来源才可定案；Bracket 推出的结果只能标“候选” |
| MVP / FMVP / POG | 只接已核实奖项；不按 KDA/伤害自动推断 |
| 来源 / 更新时间 | Provider / Official / Derived / Local Cache 明确分层 |

槽位状态统一为：`完整 / 部分 / 待同步 / 来源异常`。

## UI

PRE → `COMPREHENSIVE DATA / 全面数据` 下增加 `TOURNAMENT EDITIONS / 年度赛事档案`。

第一版提供：

- 当前已归档 Tournament Edition 数量；
- 最近/同赛事 family/同赛区的届次入口；
- 当前 edition 日期窗口、Series 数、已识别参赛队数量、Patch 状态；
- 各固定槽位完成度及缺口。

它的目的不是堆文字，而是让用户明确看到“这一届赛事已经存了什么、还缺什么”。

## 与 dev.70 的边界

`dev.69` 只建立可长期存在的 Tournament Edition 容器与真实槽位，不提前伪造资格逻辑。

`dev.70` 再把以下内容正式接入：

- Championship Points；
- Worlds / MSI / First Stand 等晋级路径；
- 区域资格赛 / 冒泡赛 / 骑士之路等阶段节点；
- `已锁定 / 仍可争夺 / 已淘汰` 状态；
- 官方确认结果与 RiftLab 推演结果的硬隔离。

## OTA

本版本不改 OTA 架构：GitHub `dev-latest` 仍是 APK / `latest.json` 唯一 canonical Release；客户端只对 GitHub Release 请求使用现有直连 + GitHub-only 自适应加速、Range 续传和完整性校验。

**Gitee OTA 已废弃，不得重新作为 RiftLab 更新依赖引入。** Gitee 即使保留，也只允许作为源码镜像，不参与 APK、manifest、fallback 或发布链路。
