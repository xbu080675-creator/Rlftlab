# RiftLab dev.67 ～ dev.86 开发路线

> 新基线：`1.0.0-dev.67`
>
> 说明：旧 `RIFTLAB_DEV_59_78.md` 是 dev.58 时制定的计划。实际开发中 dev.59～dev.66 被 OTA、全球观赛入口与 RiftScreen Draft HUD 等工作占用，因此从这里重新排号，不再让“计划版本号”和“真实已发布版本号”冲突。
>
> 主线不变：先补完整赛事数据与档案，再把已有真实比赛、Timeline、BP、规则、积分、队伍和人员模型汇入 Sandbox。

---

# 第一阶段：清账与赛事内容完整度

## dev.67 — Launcher 图标二次更新 / OTA 实机验证

实际交付：这一版刻意保持最小变量，只更新 APK Launcher / Round Launcher 图标并重新打包分发，用于验证 dev.66 → dev.67 的 APP 内 OTA。

- 不改赛事数据、PRE / LIVE / POST、RiftScreen 或 OTA 传输逻辑；
- 中国移动 5G、手机重启并还原网络设置、VPN/系统代理关闭条件下完成实机 OTA；
- 实测下载约 20 MB/s，检查、下载、校验、覆盖安装闭环通过；
- Gitee OTA 继续保持废弃。

该版不再承担原路线中的“治理清账”任务，相关工作并入后续数据治理主线。

## dev.68 — 全面数据基线：统一图 + Provenance + Coverage

目标：从这一版开始，把 RiftLab 从“多个页面各自拿数据”升级成长期可扩展的电竞数据图。全面不等于强行填满字段，而是任意实体都能继续沿关系查询，缺口也必须可见。

第一阶段落地：

- 统一 `Tournament Edition → Series → Game → Team → Player` 核心关系；
- 纳入 Roster、Player Game Stats、Standings、Qualification、Timeline 槽位；
- 每条数据保留 authority / freshness / source provenance；
- 统一 12 个覆盖域：赛事、赛程、队伍、选手、阵容、PRE、LIVE、POST、排名、晋级、历史、来源；
- 覆盖状态固定为 `完整 / 部分 / 待同步 / 来源异常 / 不适用`；
- `ComprehensiveDataCenter` 直接监听现有真实 Store，不重复抓同一数据；
- PRE 页面增加真实 Coverage 面板，明确暴露缺失字段；
- LPL、LCK、LCP、LEC、LTA 与 Worlds / MSI / First Stand / EWC 等 family 使用稳定 edition identity 规则；
- 不因当前没有 LIVE 比赛就丢掉 Tournament / Series / Standings 档案关系；
- 不用 Mock、推测、静态占位文本把 Coverage 顶成“完整”。

验收：当前运行中的比赛数据已经可以进入统一 Graph，并能回答“当前拿到了哪些域、还缺哪些域、来源是什么”；后续全球历史目录、资格路径与完整赛后数据在这个统一模型上继续补齐。

## dev.69 — Tournament Edition / 年度赛事实体

目标：每一届比赛成为长期存在的独立实体，而不是临时 Schedule 的一组 event。

每届固定槽位：

- 正式名称 / 年份；
- Patch / 比赛版本；
- 参赛队；
- 资格来源；
- 规则 / 赛制；
- 分组 / 抽签 / 签位；
- Schedule；
- Standings / Bracket；
- 最终名次；
- MVP / FMVP / POG 等已核实奖项；
- 数据来源 / 更新时间 / 完整度。

旧届次绝不被新一届覆盖。

## dev.70 — 资格、年度积分与晋级路径中心

目标：用户不只看到“现在第几”，还能看到“怎样进入下一阶段”。

- Championship Points 与当前联赛排名彻底独立；
- Worlds / MSI / First Stand 等资格路径可视化；
- 区域资格赛、冒泡赛、骑士之路等变成明确阶段节点；
- `已锁定 / 仍可争夺 / 已淘汰` 三态；
- 官方已确认结果与 RiftLab 推演结果必须视觉区分；
- 允许从某支队伍反向查看“还剩哪些晋级路径”。

## dev.71 — PRE / 赛前信息完整化

实际交付同时承担 dev.69/dev.70 实机后暴露的数据全局查漏：PRE 首发/名单池/Staff/近期战绩链路补齐；Tournament Edition 历史、Patch、Final 结果源增强；2026 Riot League Handbook 覆盖主要赛区与国际赛；Qualification Center 扩展 LCK/LCP/LEC/LCS/CBLOL 机制，其中 LCP 保存官方 Championship Points 公式但不在数据不完整时硬算当前总分；海外 Staff/Awards 增加 APK 种子兜底。仍拿不到的 Rank、伤病、转会、完整 Awards 等继续明确标为未知/待同步。

目标：赛前页面不只是一张对阵卡。

- 首发 / 替补 / 教练组；
- 最近正式比赛阵容；
- Rank 与近期英雄池（来源可用时）；
- 最近 5～10 场状态；
- 转会、首发变化、伤病/缺席只收录公开确认信息；
- 近期交手；
- 红蓝方历史记录；
- BO3 / BO5 当前小局的选边；
- “赛事节目已开始但小局未开始”继续独立于 GAME_LIVE。

## dev.72 — LIVE 统一事件模型

目标：所有实时 Provider 最终输出同一种事件，而不是 UI 分别理解各家字段。

统一事件至少包含：

- Kill / MultiKill / TeamFightWindow；
- Tower；
- Dragon / Soul / Elder；
- Herald / Atakhan / 当前版本地图资源；
- Baron；
- GoldLeadChange；
- ItemSpike；
- Level / CS / KDA change；
- Game pause / resume（官方源可确认时）。

无法确定击杀者、受害者、顺序或归属时不猜。

## dev.73 — POST 完整 Game / Series 数据

目标：赛后不再只剩终局比分。

每个 Game：

- 双方阵容、英雄、召唤师技能；
- K/D/A、CS、经济、伤害等可用统计；
- 终局装备；
- 龙 / 塔 / 男爵等资源；
- BP；
- MVP / POG；
- Timeline；
- VOD。

每个 Series：

- 总比分；
- G1/G2/... 入口；
- 首发与换人变化；
- 每局选边；
- 系列赛关键节点。

## dev.74 — VOD ↔ Timeline 时间对齐

目标：点击时间轴事件就能去录像附近，拖录像也能反向定位事件。

- Timeline gameTime 与 VOD playback position 建 offset；
- 击杀、龙、团战等事件可跳转录像；
- 播放进度反向定位 Timeline；
- 片头、广告、暂停导致的偏移允许人工校准；
- 校准值只属于该 VOD，不污染比赛事实数据；
- 同一比赛不同平台 VOD 可拥有独立 offset。

## dev.75 — 内容完整度面板

目标：让 RiftLab 自己知道“缺什么”，而不是页面空了才发现。

每届赛事至少计算：

- Schedule
- Standings
- Rosters
- Rules
- Draw
- Patch
- Match Stats
- Awards
- Replay
- Timeline

状态建议：`完整 / 部分 / 待同步 / 来源异常 / 不适用`。

同步器可以优先补最缺的数据，UI 不再用占位内容假装完整。

---

# 第二阶段：RiftLab Sandbox / 赛事沙盘

Sandbox 不负责“随机模拟谁赢”。它是把真实赛事状态复制出来，让用户修改一个条件、建立分支、比较代价和可行性的复盘工具。

所有 Sandbox 数据必须和事实档案硬隔离，永远不能反写覆盖真实比赛。

## dev.76 — Sandbox Core

核心对象：

- `SandboxSession`
- `SandboxTeam`
- `SandboxPlayer`
- `SandboxDraft`
- `SandboxMapState`
- `SandboxObjectiveState`
- `SandboxTimeline`
- `SandboxBranch`

支持：

- 空白创建；
- 从真实比赛创建；
- 从真实 Timeline 某个时间点复制状态；
- 原始事实快照只读。

## dev.77 — 召唤师峡谷战术地图

第一版做电竞分析所需的区域级地图，不追求游戏客户端像素级坐标。

- 红蓝双方五个位置节点；
- 三路、河道、上下野区；
- 龙坑 / 男爵坑 / 当前版本关键资源区；
- 拖动选手位置；
- 标记推进、回城、包夹、绕后、视野区域；
- 时间轴拖动；
- 地图 UI 状态与事实数据分离。

## dev.78 — BP / Draft Sandbox

- 蓝红方 Ban/Pick；
- BO3 / BO5 小局 BP；
- ChampionCatalog 接入；
- 选手英雄池提示；
- 已使用英雄 / 全局 BP 规则可配置；
- 从真实 BP 一键复制；
- 修改一个 Pick/Ban 立即生成 Sandbox 分支。

## dev.79 — 阵容与对位 Sandbox

- 真实 roster 载入；
- 首发 / 替补切换；
- TOP/JUG/MID/BOT/SUP 调整；
- 换线 / 英雄换位；
- 对位关系；
- 控制、开团、前排、持续输出、Poke、边线等阵容标签；
- 每个标签标记来源：统计 / 人工规则 / 用户手填。

## dev.80 — 兵线、塔与资源状态

- 三路兵线：推进 / 回推 / 冻结 / 中线；
- 外塔/二塔/高地塔状态；
- 小龙层数 / 龙魂；
- 男爵 / 远古龙；
- Herald / Atakhan 等版本资源；
- 双方经济 / 经济差；
- 可选记录关键召唤师技能、大招、TP 窗口。

## dev.81 — Timeline Branch / “如果当时不这么打”

- 从真实 Timeline 任意节点创建 Branch；
- 修改一个条件，例如“放龙换塔”“辅助提前游走”“上单继续带线”；
- 原始事实线永久只读；
- Sandbox 分支清晰标识；
- 支持 A/B/C 多分支并存；
- 分支变化只记录用户改变的条件与后续推演，不篡改历史事实。

## dev.82 — 路线与决策标注

支持教练式语言：

- 打野动线；
- 辅助游走；
- TP / 回城窗口；
- 资源交换；
- 交叉地图；
- 视野进入路径；
- 团战进场角度；
- 兵线代价。

重点是表达“为什么这么走 / 代价是什么”，而不是假装知道比赛内部不可见信息。

## dev.83 — Explainable Evaluation Engine v1

第一版不输出没有训练依据的伪精确胜率，例如 `63.7%`。

输出：

- 经济与资源风险；
- 人数差；
- 兵线代价；
- 地图资源收益；
- 阵容时间曲线；
- 开团 / 反开结构；
- Carry 暴露风险；
- 下一资源刷新前准备时间；
- 方案成立的必要条件。

结论用：`倾向 / 风险 / 代价 / 可行条件`。

## dev.84 — Sandbox Compare

把同一节点多个方案真正放在一起：

```text
A：接小龙团
B：放龙换上塔
C：中野入侵上半区
```

统一比较：

- 经济；
- 防御塔；
- 地图资源；
- 兵线；
- 人员位置；
- 下一步窗口；
- 主要风险；
- 对阵容时间曲线的影响。

从 Match Detail / Timeline 可直接进入 Compare。

## dev.85 — 本地沙盘档案与导出

- 保存；
- 重命名；
- 删除；
- 标签 / 搜索；
- 从真实比赛重新打开；
- 结构化 JSON 导出；
- 导出文件明确包含 `SANDBOX` schema / provenance；
- 禁止导入时覆盖事实比赛库。

## dev.86 — Sandbox MVP 收口

完成第一代长期可用的复盘工作台：

- 真实比赛 → Sandbox；
- BP；
- 地图；
- 兵线 / 资源；
- 路线标注；
- Timeline 分支；
- A/B/C Compare；
- Explainable Evaluation；
- 本地档案 / JSON 导出；
- 为后续 AI 复盘、教练协作、训练模板预留稳定接口。

---

# 第三阶段之后的方向（不预占版本号）

完成 dev.86 后再决定版本号，不提前把未来几十版写死。候选方向：

- AI 辅助复盘，但必须引用 Sandbox 中真实/用户输入状态；
- 教练/选手协作批注；
- 多人共享战术模板；
- 对局方案库；
- 更多游戏项目接入通用电竞图谱；
- 国内二进制分发从 Gitee Release 平滑迁移到可控对象存储/CDN（如果 APK 体积或用户规模需要）。

完成 dev.86 后，RiftLab 的目标产品形态应成为：

**赛事资料库 + 实时观赛数据层 + 回放/Timeline + RiftScreen + 可分支电竞分析 Sandbox。**
