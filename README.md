# RiftLab 1.0-dev

LOL 社区赛事数据 APP 的第一版可运行骨架。

## 1.0 产品边界

### 赛前
- 赛程 / 对阵
- 首发与替补状态
- 选手 Rank
- 近期英雄/状态
- 人员转会 / 首发变动（数据源待接）

### 赛中
- 实时经济、击杀、防御塔、小龙
- 实时事件
- AI 解读接口
- RiftScreen 悬浮赛事副屏
- Bilibili / 虎牙固定直播间快捷跳转
- **不提供任何直播流**

### 赛后
- 比分 / MVP
- 赛后位置 Rank
- 关键节点
- 后续扩展 TOP10 / BEST / 雷达图 / 完整赛后 AI 复盘

## 当前状态

当前用 `MatchSessionStore` 的 Mock 数据每 2 秒更新，用来验证 UI、动画、副屏与生命周期。

后续真实数据只需实现：

- `LiveMatchDataSource`
- `AiInsightEngine`

不会推翻 UI。

## 构建环境

- Android Gradle Plugin 9.4.0
- Gradle 9.6.0
- Compose BOM 2026.08.00
- compileSdk 37
- targetSdk 36
- minSdk 28
- JDK 17

## 首次运行副屏

Android 会要求授权“在其他应用上层显示”。授权后重新点「开启副屏」或「B站观赛 / 虎牙观赛」。

副屏通过前台服务保持运行，关闭悬浮卡右上角 `×` 即停止。

## 构建方式

本包没有附带 Gradle Wrapper 二进制，避免把大块第三方二进制塞进源码包。

- Android Studio：打开项目并使用 Gradle 9.6.0 / JDK 17 同步。
- 本地有 Gradle：`./build.sh`
- GitHub：已带 `.github/workflows/android-build.yml`，推送后会自动构建 debug APK artifact。
