# RiftLab dev.1 ～ dev.30 完整开发档案

> 归档范围：`1.0.0-dev.1` ～ `1.0.0-dev.30`  
> 仓库：`xbu080675-creator/Rlftlab`  
> 整理日期：2026-09-09  
> 后续架构章节从 dev.31 开始，不计入本文 30 个版本。

这份文档与 `RIFTLAB_DEVELOPMENT_HISTORY.md` 的定位不同。后者偏产品与工程回忆录；本文是 **dev.1 到 dev.30 的逐版本档案**，尽可能把每个开发版本解决的问题、实现方向、结果以及能够确认的 Git 提交对应起来。

## 资料可信度说明

RiftLab 的早期开发速度很快，dev.1～dev.20 并不是每一个版本都保留了独立、完整的 changelog。本文采用以下规则：

- 能通过版本号提交、版本文件或明确 changelog 确认的，标记为 **已确认**；
- 只能通过版本 bump 前后的连续提交还原的，标记为 **提交链重建**；
- 不为了让版本表看起来完整而编造不存在的功能或发布时间；
- dev.21～dev.30 的问题链、版本目标和发布记录较完整，按实际开发决策整理。

---

# 版本总览

| 版本 | 核心主题 | 记录状态 |
|---|---|---|
| dev.1 | Android 工程与 RiftLab Overlay MVP | 提交链重建 |
| dev.2 | PRE/LIVE/POST 生命周期与真实数据基础 | 提交链重建 |
| dev.3 | 实时数据测试与真实 Feed 到达判定 | 已确认 |
| dev.4 | Riot 数据源接入 | 已确认 |
| dev.5 | Schedule Center / 赛程中心 | 已确认 |
| dev.6 | Standings Center / 排名中心 | 已确认 |
| dev.7 | 全 LPL 通用实时比赛引擎 | 已确认 |
| dev.8 | Match Detail 解耦 + App 内更新器 | 已确认 |
| dev.9 | TJStats/实时源探索 + OTA 文案完善 | 提交链重建 |
| dev.10 | 赛事中心、战队页和比赛详情扩展 | 提交链重建 |
| dev.11 | Event Center 成为一级赛事 Hub | 提交链重建 |
| dev.12 | 图片加载与视觉资源容错 | 已确认 |
| dev.13 | 战队 Logo / Team Identity 稳定化 | 提交链重建 |
| dev.14 | BP 与 Match Detail 电竞视觉卡片化 | 已确认 |
| dev.15 | 阵营选择、Logo 等数据可信度硬化 | 提交链重建 |
| dev.16 | 战队/选手视觉资源与 fallback 稳定化 | 提交链重建 |
| dev.17 | 全局战队对阵视觉统一 | 已确认 |
| dev.18 | 战队 staff 数据源探索 | 提交链重建 |
| dev.19 | 运行时 Wiki 方案收敛前的人员数据扩展 | 提交链重建 |
| dev.20 | 首发、替补、教练组完整 Team Page | 提交链重建 |
| dev.21 | 移除不稳定运行时 Wiki 依赖 | 已确认 |
| dev.22 | 赛事开始 ≠ 小局 LIVE | 已确认 |
| dev.23 | 排名积分 ≠ 年度 Championship Points | 已确认 |
| dev.24 | OTA 更新弹窗/发布说明修复 | 已确认 |
| dev.25 | iG 治理与负责人语义纠正 | 已确认 |
| dev.26 | Dynamic Team Data | 已确认 |
| dev.27 | Operator-first + RIFT LEGACY + BLG 管理修正 | 已确认 |
| dev.28 | People 人物实体、头像和多段任职履历 | 已确认 |
| dev.29 | Riot Persisted Gateway 故障容灾 | 已确认 |
| dev.30 | Team Archive / 战队完整档案 | 已确认 |

---

# dev.1 — Android 工程与 Overlay MVP

**状态：提交链重建。**

## 目标

先证明 RiftLab 作为 Android 英雄联盟观赛辅助工具的产品形态可以跑起来，而不是先做一个庞大的后台系统。

## 主要工作

- 建立 Android app module；
- 固定 Android SDK 36 环境；
- 固定兼容 Android 36 的 AndroidX 依赖；
- 建立固定开发签名，为后续 APK 覆盖安装和 OTA 铺路；
- 实现第一版 RiftLab Android Overlay MVP；
- 奠定电竞风 UI 和未来 RiftScreen / 浮窗方向。

## 关键提交

- `8f1e3d77eb60f3928c4974f270be7a0ade1dd36a` — `build: add Android app module`
- `ea1dfdb803558ccac6052a089447344f6aec54bd` — Android SDK 36 环境
- `35ea1417524315987ac202bff56bffa19331fb25` — Android 36 兼容 AndroidX
- `a23cdc8cb0f67be8f83377a033d6294e60324a56` — fixed dev signing key
- `a971addb7373474837e86eb6011761e64c763ac2` — `feat: build RiftLab Android overlay MVP`

## 结果

项目从概念进入可安装 Android 应用阶段，并建立了以后所有开发版本都依赖的固定签名基础。

---

# dev.2 — PRE / LIVE / POST 生命周期基础

**状态：提交链重建。**

## 问题

直播比赛不是一个永远覆盖的“当前 JSON”。如果上一小局结束后的状态继续留在 Live 页面，或者下一局开打后把上一局赛后数据覆盖掉，产品无法同时支持观赛和复盘。

## 主要工作

- 当前正在进行的小局与已经结束的小局分离；
- 实时 Provider 只服务 current game；
- 完成小局快照进入 post-match archive；
- 开始形成 PRE / LIVE / POST 三段式数据边界。

## 关键提交

- `249423cb31426726c668d5758fcfa4cf6c5c4c66` — isolate current LPL game from completed games
- `3159cfb0bc11edca89998111cbac55cd0c3899c0` — current-game-only provider
- `c58cd0b6e9114574fa03e2ee25f649c44e31e8d1` — separate live current game from completed post-game state
- `5a133bc9667ed0bd92ee6dbbaafb1c4c7245dd8c` — archive finished game snapshots

## 结果

RiftLab 第一次拥有明确比赛生命周期，而不是把所有比赛数据混在一个状态对象里。

---

# dev.3 — 实时数据测试与 Feed 到达判定

**状态：已确认。**

版本提交：

- `9ccd85a36f44b92a5ac3d8e22879f963aff75b46` — `build: bump live data test build to dev.3`

## 主要工作

- 把开发重点转向真实赛程与真实 Live Feed；
- 在 UI 中暴露 schedule/live diagnostics，方便实机判断哪一层数据失败；
- 不再在真实实时源到达前提前进入分析状态。

相邻提交：

- `980bc49be7b92cc4cf8b3e1888b88bb1ce03424f` — `fix: do not analyze before real live feed arrives`
- `f199266d1d2ca5b4881fca44a662472a5fa5c41a` — `ui: expose real schedule and live feed diagnostics`
- `7c439a9a783745489d6b3b5c6c17d4d4535643c2` — publish dev3 real-data artifact

## 结果

“接口有响应”与“比赛真实实时数据已经到达”开始被分成不同状态，为后来的 `GAME_LIVE` 语义埋下伏笔。

---

# dev.4 — Riot 数据源接入

**状态：已确认。**

- `417a7b5ef4b9544f384b982e5549cc9807a9dee9` — `build: bump Riot data source build to dev.4`

## 主要工作

把 Riot 官方电竞数据源正式接进 RiftLab，开始让赛程、战队身份和赛事信息摆脱临时 Mock/单一测试数据。

## 结果

Riot 数据成为后续赛程、战队、排名、比赛实体的重要上游来源。

---

# dev.5 — Schedule Center / 赛程中心

**状态：已确认。**

- `553b5b6e9556518ea6dacc726814e096eea5908a` — `build: bump schedule center build to dev.5`

## 主要工作

- 从“当前比赛”扩展到完整赛程中心；
- 赛事/日期/对阵进入独立浏览结构；
- 开始解决赛事数量多以后单纯长列表难以使用的问题。

## 结果

RiftLab 从单场比赛辅助向“赛事中心”产品迈出第一步。

---

# dev.6 — Standings Center / 排名中心

**状态：已确认。**

- `fbacea6247e7d7342b5f29b95619074572f15a92` — `build: bump standings center to dev6`

## 主要工作

- 增加赛事排名/Standings；
- 赛程之外开始展示赛事竞争关系；
- 为后续积分、年度 Championship Points 分层打基础。

## 结果

赛事中心第一次同时拥有“赛程”和“排名”两个基本维度。

---

# dev.7 — 全 LPL 通用实时比赛引擎

**状态：已确认。**

关键提交：

- `cff898dcf600d0844dcc17a33f832b832dc88e1e` — `refactor: generalize live engine for all LPL matches`
- `f8431de2...` / `1a4316b8...` — validate generic live engine dev7

## 问题

早期 Live 逻辑如果只针对某一场测试比赛或固定 target，就不可能长期维护。

## 主要工作

把实时比赛识别、provider 目标和比赛状态改造成可以适配任意 LPL 对阵的通用引擎。

## 结果

Live 层从实验性单场逻辑进化为真正赛事系统的一部分。

---

# dev.8 — Match Detail 解耦 + App 内更新器

**状态：已确认。**

- `9ea0b7dcfba7d0e1579579a3156a584faa87442b` — `feat: decouple match detail and wire in-app updater`
- 该提交版本文件明确为 `versionCode 8 / 1.0.0-dev.8`。

## 主要工作

- Match Detail 从主赛事页面解耦为独立详情能力；
- App 内“检查更新”链开始落地；
- 为后续 GitHub `dev-latest` OTA 奠定客户端协议。

## 结果

从这一版开始，RiftLab 不再只能靠用户手工找新 APK；发布系统成为产品的一部分。

---

# dev.9 — 实时源探索与 OTA 可读性

**状态：提交链重建。**

版本提交：

- `9b93f29adcc3faeb352b4991e2de27bfac74daa0` — bump `1.0.0-dev.9`

## 这一阶段可以确认的开发方向

- 对 TJStats / 国内实时数据能力进行 MVP 探测；
- 继续比较 Riot 官方数据与第三方实时数据能提供的字段；
- OTA release body 开始强调人类可读 changelog，而不是只给技术元数据。

## 结果

实时数据源逐渐从“能连上即可”转向多 Provider 思维；发布说明也开始服务真实用户更新体验。

---

# dev.10 — 赛事中心、战队页和比赛详情扩展

**状态：提交链重建。**

版本提交：

- `f56f3a420d871e7ccbad06a5085af3c42ed2c428` — bump `1.0.0-dev.10`

## 主要工作

该版本前后的连续提交包括：

- 持久化 map-side participant details；
- Riot team logo 进入 schedule match；
- Match Card 渲染真实战队 Logo；
- 从 Riot `getTeams` 增加 Team Detail Screen；
- Event Center 加入 date chips 和 team pages；
- Match Detail 展示 series summary；
- 2026 playoff bracket 证据补强；
- series wins 与 VOD links 动态化。

## 结果

赛事中心不再是单纯的日程列表，开始拥有“比赛 → 战队 → 系列赛 → 详情”的完整导航关系。

---

# dev.11 — Event Center 成为一级赛事 Hub

**状态：提交链重建。**

- `dbd3fdc1ccce3721929d3ec2ab27a1027d0cc06c` — release bump dev11

相邻提交：

- `9b94ed...` — make event center first-class tournament hub
- `8d4412...` — make event center date first
- `dd2b56...` — add back navigation

## 主要工作

- Event Center 从附属页升为一级 Tournament Hub；
- 日期优先浏览；
- 完善返回导航和跨页面路径。

## 结果

RiftLab 的页面组织开始围绕“赛事”而不是围绕“某个 API 页面”设计。

---

# dev.12 — 图片加载与资源容错

**状态：已确认。**

- `c30c1b771c7be87e88a3644b91863c18db53dfb5` — `build: bump dev.12 and add robust image loading`

## 主要工作

战队 Logo、英雄图、选手图等远程资源增加更稳健的加载和 fallback，降低图片 URL 失效造成大面积空白 UI 的概率。

## 结果

视觉资源从“有 URL 就显示”进入可降级资源层。

---

# dev.13 — 战队 Logo / Team Identity 稳定化

**状态：提交链重建。**

- `6e15647e80da0987edfc6a6444ae322dfdc9fcf6` — `release: bump dev.13`

关键修复：

- `869813d0a1a415292012d8019ab169b518c141f2` — resolve team logo in team detail state
- `307693fa033b41f57397f89ca768ae36745ce153` — render resolved team logo in team pages

## 结果

同一战队在赛程、详情和战队页之间的视觉身份开始统一，减少同队不同页面 Logo 不一致的问题。

---

# dev.14 — BP 与 Match Detail 电竞视觉卡片化

**状态：已确认。**

- `0746ecf1987f4163ea43eb6bae792714280c5b12` — release bump dev14
- `e211a49a4cb221e72273544189dfe0b952112e9f` — dev14 visual-detail changelog

关键提交：

- `52a74f3fe5bf06b9934d512bcf5bf5786f38a7fe` — expose champion icons for visual BP panels
- `d00b5f1f3bd5939e020f6c3e3f2885cf1e2a2087` — turn match detail into visual esports cards

## 主要工作

- BP 不再只显示英雄名称，开始使用英雄图标；
- 比赛详情转成视觉电竞卡片；
- 强化手机竖屏下的信息密度和电竞感。

## 结果

Match Detail 从“数据调试页”向真正产品化的电竞详情页转型。

---

# dev.15 — 数据可信度硬化

**状态：提交链重建。**

- `13cf9ed63687ef7abf174b690271f28ab500d99f` — release bump dev15
- `cd66952112b0d6420c715e1bfdd1ee5c18c65f27` — dev15 changelog

关键修复：

- `0ee062b275adb2bddded1198e4de58360f060b91` — require explicit confirmed side-selection evidence
- `7c4e829bda9451bf503a56c5bc9ad8722c219f1d` — reject invalid logo URLs and keep visible fallback

## 主要工作

这一版开始把一个很重要的原则写进实现：**没有明确证据就不要推断。**

例如蓝红方/选边信息只有存在明确确认来源才展示；无效图片 URL 不再把整个视觉组件拖成空白。

## 结果

RiftLab 数据策略从“尽可能显示”转向“能证明才显示”。

---

# dev.16 — 战队视觉资源和 fallback 稳定化

**状态：提交链重建。**

- `e9240b46ee817d1a9971acafc5e6cdc03c6a90ef` — `release: bump RiftLab to dev.16`

相邻提交包括：

- OP.GG artwork URL 一次性探测；
- 清理临时 artwork probe；
- `8a0053215b1a75aac83ad309a860e936ab7480b4` — make team image fallback selection null-safe。

## 结果

战队视觉资产链更稳健，同时临时探测代码不被长期留在生产工程里。

---

# dev.17 — 全局战队对阵视觉统一

**状态：已确认。**

- `6a6758abe1bfe135c03393b35126095c0cbe24d5` — `release: bump dev.17 global team matchup visuals`

## 目标

让赛事列表、比赛详情等页面的 team-vs-team 视觉表达统一，而不是每个页面各自实现一套。

## 结果

对阵关系开始成为可复用 UI 组件，为后来战队定制主题预留了更清晰的界面边界。

---

# dev.18 — Staff 数据源探索

**状态：提交链重建。**

- `b8b8ca7c52da49b7129d99efd4fee82f4a109897` — release bump dev18

## 主要工作

随着战队页从选手名单扩展到教练和管理层，这一阶段开始尝试 Leaguepedia/Fandom 等公开资料源，并建立临时 probe workflow 检验移动端/Actions 访问情况。

## 结果

证明了“战队页不能只有 Riot roster”，同时也很快暴露 Wiki 运行时依赖的可靠性问题。

---

# dev.19 — 人员资料扩展与 Wiki 方案收敛

**状态：提交链重建。**

- `32da61eca0a5aea6209e768209cc78701edf0e45` — release bump dev19

相邻工作：

- lightweight staff page parser；
- Leaguepedia active staff probe；
- 对 cached staff / runtime parser 两种路线进行比较。

## 结果

战队 staff 已经进入正式产品范围，但“手机直接抓 Wiki”被证明不适合作为最终生产架构。

---

# dev.20 — 首发、替补和教练组完整 Team Page

**状态：提交链重建。**

- `8b0df9f9c53ea0ca5acad6c6b86c5691424e9bf6` — release bump dev20

关键提交：

- `017ed507f50f629a93b2f82b06fe466677f4bf8b` — preserve substitutes and derive latest starters
- `55b7582034bbfc249ea1aeee8fa8c09b3e9f49bb` — resolve starters substitutes and coaching staff
- `1d8215c9d73c000a4017342f9be6bf18714c6172` — show starters substitutes and coaching staff

## 主要工作

战队页面正式区分：

- 当前首发；
- 现役替补；
- 主教练；
- 教练；
- 助理教练；
- 分析师；
- 管理人员。

## 结果

Team Page 从“Riot 5 人名单”变成真正的战队人员页面，也把数据源问题推到必须解决的程度。

---

# dev.21 — 移除不稳定运行时 Wiki 依赖

**状态：已确认。**

- `1ca706c5c4774d07071d64f099922dc39354b729` — release bump dev21

## 问题

Android 直接访问 Leaguepedia/Fandom 在真实网络环境下并不可靠；匿名 Cargo 查询也遇到限流。如果把它作为 Team Page 强依赖，资料区会随机消失。

## 关键提交

- `7012d1f33a4d613a968d38b21557be515e7e97dc` — cached staff without runtime wiki parser
- `f20d7d0407259fae3aec555a7da31c9a57f8da66` — remove runtime Liquipedia scraper
- `852da392a049bd2e7852c109dfc23478cffb9713` — avoid duplicate management and coaching records
- `c443c8398b6136a30bf60569d92e56664cbd722e` — dev21 profile source fix docs

## 决策

第三方 Wiki 可以用于数据研究和后台同步，但不能成为手机端运行时唯一真相源。

---

# dev.22 — “赛事开始”与“小局 LIVE”拆分

**状态：已确认。**

关键版本实现：`ScheduleActivityState`

- `GAME_LIVE`
- `EVENT_LIVE`
- `BETWEEN_GAMES`
- `UPCOMING`
- `COMPLETED`

## 问题

赛事直播已经开始时，可能还在评论席、选手入场、赛前短片或等待 BP。Riot Schedule 的 `inProgress` 不能被直接翻译成“游戏进行中”。

## 实现

只有实时 Provider 真正返回 `LiveSourcePhase.LIVE` 才进入 `GAME_LIVE`；局间、节目已开始但游戏未进入，使用独立状态和文案。

## 结果

确立 RiftLab 后续长期原则：**赛事开始不代表游戏进入。**

---

# dev.23 — Championship Points 独立

**状态：已确认。**

## 问题

英雄联盟里的“积分”有至少两类：

- 当前赛事/组内 standings points；
- 全年用于世界赛资格的 Championship Points。

## 实现

Event Center 扩展为：

`赛程 / 排名 / 积分 / 淘汰赛 / 战队`

新增 `LplChampionshipPoints.kt`，2026 LPL 年度积分独立展示；原排名表使用“组内积分”语义，禁止用胜场随便填充积分。

## 结果

积分系统从 UI 字段升级为真正赛事规则实体。

---

# dev.24 — OTA 更新弹窗与发布说明修复

**状态：已确认。**

## 问题

开发版本 changelog 越来越长，更新弹窗高度被撑爆，下载/安装按钮可能被挤出屏幕。

## 实现

- Release 只发布当前版本第一段 changelog；
- 最大约 520 字符；
- App changelog 区域限高并独立滚动；
- 操作按钮保持可见。

## 结果

OTA 从“技术上能更新”进化到手机上可长期使用的更新体验。

---

# dev.25 — iG 治理语义纠正

**状态：已确认。**

## 问题

早期战队资料把历史创始人、现任负责人、Owner、法人等概念混在一起。iG 是第一个把这个问题彻底暴露出来的案例。

## 修正

当前管理记录按当时核验结果包括：

- 安杰 — CHAIRMAN；
- facewind — MANAGER；
- xiaochen — LEADER；
- Kezman — SUPERVISOR。

王思聪只保留历史创始人语义，不再显示为当前负责人/Owner/法人。

## 产品决策

RiftLab 不是工商查询 App。真正需要表达的是电竞战队当前由谁运营、谁负责，而不是社会信用代码、注册资本等字段。

---

# dev.26 — Dynamic Team Data：APK 不再等于数据库

**状态：已确认。**

## 问题

管理层和教练经常变动，如果每次人员变化都必须发布 APK，数据永远追不上现实。

## 架构

```text
Remote team_profiles.json
        ↓
App cache
        ↓
Team UI
        ↓ failure
APK/Kotlin fallback
```

新增：

- `data/lpl/team_profiles.json`
- `data/lpl/source_registry.json`
- `data/lpl/team_watch_state.json`
- `DynamicTeamDataProvider.kt`
- `tools/sync_team_data.py`
- Team Data Sync（约每 3 小时）

关键提交：

- `08b8162a6c211b8dbc545e756623268dc64ef2ee` — remote team directory dataset
- `039a5f85f21f73c33dd6a11cc16127f0dfcccf4c` — remote-first dynamic provider
- `e5c09190d29e0239c9aba494b13762f6b86e8423` — team detail dynamic directory
- `908ccfc6096060fc900bfa2ee30d7c9a21d7d0a5` — keep data sources separate from official socials

## 数据治理

自动同步只允许高置信、明确官方措辞直接改 current 数据；模糊事件进入 watch state，不直接污染生产资料。

---

# dev.27 — Operator-first + RIFT LEGACY

**状态：已确认。**

## 主要变化

### 1. 运营主体成为电竞组织信息核心

删除客户端工商/法人主线，只保留电竞相关：运营主体、上层组织、负责人、赛训管理。

### 2. RIFT LEGACY / 历史荣誉

管理负责人明确离任后不再直接消失，而是进入历史记录，保留曾任职位和来源。

“RiftLab 荣誉成员”只代表 RiftLab 历史档案标签，不冒充俱乐部官方授予头衔。

### 3. BLG 袁玺 / You 修正

- 袁玺：`赛训总监 / 经理`；
- You / 尤长鑫：继续保留经理；
- YUZZ：领队。

最重要的数据原则由此固定：

> **没有明确离任证据，不得移除或归档现任管理人员。** 新出现一个经理，也不能推导另一个经理已经离开。

## 关键提交

- `84546394278ea8e3821881b76fffcd28ab3632a8` — track operators and legacy management
- `82f3a4974588217ee9d5cf425878ff16533a1704` — keep BLG You as current manager

---

# dev.28 — People 人物实体、头像与多段任职

**状态：已确认。**

## 问题

人不能被建模成 `team.management[0]`。一个人可能：

- 换多个俱乐部；
- 离开又回归；
- 同一时期拥有多个职位；
- 头像属于人物，不属于某个 Team JSON。

## 实现

新增 `data/lpl/people.json` 人物实体库，包含：

- person ID；
- 姓名/别名；
- avatar URL、来源、核验时间；
- career/employment history；
- current、start、end、source；
- 支持同一人多次效力同一队而不覆盖旧任期。

关键提交：

- `559e776123f210d13fd145d5497705628268e1f6` — `feat: add people entities avatars and career timelines`

## 头像规则

优先：官方定妆照 → 官方/认证社媒 → 可验证资料源 → fallback glyph。

找不到可靠头像宁愿显示“管 / 教 / 誉”，不乱配同名人物。

---

# dev.29 — Riot Persisted Gateway 故障容灾

**状态：已确认。**

## 真实事故

实机使用 5G 时出现：

`Failed to connect to esports-api.lolesports.com:443`

当时赛程、排名、Team roster、Event Detail 多块功能都压在 Riot Persisted Gateway 上，一处 443 不可达导致多个页面同时空白。

## 新降级链

```text
Riot 官方直连
    ↓
RiftLab Riot Mirror
    ↓
手机磁盘缓存
    ↓
APK 内置 Riot 种子快照
```

Mirror 由 GitHub Actions 周期性从 Riot 官方拉取真实数据；Mirror/缓存不会冒充实时官方直连。

同时 Team Detail 解除对 Riot roster 的强依赖：即使 `getTeams` 失败，RiftLab 自有管理层、教练组和组织资料仍能显示。

关键提交：

- `87da8a7a075f8a59e063b64769c1268f30b33ee9` — `fix: survive Riot persisted gateway outages`

## 结果

“单一公共 API 故障不能把整个 App 打空”成为数据架构硬要求。

---

# dev.30 — Team Archive / 战队完整档案

**状态：已确认。**

## 起因

Team Page 到 dev.29 仍然是“Riot roster + 不断往下面补资料”的结构，已经承载不了真正的战队历史。

用户重新定义了战队页需要包含：

1. 建队日期；
2. 战队管理；
3. 战队选手；
4. 替补和教练；
5. 负责人；
6. 历史离队选手、教练、管理人员以及比赛；
7. 战队荣誉；
8. 前身、所属组织与历史沿革。

## 新数据集

新增：

- `data/lpl/team_archive.json`

并对当时 12 支 LPL 战队进行第一轮运营主体与谱系审计：

`AL / BLG / TES / JDG / LGD / EDG / TT / IG / LNG / NIP / WBG / WE`

## 页面结构

Team Detail 开始拥有：

- TEAM ARCHIVE / 战队档案；
- ORGANIZATION / 当前运营；
- RESPONSIBLE / 负责人；
- STARTING FIVE / ACTIVE ROSTER；
- SUBSTITUTES；
- TEAM MANAGEMENT；
- COACHING STAFF；
- RIFT LEGACY；
- ALUMNI；
- HONORS；
- LINEAGE / 战队沿革与前身；
- MATCHES / 近期赛程。

## 另一个重要修复

dev.28 曾出现“源码 JSON 已经有 AG爱笑、袁玺，手机却还是显示旧数据”的现象。根因之一是远程读取与旧 CDN/旧快照 fallback 的优先级。

dev.30 将动态 Team 数据调整为更可靠的：

```text
GitHub Raw
    ↓
jsDelivr
    ↓
APK bundled seed
```

核心 JSON 随 APK 一起内置，避免 CDN 老缓存把新人员覆盖掉。

## 关键提交

- `36a006fb6f9a87252bfa60b73777e38c4122d0ab` — `feat: rebuild team pages as audited archives`
- 最终 dev.30 OTA 触发提交：`057f5c133c6e803bb4b65a14bb2513c1381e36b0`

## dev.30 结束时的架构边界

到 dev.30，RiftLab 已经拥有相当完整的 LPL Team Archive，但底层仍然以 **LPL 专用 team JSON** 为中心。这一点直接暴露出下一阶段问题：如果接 LCK、LCP、KPL、VALORANT、CS2，就不能继续复制一套新的 `lpl/team_archive.json`。

因此，dev.30 是“战队档案系统完成”的节点，也是下一代 Organization-centric 数据架构的起点。

---

# dev.1 ～ dev.30 期间形成的长期工程原则

## 1. 不用假数据填 UI

没有可靠来源就显示缺失，不用推测值把页面填满。

## 2. 电竞语义优先于上游 API 字段名

`inProgress` 不一定是 `GAME_LIVE`；“积分”必须区分赛事排名积分和年度世界赛积分。

## 3. 当前人员变更必须有明确证据

尤其管理人员：没有“离队 / 离任 / 不再担任”等明确证据，不得自动归档。

## 4. Person、Team、Organization 是不同实体

一个人可以跨队，一个 Team 可以换运营体系，一个品牌也可以继承席位但不能自动继承所有历史荣誉。

## 5. 运营主体比工商字段更有电竞意义

RiftLab 关心谁运营、谁负责、谁负责赛训；不把社会信用代码、注册资本等字段当产品核心。

## 6. 外部数据源必须可降级

生产 App 不再把整个页面绑死在一个 Wiki、一个 CDN 或一个 Riot Gateway 上。

## 7. 固定签名 + OTA 是开发流水线的一部分

“发布版本”从来不只等于 push main，而是：

```text
代码提交
→ 构建 APK
→ 固定开发签名验证
→ dev-latest Release
→ App 内检查更新可发现
```

---

# 截至 dev.30 的产品能力快照

在 dev.30 结束时，RiftLab 已经具备：

- 赛事中心；
- 赛程；
- 排名；
- 年度 Championship Points；
- 淘汰赛/系列赛信息；
- Team Page / Team Archive；
- Riot roster；
- 首发/替补；
- 教练和管理人员；
- Dynamic Team Data；
- People 人物实体和历史履历；
- 运营主体、负责人、前身和谱系；
- PRE / EVENT LIVE / GAME LIVE / BETWEEN GAMES / POST 状态语义；
- Completed Game Archive；
- Riot Persisted Mirror 容灾；
- 固定签名 OTA `dev-latest`。

仍未完成或仅部分完成的领域包括：

- 全历史 alumni 完整覆盖；
- 每支战队全赛事成绩数据库；
- 跨赛区/跨游戏 Organization 数据模型；
- 各战队定制主题；
- 可拖动 Match Timeline；
- 赛后数学模型/Monte Carlo/因果分析驱动的 RiftLab Sandtable。

这些不足不是被隐藏的数据空洞，而是 dev.31 及以后架构继续演进的明确输入。

---

# 下一章：dev.31（本文范围外）

本文严格截止 dev.30。dev.31 开始，RiftLab 将数据核心从 LPL 专用 Team Archive 推进为：

```text
Organization
  → Game
    → Team
      → Roster / Personnel
      → Results
      → Lineage
```

并开始把“冠军荣誉”与“完整赛事成绩”分离。这属于下一阶段架构，不计入本次 dev.1～dev.30 归档。
