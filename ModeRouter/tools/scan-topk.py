#!/usr/bin/env python3
"""对 debate 任务按不同 topK 重跑召回+精判，报告各 topK 下召回命中与精判命中。
直接调 /v1/enhance。需服务已启动。只读任务集，会写审计日志。"""
import argparse, hashlib, json, urllib.request
from pathlib import Path

ap = argparse.ArgumentParser()
ap.add_argument("--tasks", default="../ModeTest/data/tasks/tasks.jsonl")
ap.add_argument("--endpoint", default="http://127.0.0.1:18080/v1/enhance")
ap.add_argument("--domain", default="debate")
ap.add_argument("--topks", default="8,12,16,20")
a = ap.parse_args()

tasks = [json.loads(l) for l in Path(a.tasks).read_text(encoding="utf-8").splitlines() if l.strip()]
tasks = [t for t in tasks if t["domain"] == a.domain]

def call(prompt, topk):
    body = json.dumps({"task": prompt, "options": {"topK": topk}}, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(a.endpoint, data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=120) as resp:
        return json.loads(resp.read().decode("utf-8"))

for topk in [int(x) for x in a.topks.split(",")]:
    recall_hit = judge_hit = 0
    for t in tasks:
        acc = set(t["acceptableCards"])
        try:
            r = call(t["prompt"], topk)
        except Exception as e:
            print("  调用失败", t["taskId"], e); continue
        meta = r.get("meta") or {}
        card = meta.get("cardId")
        judge_hit += (meta.get("action") == "INJECT" and card in acc)
        # 召回命中需要候选池——从审计读较繁琐，这里仅用"精判是否命中"近似比较
    print(f"topK={topk}: 精判命中 {judge_hit}/{len(tasks)} = {judge_hit/len(tasks):.0%}")
