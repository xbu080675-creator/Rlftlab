# RiftLab dev.68 — 全面数据基线

## 1. “全面”不是字段越多越好

RiftLab 对“全面数据”的定义是：用户从一个赛事、一个 Series、一个 Game、一个队伍或一个选手出发，沿实体关系继续浏览时不会突然断档；同时任何拿不到的数据都明确标成缺失，而不是用 Mock、推测或占位内容伪装成完整。

核心连续链：

```text
Tournament Edition
→ Series
→ Game
→ Team
→ Player / Roster
→ Draft / Stats / Objective / Timeline
→ Post / VOD / Awards
→ Standings / Qualification / History
```

## 2. dev.68 第一阶段落地

本版本先把散落在现有 Provider / Store 中的数据接到统一图模型：

- `TournamentEditionIdentity`：赛事 family、赛区、年份、届次 key、阶段；
- `ComprehensiveSeriesRecord`：Series / event / BO / 对阵 / 状态；
- `ComprehensiveGameRecord`：小局状态、时间、经济、击杀、塔、龙、男爵；
- `ComprehensiveTeamRecord` / `ComprehensivePlayerRecord` / `ComprehensiveRosterMembership`；
- `ComprehensivePlayerGameStats`；
- `ComprehensiveStandingRecord`；
- `ComprehensiveQualificationPath`；
- `ComprehensiveTimelineEvent`；
- `DataProvenance`：来源等级、刷新级别、观察时间；
- `ComprehensiveCoverageReport`：每个数据域的完整 / 部分 / 待同步 / 来源异常 / 不适用。

现有 Riot / Cito / OP.GG / Bilibili / 本地归档仍是事实来源。统一层不重新抓一遍相同数据，也不覆盖原 Provider；它只负责 identity、关系、provenance 与 coverage。

## 3. 数据域

当前统一覆盖矩阵固定为 12 个域：

1. 赛事 Tournament
2. 赛程 Schedule
3. 队伍 Team
4. 选手 Player
5. 阵容 Roster
6. 赛前 PRE
7. 赛中 LIVE
8. 赛后 POST
9. 排名 Standings
10. 晋级 Qualification
11. 历史 History
12. 来源 Provenance

覆盖率只表示结构化数据槽位的完成程度，**不是数据可信度分数，更不是胜率**。

## 4. Provenance 规则

统一来源等级：

```text
OFFICIAL      官方确认
PROVIDER      Provider 返回
DERIVED       结构推导
LOCAL_CACHE   本地归档 / 缓存
APK_SEED      APK 内置种子
USER_INPUT    用户输入
```

刷新级别：

```text
STATIC    历史 / 固定档案
DAILY     日级
HOURLY    小时级
MINUTES   分钟级
REALTIME  秒级 / 实时
```

不同等级不能混为一谈。尤其是 `DERIVED` 不能在 UI 中显示成“官方确认”。

## 5. 当前运行方式

`ComprehensiveDataCenter` 在 APP 启动后监听现有：

- `MatchSessionStore.targetMatch`
- `MatchSessionStore.preMatchFlow`
- `MatchSessionStore.live`
- `CompletedGameArchive.latest`
- `StandingsCenterStore.state`

然后持续生成一个统一 `ComprehensiveDataSnapshot`。这意味着旧功能不需要停工重写，后续各 Provider 可以逐个迁入统一数据图。

赛前页增加 `COMPREHENSIVE DATA / 全面数据` 面板，直接展示当前真实数据覆盖状态与下一批缺口。缺数据就是“待同步/部分”，不再因为页面有占位文字就算已完成。

## 6. dev.68 后续继续补的重点

本版建立的是“全面数据骨架 + 实时覆盖检查”，不是宣称所有历史数据已经全部抓齐。接下来在同一主线继续补：全球赛事 identity / Edition、稳定历史目录、资格路径、完整 Game/Series POST、Timeline、VOD 关联，以及跨赛季队伍/选手履历。

验收标准保持一句话：

> 任意一场受支持职业比赛，都应该能沿 `赛事 → Series → Game → 队伍 → 选手 → BP/统计/时间轴` 继续走，并且任何缺口都能回答“缺什么、从哪里补、上次什么时候拿到”。
