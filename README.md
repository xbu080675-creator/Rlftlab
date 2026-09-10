# RiftLab

英雄联盟赛事数据、观赛副屏与复盘工具。当前 DEV 基线：`1.0.0-dev.66`。

RiftLab 的目标不是再做一个只有比分的 APP，而是在官方直播之外提供一层可持续积累的赛事资料、实时数据、回放/Timeline 和 RiftScreen 辅助层。RiftLab 不托管直播流。

## 当前产品结构

### PRE / 赛前

- 全球/赛区赛事目录与赛程；
- 赛区订阅；
- 首发、替补、教练/管理人员；
- 战队档案、运营关系、人物履历与电竞图谱；
- Championship Points / 资格与赛事治理能力；
- 赛前选边与后续 Rank/近期状态补全接口。

### LIVE / 赛中

- `GAME_LIVE / EVENT_LIVE / BETWEEN_GAMES` 等真实生命周期语义；
- Riot/LPL 多数据源与降级链；
- 经济、击杀、塔、龙、男爵、选手实时状态等可用数据；
- Match Timeline 持续采集；
- RiftScreen 悬浮副屏；
- 可编辑/锁定 Draft HUD；
- Bilibili / 虎牙 / LoL Esports / YouTube / Twitch / X 等观赛入口。

### POST / 赛后

- Series / Game 比分与历史小局；
- 比赛详情、资源与选手数据；
- Timeline 本地归档；
- Bilibili / Riot / YouTube 等回放链；
- APP 内 Media3 / WebView 播放；
- OP.GG / Riot / Cito 等全球赛后补全；
- Tournament Research / Awards / Team Archive。

## 数据可靠性

关键数据不押注单一公网接口。当前多条链路支持类似：

```text
Official Provider
→ RiftLab Mirror / Secondary Provider
→ Device Cache
→ APK Seed
```

UI 应明确来源，不把缓存、结构推导或第三方 Provider 伪装成官方实时数据。没有可靠记录时宁可显示缺失，也不为了填页面制造假事实。

## APP 内 OTA

当前架构（dev.59 起，dev.65 完成自适应测速，dev.66 锁定决策）：

```text
GitHub canonical dev-latest Release
→ GitHub 直连 + GitHub-only 加速节点并发测速
→ 按真实 APK 吞吐选择最快路径
→ 失败/过慢时保留断点自动换线
```

**Gitee OTA 已废弃。** Gitee 不参与 RiftLab APK / `latest.json` 分发，不需要 `GITEE_TOKEN`，也不得重新成为 APP 运行时或发布工作流的更新依赖；如保留 Gitee 仓库，只作为源码镜像使用。

安装前继续强制验证 SHA-256、包名、versionCode 和固定 DEV 签名证书。详见 `docs/OTA_CN.md`。

## 构建环境

- Gradle 9.6.0
- Compose BOM 2026.06.00
- compileSdk 36
- targetSdk 36
- minSdk 28
- JDK 17

本仓库不附 Gradle Wrapper 二进制：

- Android Studio：使用 JDK 17 / Gradle 9.6.0；
- 本地已有 Gradle：`./build.sh`；
- GitHub Actions：`.github/workflows/android-build.yml` 与 `.github/workflows/ota-direct.yml`。

## 文档

- `docs/RIFTLAB_DEVELOPMENT_HISTORY.md`：MVP ～ dev.30 的产品/工程演化；
- `docs/RIFTLAB_DEVELOPMENT_HISTORY_DEV31_66.md`：dev.31 ～ dev.66 续篇；
- `docs/RIFTLAB_DEV_67_86.md`：从当前真实版本重新排定的后续 20 版路线；
- `docs/OTA_CN.md`：中国大陆 APP 内更新架构；
- `docs/SIGNING.md`：DEV 签名说明。

旧 `docs/RIFTLAB_DEV_59_78.md` 保留为 dev.58 时的历史计划快照；其中版本号已被实际开发占用，不再作为当前执行排期。
