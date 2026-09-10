# RiftLab dev.68 — 全面数据验收矩阵

本矩阵不是“接口存在即通过”。只有真实数据能进入统一关系图，且来源/刷新级别明确时，才算对应域已接入。

| 数据域 | dev.68 基线 | 完整目标 | 当前行为 |
| --- | --- | --- | --- |
| Tournament | 已接入 | family / edition / year / stage / stable id | 从 Riot tournament ref 归一；无法识别的 family 保留赛区 identity |
| Schedule | 已接入 | Series、BO、两队、时间、状态 | 直接接现有 Schedule Store |
| Team | 已接入 | stable id、名称、缩写、slug、Logo、赛区 | 当前比赛双方进入 Graph；空 ID 使用明确的 local-derived id |
| Player | 部分 | stable player id、ID/姓名、位置、当前与历史队伍 | 当前先从真实 roster 建立关系，不伪造姓名/履历 |
| Roster | 已接入 | 双方阵容、位置、首发状态、有效期 | Riot roster 进入 Graph；未确认首发标 STARTER_CANDIDATE |
| PRE | 已接入骨架 | 首发、近期状态、交手、Rank、版本/BP趋势 | 现有真实 PRE 接入；缺口由 Coverage 暴露 |
| LIVE | 已接入 | Game 状态、经济、KDA、资源、选手帧、事件 | 只在 LiveSourcePhase.LIVE 时计入，不把 event active 当 game live |
| POST | 部分 | Game + Series、KDA、伤害、承伤、视野、经济、目标、BP、MVP | 当前 completed snapshot 可进入 Graph；高级统计仍显示缺口 |
| Standings | 已接入 | stage/section/team/record/league points | 直接接 Riot Standings；不冒充 Championship Points |
| Qualification | 待补 | Championship Points、晋级状态、资格路径与规则来源 | 槽位已建立，当前无可靠事实就保持待同步 |
| History | 部分 | 跨赛季 Tournament/Series/Game/Team/Player 关系 | 当前已接本地 completed archive；全历史目录后续持续补齐 |
| Provenance | 已接入 | authority、freshness、source、observed/update time | dev.68 建立统一来源与刷新等级 |

## 硬规则

- `赛事开始` 与 `GAME_LIVE` 永远分开；
- `leaguePoints` 与 `championshipPoints` 永远分开；
- 未确认首发不能写成官方首发；
- 推导值必须保持 `DERIVED` 身份，不能升级成 `OFFICIAL`；
- 来源失败时保留最后已知事实并显示 `SOURCE_ERROR`，不能生成假值填空；
- Coverage 只表达结构完成度，不表达胜率、可信度或模型置信度。

## dev.68 通过条件

1. 项目可编译并正常启动；
2. 现有赛程/PRE/LIVE/赛后/积分链不被破坏；
3. 当前目标比赛可以归一成稳定 Series + Team 关系；
4. 真实 LIVE 出现时能进入 Game/PlayerStats；
5. Standings 能与 Tournament identity 连接；
6. 缺失 Qualification、History、POST 深层字段时，UI/Graph 明确标为部分或待同步；
7. 不新增 Mock fallback，不改变 OTA 传输架构。
