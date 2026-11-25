#!/usr/bin/env bash
# 客流迁移 / 旅程重建（feature-2）关键不变量验证。
# 用法：./verify-migration.sh [BASE_URL]
# 依赖：bash、curl、python3（仅标准库）
set -u
BASE="${1:-${BASE_URL:-http://127.0.0.1:8080}}"
PYBIN="$(command -v python3 || true)"
if [ -z "$PYBIN" ] && [ -x /opt/python-3.12/bin/python3 ]; then PYBIN=/opt/python-3.12/bin/python3; fi
[ -n "$PYBIN" ] || { echo "未找到 python3"; exit 1; }

echo "==> 等待服务健康：$BASE"
for i in $(seq 1 60); do
  if curl -fsS --max-time 3 "$BASE/api/health" 2>/dev/null | grep -q '"db":"UP"'; then
    echo "    health UP"; break
  fi
  sleep 2
  if [ "$i" = "60" ]; then echo "服务未就绪"; exit 1; fi
done

BASE="$BASE" "$PYBIN" - <<'PY'
import base64, json, os, sys, urllib.request, urllib.error

BASE = os.environ["BASE"].rstrip("/")
fails, passes = [], []

def req(method, path, user="analyst", pwd="analyst123", body=None, auth=True):
    r = urllib.request.Request(BASE + path, method=method)
    if auth:
        token = base64.b64encode(f"{user}:{pwd}".encode()).decode()
        r.add_header("Authorization", "Basic " + token)
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        r.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(r, data=data, timeout=25) as resp:
            txt = resp.read().decode()
            return resp.status, (json.loads(txt) if txt else {})
    except urllib.error.HTTPError as e:
        txt = e.read().decode()
        try:
            return e.code, json.loads(txt)
        except Exception:
            return e.code, txt

def check(name, cond, detail=""):
    (passes if cond else fails).append(name)
    print(("  ✅ " if cond else "  ❌ ") + name + ("" if cond or not detail else f"  -> {detail}"))

def close(a, b, tol=0.15):
    return abs(a - b) <= tol

E2, E1, HUB, W2 = "E2-EXPO", "E-LAKE", "HUB-CENTRAL", "W-STATION"

# 0. 取实验 id，验证嵌套路径
st, exp = req("GET", "/api/experiments/by-code/PILOT-R123-202606")
check("样例实验存在", st == 200 and exp.get("id"), str(exp)[:160])
eid = exp.get("id")

# 1. 总览：五类产物齐备
st, ov = req("GET", "/api/migration/overview")
check("迁移总览 200", st == 200, str(st))
check("上车观察账已建立(>=100)", int(ov.get("boardingLedger", 0)) >= 100, str(ov.get("boardingLedger")))
check("重建旅程与账一一对应", ov.get("rebuiltJourneys") == ov.get("boardingLedger"))
check("OD 双引擎流量行=2*OD(>=30 行)", int(ov.get("odFlowRows", 0)) >= 30, str(ov.get("odFlowRows")))
check("存在定位中断个案(>=10)", int(ov.get("locationOutageCases", 0)) >= 10)
check("存在多候选旅程(>=2)", int(ov.get("multiCandidateJourneys", 0)) >= 2)
check("存在短线折返个案", int(ov.get("shortTurnCases", 0)) >= 1)

st, ov2 = req("GET", f"/api/experiments/{eid}/migration/overview")
check("嵌套实验路径 /experiments/{id}/migration/overview 可用", st == 200 and ov2.get("boardingLedger"))

# 2. 定位中断：保留多候选（硬不变量）
st, d = req("GET", "/api/migration/journeys/C-DEMO-GAP-01")
j = d.get("journey") or {}
cands = d.get("candidates") or []
check("中断样例旅程存在", st == 200 and bool(j), str(st))
check("中断样例 location_outage=true", j.get("location_outage") is True)
check("中断样例 multi_candidate=true", j.get("multi_candidate") is True)
check("定位中断保留 >=2 个候选下车站", len(cands) >= 2, str(len(cands)))
check("至少 2 个候选后验 >=0.10", sum(1 for c in cands if float(c.get("posterior_prob", 0)) >= 0.10) >= 2)
probs = [float(c.get("posterior_prob", 0)) for c in cands]
check("候选后验之和约为 1", bool(probs) and close(sum(probs), 1.0, 0.06), str(sum(probs)))

# 3. 上车站本身在中断区间 -> GPS_GAP_FUZZY
st, fb = req("GET", "/api/migration/boardings/C-DEMO-FUZZY-01")
check("中断区间上车站为 GPS_GAP_FUZZY",
      st == 200 and (fb.get("boarding") or {}).get("align_status") == "GPS_GAP_FUZZY",
      str((fb.get("boarding") or {}).get("align_status")))

# 4. 轨迹对齐数据
st, trs = req("GET", "/api/migration/trajectories")
gap_trip = next((t for t in trs if t.get("trip_code") == "R2-GAP-0812"), None)
st_trip = next((t for t in trs if t.get("trip_code") == "R1-ST-0810"), None)
check("轨迹列表含中断班次与折返班次", bool(gap_trip) and bool(st_trip))
check("中断班次记录缺测 fix 点", bool(gap_trip) and int(gap_trip.get("missing_fix_points", 0)) >= 1)
check("折返班次 short_turn=true 且折返站=HUB",
      bool(st_trip) and st_trip.get("short_turn") is True and st_trip.get("turn_stop") == HUB)
st, td = req("GET", "/api/migration/trajectories/R2-GAP-0812")
check("轨迹详情含逐站点", st == 200 and len(td.get("points") or []) >= 4)

# 5. OD 矩阵：两引擎同一站对 + 区间有序 + 损失为正
st, m = req("GET", "/api/migration/od-matrix")
matrix = m.get("matrix") or []
check("OD 矩阵非空(>=15 站对)", len(matrix) >= 15, str(len(matrix)))
def od(o, d):
    return next((r for r in matrix if r.get("origin") == o and r.get("dest") == d), None)
row = od(E1, W2)
check("存在 E-LAKE->W-STATION 站对且双引擎齐备",
      bool(row) and row.get("deterministic") and row.get("probabilistic"))
p = (row or {}).get("probabilistic") or {}
check("概率迁移量显著(>5)", float(p.get("mig_point", 0)) > 5, str(p.get("mig_point")))
check("概率迁移区间下界>0", float(p.get("mig_low", 0)) > 0, str(p.get("mig_low")))
check("换乘损失均值>300s 且区间下界>0",
      float(p.get("loss_mean_sec", 0)) > 300 and float(p.get("loss_low_sec", 0)) > 0,
      f"mean={p.get('loss_mean_sec')} low={p.get('loss_low_sec')}")
check("损失区间有序 low<=mean<=high",
      float(p.get("loss_low_sec", 0)) <= float(p.get("loss_mean_sec", 0)) + 0.11
      and float(p.get("loss_high_sec", 0)) + 0.11 >= float(p.get("loss_mean_sec", 0)))
for bname in ["deterministic", "probabilistic"]:
    b = (row or {}).get(bname) or {}
    check(f"{bname} 迁移区间包含点估计",
          float(b.get("mig_low", 0)) - 0.11 <= float(b.get("mig_point", -1))
          and float(b.get("mig_high", 0)) + 0.11 >= float(b.get("mig_point", -1)),
          str({k: b.get(k) for k in ("mig_low", "mig_point", "mig_high")}))

# 6. 抽样核验三态
st, v = req("GET", "/api/migration/verification")
vrows = v.get("rows") or []
def vrow(o, d):
    return next((x for x in vrows if x.get("origin_stop") == o and x.get("dest_stop") == d), None)
vc = vrow(E2, W2); ok = vrow(E1, W2); ins = vrow(HUB, W2)
check("E2->W2 判定 CONFLICT 且区间不重叠",
      bool(vc) and vc.get("status") == "CONFLICT" and vc.get("intervals_overlap") is False)
check("E1->W2 判定 CORROBORATED", bool(ok) and ok.get("status") == "CORROBORATED")
check("HUB->W2 小样本判定 INSUFFICIENT", bool(ins) and ins.get("status") == "INSUFFICIENT")

# 7. 门控：冲突 -> 禁单点
st, pub = req("GET", "/api/migration/publication")
gate = pub.get("gate") or {}
check("门控 singlePointAllowed=false", gate.get("singlePointAllowed") is False)
check("门控状态 INTERVAL_ONLY", gate.get("status") == "INTERVAL_ONLY", str(gate.get("status")))

st, _ = req("POST", "/api/migration/publish", body={"scope": "REPORT", "mode": "POINT"})
check("报告级单点发布被 409 拒绝", st == 409, f"status={st}")
st, _ = req("POST", "/api/migration/publish",
            body={"scope": "OD", "origin": E2, "dest": W2, "mode": "POINT"})
check("冲突 OD 单点发布被 409 拒绝(任何角色不可绕过)", st == 409, f"status={st}")
st, r = req("POST", "/api/migration/publish", body={"scope": "REPORT", "mode": "INTERVAL"})
check("报告级包络区间可发布(200, low<=high)",
      st == 200 and r.get("status") == "INTERVAL_ONLY"
      and float(r.get("intervalLow", 1)) <= float(r.get("intervalHigh", 0)) + 1e-9, str(r)[:160])
st, r = req("POST", "/api/migration/publish",
            body={"scope": "OD", "origin": E1, "dest": W2, "mode": "POINT"})
check("相容 OD 单点可发布(200)", st == 200 and r.get("status") == "PUBLISHED", f"status={st} {str(r)[:120]}")

# 8. 权限
st, _ = req("POST", "/api/migration/rebuild", user="viewer", pwd="viewer123", body={})
check("viewer 重算被 403", st == 403, f"status={st}")
st, _ = req("GET", "/api/migration/overview", auth=False)
check("未认证访问被 401", st == 401, f"status={st}")

# 9. 重算幂等可用
st, r = req("POST", "/api/migration/rebuild", body={})
check("analyst 重算重建 200 且产物数量稳定",
      st == 200 and int(r.get("boardingCases", 0)) >= 100 and int(r.get("odPairs", 0)) >= 15, str(r)[:160])

# 10. 常见别名可达
for path in ["/api/migration", "/api/migration/flow-matrix", "/api/migration/samples",
             "/api/migration/alighting-candidates", "/api/migration/boarding-ledger",
             "/api/migration/journeys", "/api/migration/workbench"]:
    st, _ = req("GET", path)
    check(f"别名可达 {path}", st == 200, f"status={st}")

# 11. 前端资源含迁移标签
try:
    with urllib.request.urlopen(BASE + "/app.js", timeout=10) as resp:
        js = resp.read().decode("utf-8", "replace")
    with urllib.request.urlopen(BASE + "/index.html", timeout=10) as resp:
        html = resp.read().decode("utf-8", "replace")
    check("index.html 含 migration 标签页", 'data-tab="migration"' in html)
    check("app.js 含迁移工作台渲染", "RENDER.migration" in js and "客流迁移" in js)
except Exception as e:
    check("前端资源可抓取", False, str(e))

print(f"\n通过 {len(passes)} 项，失败 {len(fails)} 项")
if fails:
    print("失败项：" + "; ".join(fails))
    sys.exit(1)
print("ALL_MIGRATION_CHECKS_PASSED")
PY
