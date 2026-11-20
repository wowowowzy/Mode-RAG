#!/usr/bin/env python3
"""三臂实验运行器（P4）。在 ModeTest 服务(18090)启动后运行。

每条任务跑 baseline / oracle / system 三臂，调用 POST /api/arms/run，
结果落 JSONL（统一输出契约），并另写一份去掉 arm 标签的盲评文件。

  python tools/three-arm.py --domain planning --limit 30
  python tools/three-arm.py --tasks data/tasks/holdout-frozen.jsonl --domain debate --limit 30
  python tools/three-arm.py --tasks data/tasks/holdout-frozen.jsonl --arms baseline,oracle,system
"""
import argparse
import json
import time
import urllib.request
from pathlib import Path

ap = argparse.ArgumentParser()
ap.add_argument("--tasks", default="data/tasks/tasks.jsonl")
ap.add_argument("--base", default="http://127.0.0.1:18090")
ap.add_argument("--domain", default="", help="只跑某个域，默认全部")
ap.add_argument("--split", default="", help="只跑某个 split，默认全部")
ap.add_argument("--limit", type=int, default=0, help="每域最多跑多少条，默认不限")
ap.add_argument("--arms", default="baseline,oracle,system")
ap.add_argument("--out", default="var/three-arm/results.jsonl")
ap.add_argument("--blind-out", default="var/three-arm/blind.jsonl")
ap.add_argument("--sleep", type=float, default=0.2)
a = ap.parse_args()

arms = [x.strip() for x in a.arms.split(",") if x.strip()]
unknown = [x for x in arms if x not in ("baseline", "oracle", "system")]
if unknown:
    raise SystemExit("未知 arm: " + ",".join(unknown))


def post(path, payload):
    req = urllib.request.Request(
        a.base + path,
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=600) as r:
        return json.loads(r.read().decode("utf-8"))


out = Path(a.out)
out.parent.mkdir(parents=True, exist_ok=True)
blind = Path(a.blind_out)
blind.parent.mkdir(parents=True, exist_ok=True)

done = set()
if out.exists():
    for line in out.read_text(encoding="utf-8").splitlines():
        if line.strip():
            r = json.loads(line)
            done.add((r["taskId"], r["arm"]))
    print(f"已有进度 {len(done)} 条，将跳过", flush=True)

tasks = [json.loads(line) for line in Path(a.tasks).read_text(encoding="utf-8").splitlines() if line.strip()]
if a.domain:
    tasks = [t for t in tasks if t["domain"] == a.domain]
if a.split:
    tasks = [t for t in tasks if (t.get("meta") or {}).get("split") == a.split]
if a.limit:
    by_domain = {}
    selected = []
    for t in tasks:
        n = by_domain.get(t["domain"], 0)
        if n < a.limit:
            selected.append(t)
            by_domain[t["domain"]] = n + 1
    tasks = selected

print(f"待跑 {len(tasks)} 条 × {len(arms)} 臂", flush=True)
with out.open("a", encoding="utf-8") as handle, blind.open("a", encoding="utf-8") as blind_handle:
    for index, task in enumerate(tasks, 1):
        for arm in arms:
            if (task["taskId"], arm) in done:
                continue
            started = time.time()
            try:
                result = post("/api/arms/run", {"taskId": task["taskId"], "arm": arm})
            except Exception as error:
                print(f"[{index}] FAIL {task['taskId']} {arm}: {error}", flush=True)
                continue
            handle.write(json.dumps(result, ensure_ascii=False) + "\n")
            handle.flush()
            # 盲评文件：去掉 arm / action / cardId / libraryVersion / 耗时等泄露来源的信息
            blinded = {k: v for k, v in result.items()
                       if k not in ("arm", "action", "cardId", "libraryVersion",
                                    "routerMs", "modelMs", "promptTokens", "completionTokens")}
            blinded["blindId"] = f"{index:04d}-{arm}"
            blind_handle.write(json.dumps(blinded, ensure_ascii=False) + "\n")
            blind_handle.flush()
            print(f"[{index}/{len(tasks)}] {task['taskId']} {arm} "
                  f"score={result['score']} {int((time.time()-started)*1000)}ms", flush=True)
            if a.sleep:
                time.sleep(a.sleep)

print(f"\n完成。结果 {out}，盲评 {blind}", flush=True)
