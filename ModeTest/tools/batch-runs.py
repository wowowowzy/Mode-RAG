#!/usr/bin/env python3
"""批量跑双臂并自动判定。在 ModeTest 服务(18090)启动后运行。
  python tools/batch-runs.py --domain planning --limit 40
断点：已跑过（test_run 里已有该 taskId）的任务会跳过。
注意：/api/runs 列表接口不返回正文，故按 taskId 判重，不按 oursText。"""
import argparse, json, time, urllib.request

ap = argparse.ArgumentParser()
ap.add_argument("--tasks", default="data/tasks/tasks.jsonl")
ap.add_argument("--base", default="http://127.0.0.1:18090")
ap.add_argument("--domain", default="")
ap.add_argument("--limit", type=int, default=0)
a = ap.parse_args()

def post(path, payload):
    req = urllib.request.Request(a.base+path, data=json.dumps(payload).encode(),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=300) as r:
        return json.loads(r.read().decode())

def get(path):
    with urllib.request.urlopen(a.base+path, timeout=60) as r:
        return json.loads(r.read().decode())

done = {r["taskId"] for r in get("/api/runs?limit=200")}
tasks = [json.loads(l) for l in open(a.tasks, encoding="utf-8") if l.strip()]
if a.domain:
    tasks = [t for t in tasks if t["domain"] == a.domain]
todo = [t for t in tasks if t["taskId"] not in done]
if a.limit:
    todo = todo[:a.limit]
print(f"待跑 {len(todo)} 条")
for i, t in enumerate(todo, 1):
    try:
        run = post("/api/runs", {"taskId": t["taskId"]})
        rid = run.get("id")
        post(f"/api/runs/{rid}/judge", {})
        print(f"[{i}/{len(todo)}] {t['taskId']} action={run.get('action')} card={run.get('cardId')}", flush=True)
    except Exception as e:
        print(f"[{i}/{len(todo)}] {t['taskId']} 失败: {e}", flush=True)
    time.sleep(1)
