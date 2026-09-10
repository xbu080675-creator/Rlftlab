# RiftLab Comprehensive Data Contract

从 dev.68 起，以下约束视为 RiftLab 数据层长期契约。

## Identity

- Tournament、Series、Game、Team、Player 必须拥有稳定 identity；
- Provider 临时显示名不是主键；
- 同一选手跨赛事、跨队伍不能因为显示 ID 变化而自动视为新实体；
- 暂时无法获得官方 ID 时可以使用明确带来源语义的本地派生 ID，但必须允许后续合并到 canonical identity。

## Fact / Derived / Unknown

数据只能处于三种语义之一：

```text
FACT       上游或官方明确给出的事实
DERIVED    可以从事实结构化推导的值
UNKNOWN    当前没有足够可靠证据
```

`UNKNOWN` 不能被 UI 默认值、历史残留值或模型推测变成 `FACT`。

## Time

每条动态数据至少要区分：

- `observedAt`：RiftLab 何时拿到；
- `sourceUpdatedAt`：上游声明何时更新（若上游提供）；
- freshness class：STATIC / DAILY / HOURLY / MINUTES / REALTIME。

不同刷新等级不得使用同一个全局刷新周期。

## Match state

```text
EVENT_SCHEDULED
EVENT_LIVE
BETWEEN_GAMES
GAME_LIVE
GAME_COMPLETED
SERIES_COMPLETED
```

其中 `EVENT_LIVE != GAME_LIVE`。赛事开场、选手入场、解说暖场、局间阶段不能伪造成小局实时帧。

## Scoring

- 联赛/小组排名的 `leaguePoints` 与年度资格体系的 `championshipPoints` 是两个独立字段；
- Provider 只返回胜场时，不推测 Championship Points；
- 晋级资格必须带规则/来源，推导路径需要明确标记为 DERIVED。

## Coverage

Coverage 的含义是“这个数据域的必要结构目前拿到了多少”，不是模型评分。

一个页面看起来有文字不等于数据完整；只有关键字段具备真实来源并进入统一 Graph，才能提升 Coverage。

## Failure behavior

数据源故障时：

1. 如果有经过验证的最后已知事实，可继续显示并标注时间/来源；
2. 没有事实就显示缺失或来源异常；
3. 不用 Mock 自动补位；
4. 不因为一个 Provider 故障让其他已经拿到的数据一起消失。
