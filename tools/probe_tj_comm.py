import json
import urllib.request

DOC = "https://open.tjstats.com/comm-match-app/swagger/v1/doc.json"
TARGETS = [
    "/realtime/topData",
    "/realtime/playerInfo",
    "/realtime/battleData",
    "/realtime/turretData",
    "/realtime/singleBp",
    "/compound/matchDetail",
]
MODELS = [
    "model.TopRealTimeData",
    "model.TopRealTimeDataTeam",
    "model.PlayerRealTimeDataV2",
    "model.PlayerRealtimeData",
    "model.BattleRealTimeData",
    "model.BattleRealTimeDataTeam",
    "model.BattleRealTimeDataPlayer",
    "model.TurretRealTimeData",
    "model.BpRealTimeData",
    "model.CommMatchInfoV2",
]

req = urllib.request.Request(DOC, headers={"User-Agent": "Mozilla/5.0 RiftLab-Probe/1.0"})
with urllib.request.urlopen(req, timeout=20) as r:
    doc = json.load(r)

print("swagger", doc.get("swagger"), "basePath", doc.get("basePath"))
paths = doc.get("paths", {})
for path in TARGETS:
    op = paths.get(path, {}).get("get")
    print("\nENDPOINT", path)
    if not op:
        print("  MISSING")
        continue
    print("  summary:", op.get("summary") or op.get("description"))
    for p in op.get("parameters", []):
        print("  PARAM", json.dumps({k: p.get(k) for k in ("name", "in", "required", "type", "format", "description", "schema") if p.get(k) is not None}, ensure_ascii=False, sort_keys=True))
    for code, resp in (op.get("responses") or {}).items():
        schema = (resp or {}).get("schema")
        if schema:
            print("  RESP", code, json.dumps(schema, ensure_ascii=False, sort_keys=True))

definitions = doc.get("definitions", {})
interesting = sorted(
    name for name in definitions
    if any(token in name.lower() for token in ("bp", "draft", "mvp", "pog", "vote"))
)
print("\nINTERESTING MODELS", json.dumps(interesting, ensure_ascii=False))

seen = set()
def dump_model(name, depth=0):
    if name in seen or depth > 4:
        return
    seen.add(name)
    model = definitions.get(name)
    print("\nMODEL", name)
    if not model:
        print("  MISSING")
        return
    props = model.get("properties", {})
    refs = []
    for key, val in props.items():
        print(" ", key, json.dumps(val, ensure_ascii=False, sort_keys=True))
        ref = val.get("$ref") if isinstance(val, dict) else None
        item_ref = ((val.get("items") or {}).get("$ref") if isinstance(val, dict) else None)
        for raw in (ref, item_ref):
            if raw and raw.startswith("#/definitions/"):
                refs.append(raw.rsplit("/", 1)[-1])
    for child in refs:
        dump_model(child, depth + 1)

for name in MODELS + interesting:
    dump_model(name)
