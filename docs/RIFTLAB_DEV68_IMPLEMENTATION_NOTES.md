# dev.68 implementation notes

本次交付是“全面数据”的基础设施版本，不宣称历史/资格/赛后高级统计已经全部抓齐。

已经进入运行时的部分：统一 Graph、Tournament Edition identity、当前 Series/Team/Player/Roster、LIVE Game/Player Stats、Completed Game、Standings、Provenance、Freshness、12 域 Coverage，以及 PRE 页面 Coverage 面板。

仍然明确留作后续填充的部分：全球历史 Series/Game 目录、跨赛季 canonical Player identity 合并、Championship Points 与 Qualification path、完整 Draft、伤害/承伤/视野等 POST 高级统计、完整 Timeline 与 VOD 对齐。

开发原则：后续增加 Provider 时优先写 Adapter 进入统一 Graph，不再让新页面各自创造一套互不相认的数据模型。
