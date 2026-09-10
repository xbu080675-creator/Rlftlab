#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(path: str, old: str, new: str) -> None:
    p = ROOT / path
    text = p.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"pattern not found in {path}: {old!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


replace_once("app/build.gradle.kts", "versionCode = 70", "versionCode = 71")
replace_once("app/build.gradle.kts", 'versionName = "1.0.0-dev.70"', 'versionName = "1.0.0-dev.71"')

release_note = (
    "dev.71：完成全面数据第一轮全局查漏补缺。PRE 将首发与 roster pool 分离，同位置多人时不再按名单顺序猜首发，新增教练组/管理层、近期正式 Series 与近期 H2H；Rank、伤病、转会与首发变化没有可信源时继续保持未知。Tournament Edition 新增历史 Completed Events 回填、真实 LiveStats Patch 归档、显式 Final 优先的冠军/亚军结果，并严格保留 DERIVED 兜底标记；Riot 2026 League Handbook 正式接入 LPL/LCK/LCP/LEC/LCS/CBLOL 及 First Stand/MSI/Worlds，规则、参赛队与资格机制优先使用官方快照。Qualification Center 扩展非 LPL 赛区语义，区分直接名次、Championship Points、资格赛与国际赛 Qualification Origin；LCP 2026 接入 Riot 官方 Championship Points 计分公式，但在没有完整赛段结果/官方总分表时不伪造队伍当前总分。全球 Staff 与已核实 MVP/POG 镜像新增 APK 内置种子兜底，GitHub Raw/jsDelivr 不可达时仍可恢复已归档数据；OTA 继续使用 GitHub canonical dev-latest + GitHub-only 加速/Range 断点续传，固定 DEV 签名校验不变。版本升级至 1.0.0-dev.71 / versionCode 71。"
)
(ROOT / "DEV_CURRENT_CHANGELOG.txt").write_text(release_note + "\n", encoding="utf-8")

changelog = ROOT / "DEV_CHANGELOG.txt"
text = changelog.read_text(encoding="utf-8")
entries = []
if not text.startswith("dev.71：") and "\ndev.71：" not in text:
    entries.append(release_note)
if not text.startswith("dev.70：") and "\ndev.70：" not in text:
    entries.append(
        "dev.70：新增独立 Qualification Center，将本届 Standings、全年 Championship Points 与晋级路径拆成不同概念；新增已锁定/仍可争夺/已淘汰/待确认状态、OFFICIAL/PROVIDER/DERIVED/PENDING 证据等级和逐节点 Route，从队伍可反向查询剩余路径。2026 LPL 世界赛资格上下文接入年度积分与官方签位，国际赛先建立 Qualification Origin 档案位，未知来源不从参赛名单反推。版本升级至 1.0.0-dev.70 / versionCode 70。"
    )
if not text.startswith("dev.69：") and "\ndev.69：" not in text:
    entries.append(
        "dev.69：把 Tournament Edition / 年度届次从临时 Schedule 提升为长期档案实体，按 family/year/stage 稳定识别赛事；每届保存身份、Patch、参赛队、资格、规则、签位、赛程、Standings、最终名次、Awards 与 Provenance 槽位，并保证旧届次不会被当前网络窗口覆盖或降级。版本升级至 1.0.0-dev.69 / versionCode 69。"
    )
if entries:
    changelog.write_text("\n\n".join(entries) + "\n\n" + text.lstrip(), encoding="utf-8")

roadmap = ROOT / "docs/RIFTLAB_DEV_67_86.md"
roadmap_text = roadmap.read_text(encoding="utf-8")
marker = "## dev.71 — PRE / 赛前信息完整化\n"
actual = '''## dev.71 — PRE / 赛前信息完整化\n\n实际交付同时承担 dev.69/dev.70 实机后暴露的数据全局查漏：PRE 首发/名单池/Staff/近期战绩链路补齐；Tournament Edition 历史、Patch、Final 结果源增强；2026 Riot League Handbook 覆盖主要赛区与国际赛；Qualification Center 扩展 LCK/LCP/LEC/LCS/CBLOL 机制，其中 LCP 保存官方 Championship Points 公式但不在数据不完整时硬算当前总分；海外 Staff/Awards 增加 APK 种子兜底。仍拿不到的 Rank、伤病、转会、完整 Awards 等继续明确标为未知/待同步。\n'''
if marker in roadmap_text and actual not in roadmap_text:
    roadmap.write_text(roadmap_text.replace(marker, actual, 1), encoding="utf-8")

print("dev71 release metadata prepared")
