# RiftLab 开发履历续篇：dev.31 ～ dev.66

> 前篇：`docs/RIFTLAB_DEVELOPMENT_HISTORY.md`（重点记录 MVP ～ dev.30）
>
> 本篇基线：`1.0.0-dev.66`
>
> 原则：只写能由仓库、提交记录和现有代码确认的能力。dev.35～dev.46 的旧逐版记录不完整，因此该段按可核验工程阶段记录，不强行编造“一版一个功能”的对应关系。

---

## 1. dev.31 — 从 LPL 战队表升级为通用电竞图谱

`dev.31` 把数据核心从“某支战队的一份资料”提升为：

```text
Organization → Game → Team → Roster / Personnel
```

主要变化：

- 引入 `organization_id / game_id / team_id` 等稳定标识；
- 建立 `data/esports/esports_graph.json`；
- 当前 LPL 12 支战队作为第一批图谱节点；
- 运营主体、上层组织、负责人、谱系、人员与赛事成绩通过关系挂接；
- “冠军荣誉”和“赛事成绩”正式拆开，冠军进入 Honors，亚军/季军/四强/八强等进入 Results；
- 前身战队成绩不默认继承给当前品牌。

这一步让后续 LCK、LCP，甚至 KPL、VALORANT、CS2 可以继续扩展，而不是重新复制一套战队 UI。

---

## 2. dev.32 ～ dev.34 — 战队视觉系统与 Timeline 成型

### dev.32：Team Skin Registry

AL 成为第一支真正拥有专属视觉皮肤的战队。关键不是“把页面涂红”，而是把战队视觉拆成可复用的：

- Team identity；
- Palette；
- Backdrop / motif；
- 业务页面继承关系。

页面业务逻辑不和某一支战队配色绑定。

### dev.33：12 支 LPL 战队统一接入

AL、BLG、EDG、IG、JDG、LGD、LNG、NIP、TES、TT、WBG、WE 全部进入 Team Skin Registry，并同时完成系统浅色/深色双模式。

从此“战队主题”成为渲染层，而不是每支队复制一套 Compose 页面。

### dev.34：比赛 Timeline 真正可用

新增/强化：

- `MatchTimelineStore`；
- `MatchTimelineCapture`；
- 正常阶段周期状态落点；
- 击杀、塔、龙、男爵、明显经济变化的额外事件点；
- 可确认具体击杀者时记录，无法确认受害者配对时不猜；
- 按 G1/G2/... 查看历史小局；
- 时间轴筛选与回看；
- 团战窗口聚合；
- 本地持久化历史小局过程。

RiftScreen 同期开始继承 Team Skin，不再永远是固定青黑主题。

---

## 3. dev.35 ～ dev.46 — 从“比赛详情”向完整观赛/回放数据层扩张

这一阶段提交非常密集，旧 `DEV_CHANGELOG.txt` 没有留下可靠的一版一条映射。本履历不重新猜版本号，但可以从最终代码和提交链确认这一阶段完成了几类关键建设。

### 比赛生命周期继续加固

`MatchSessionStore`、`MatchLifecycleArchive`、`CompletedGameArchive` 等模块继续分离：

- 当前小局状态；
- 已结束小局；
- Series 生命周期；
- 本地历史档案；
- Timeline 过程数据。

避免“上一局终局数据污染下一局”和“下一局开始后上一局结果消失”。

### 比赛详情开始从比分卡变成数据面

围绕 `MatchDetailRepository`、`MatchDetailUi`、`MatchOperationsContent`、`MatchTimelineContent` 等模块，逐步形成：

- Series / Game 层级；
- BP、资源、选手数据；
- Timeline；
- 历史赛后补全；
- 来源状态与降级标识。

### 回放系统进入 APP 内

形成 Bilibili、Riot/LoL Esports、YouTube 等多路径回放解析，随后进入 Media3 / WebView 的 APP 内播放路线。回放不等于 RiftLab 托管视频：视频字节仍来自上游平台/CDN。

### 全球赛事开始摆脱 LPL 专用假设

数据层陆续出现 Riot 全球赛程/排名、OP.GG 历史补全、全球队伍/人员、海外赛后等模块，为后续 dev.47 的全球入口重构做准备。

> 后续如果从 Git 历史恢复出 dev.35～dev.46 的逐版 tag / Release 文案，再补一份“精确逐版索引”；在证据不足前，不把阶段能力硬分配到某个具体版本。

---

## 4. dev.47 ～ dev.54 — 全球赛事、回放和赛后链路完成第一次大扩张

### dev.47：全球赛事入口重构

- 首页新增赛区订阅；
- 赛事中心一级拆成“联赛 / 国际赛”；
- 国际赛固定 family 入口；
- 扩展 Riot 官方赛事目录发现；
- 海外赛事回放与 Bilibili 解耦；
- 非 LPL 优先 Riot / YouTube / LoL Esports；
- 赛程时间显式显示本机当地时间与 UTC 偏移。

### dev.48：订阅真正控制首页与数据目标

- 解决只订 LPL 却被其他赛区 LIVE 抢占首页的问题；
- 非 LPL 详情停止调用 LPL 专用数据链；
- 海外官方回放进入 APP 内；
- 全屏播放支持传感器方向；
- 延迟重型排名加载、降低长尾赛区启动请求。

### dev.49：全球比赛详情与回放边界修复

- 次级/学院队命名澄清；
- 恢复全球 Series 总比分；
- 海外 Timeline 与 Bilibili 彻底隔离；
- 加固 YouTube 官方 VOD 播放。

### dev.50：赛事目录继续治理

- LDL 从当前订阅主入口退出；
- Worlds 2026 提升优先级；
- 增加德杯国际项目、WSCL 等目录节点；
- 赛事中心打开时尊重当前订阅/目录，不再自动跳到无关活跃赛事。

### dev.51：国际赛中国大陆回放体验

- 国际大赛优先尝试官方 Bilibili 中国 VOD；
- 同时保留 Riot / YouTube；
- YouTube WebView 保持真实 UA 与会话，允许正常验证/登录。

### dev.52：全球赛训人员与视频渲染

- Riot GCD 数据进入全球管理/教练识别链；
- YouTube embed 渲染稳定化与硬件加速处理。

### dev.53：Bilibili VOD 匹配容错

- 支持正反队名顺序；
- 负结果缓存允许重试；
- 减少“明明有官方回放却因为搜索顺序没命中”的情况。

### dev.54：全球已结束赛事补全

- OP.GG 终局数据进入全球 completed-series 档案；
- Riot/OP.GG 历史小局枚举继续补全；
- 全球 MVP / Award 镜像和明确标记的 fallback 链进入系统。

---

## 5. dev.55 ～ dev.58 — Provider、赛事治理与年度档案

### dev.55：Cito 成为可选一等 Provider

Cito 不再只做一小块补丁，而是进入：

- Schedule / Team / Standings supplement；
- 配额感知 REST Live fallback；
- 可选 WebSocket；
- 原始 Provider 档案；
- 全球赛后补全。

Provider 返回值仍要带来源，不能冒充 Riot 官方确认。

### dev.56：Tournament Governance

建立赛事规则与抽签/签位治理：

- Rulebook；
- Draw / Slot；
- 资格与签位确认；
- 已确认事实不被低优先级 Provider 覆盖。

### dev.57：Tournament Research

每年/每届赛事开始成为独立档案单元，容纳：

- 比赛版本；
- 更新；
- 规则；
- 抽签；
- 赛程；
- 后续可继续补排名、奖项和完整赛后。

### dev.58：年度研究编译修复 + 第一轮大陆 OTA

- 修复 Tournament Research UI / 构建问题；
- 第一次建立大陆优先 OTA + GitHub 兜底框架；
- 这套分发方案随后在 dev.59～dev.66 继续多轮演化。

---

## 6. dev.59 ～ dev.61 — 国内 OTA 连续三轮重构

### dev.59：最终切到 GitHub canonical + 请求级加速

这一版早期曾尝试 GitHub Actions → Gitee Release，大文件上传过程中处理过：

- 空 Release；
- API 请求超时；
- multipart attachment 鉴权。

随后为了不让镜像大文件阻塞 canonical 发布，dev.59 最终改成：

- GitHub 是唯一正式 APK / manifest Release；
- APP 先 GitHub 直连；
- 失败后临时启用 GitHub-only 请求加速；
- 加速只允许 RiftLab 官方 `dev-latest` 白名单；
- HTTP Range 断点续传；
- 不创建 VPN / 系统代理。

### dev.60：LIVE / ON AIR + 全球观赛入口

- `GAME_LIVE` 显示 LIVE；
- `EVENT_LIVE / BETWEEN_GAMES` 保持 ON AIR / 局间语义；
- 国内 Bilibili / 虎牙；
- 全球 LoL Esports / YouTube / Twitch / X 统一进入观赛跳转 Hub。

这一版继续坚持：**赛事直播开始 != 小局进入游戏。**

### dev.61：修掉固定加速节点大文件卡死

`gh-proxy.com` 类固定节点在真实 APK 大文件下载中出现“能连、但大文件卡死/很慢”。因此改成多节点故障切换，并保留 GitHub 直连与 Range 续传。

---

## 7. dev.62 ～ dev.64 — RiftScreen 从悬浮卡升级为可编辑 BP HUD

### dev.62：Draft HUD Simulator

在接真实 Draft WebSocket 前，先用本地模拟器把产品形态跑通：

- 全屏视觉 HUD；
- 主 HUD `FLAG_NOT_TOUCHABLE`；
- 独立小控制 Dock；
- BP pick / matchup / counter 状态模拟；
- 保留下方直播安全区域；
- 模拟数据不写入真实比赛档案。

### dev.63：用户拥有自己的 HUD 布局

新增：

- EDIT / LOCK；
- 拖动模块；
- Scale；
- Alpha；
- Visibility；
- Reset；
- 归一化坐标；
- 横屏 / 竖屏独立 Profile；
- LOCK 后完整触摸穿透。

### dev.64：实屏体验收口

- `DRAFT LOCKED` 完成提示短暂确认后自动隐藏；
- 编辑时仍保留状态；
- matchup intelligence 压缩为更短的双行条；
- 尽量减少对直播画面的遮挡。

---

## 8. dev.65 — GitHub OTA 自适应测速

固定节点依然可能因地区、运营商、时段不同而表现完全不同，因此 dev.65 改成“测真实 APK，再决定走谁”。

机制：

1. 对 GitHub 直连和多个 GitHub-only 加速节点并发请求真实 RiftLab APK 的小段 Range；
2. 按实际 bytes/sec 排序；
3. 优先从最快通道下载；
4. 下载过程中实时计算速度；
5. 加速节点长期明显低于测速预期时，保留 `.part` 换线；
6. 版本化 APK 不再发送 `Cache-Control: no-cache`，允许 CDN/反代命中缓存；
7. `latest.json` 继续 no-cache；
8. SHA-256、包名、versionCode、固定 DEV 签名全部继续强校验。

默认节点池包括 GH LLKK、iSteed、XMLY、DDLC、GHFast、GHProxy.net，并保留 GitHub 直连。

---

## 9. dev.66 — Gitee 大陆第一源 + GitHub 自适应兜底

这一版不是回到 dev.59 早期“把 Gitee 当第二套发布系统”的方案，而是重新明确角色：

```text
GitHub = version/build/canonical release truth
Gitee = China binary transport mirror
APP = source selection + verification + fallback
```

发布侧：

- GitHub Actions 构建唯一 APK；
- 先完成 GitHub canonical `dev-latest`；
- 再把同一 APK 与同一 `latest.json` 上传 Gitee Release；
- Gitee 不运行 CI；
- Gitee 镜像失败不能回滚 GitHub Release。

客户端：

- 优先检查/下载 `Gitee 国内 OTA`；
- Gitee 失败自动回退 GitHub；
- GitHub 路径完整继承 dev.65 的真实 APK 自适应测速；
- Gitee → GitHub 跨源续传必须验证版本号、SHA-256 和文件长度身份；
- 任意来源最终仍必须通过包名、versionCode、SHA-256 和固定签名验证后才允许安装。

这一版的实际目标只有一个：**用户在国内点 APP 的“下载并安装”时，优先拿到国内 Release 的速度，同时镜像挂掉也不会失去更新能力。**

---

# 10. 到 dev.66 时，RiftLab 已经是什么

截至 dev.66，产品已经具备这些稳定支柱：

```text
赛事资料库
+ PRE / LIVE / POST 生命周期
+ Riot / Mirror / Cache / Seed 多级数据降级
+ 全球赛事与赛区订阅
+ Team Archive / People / Esports Graph
+ 比赛详情 / Timeline / Completed Game Archive
+ Bilibili / Riot / YouTube 回放与 APP 内播放
+ Championship Points / Tournament Governance / Tournament Research
+ RiftScreen 实时副屏与可编辑 Draft HUD
+ APP 内完整 OTA 下载、校验、安装
+ Gitee 大陆第一源 + GitHub 自适应兜底
```

它已经不再是“一个英雄联盟比分 APP”。更准确的当前产品形态是：

**英雄联盟赛事资料库 + 实时观赛数据层 + 回放/时间轴 + 可定制 RiftScreen 副屏。**

下一阶段的重点不应该继续无限加零散页面，而是两条主线：

1. 把赛事数据覆盖率、赛前、赛中、赛后内容继续补完整；
2. 把现有真实赛事模型接入 Sandbox，形成可分支的电竞复盘/战术分析系统。

新的版本排期见：`docs/RIFTLAB_DEV_67_86.md`。
