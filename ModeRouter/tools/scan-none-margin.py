#!/usr/bin/env python3
"""离线扫描 noneMargin：优先用 audit 中温度标定后的 probabilities 重放门控
（标定会锐化分布，用 rawProbabilities 会系统性低估 margin 效果），仅在缺失时回退 raw。
统计各 margin 下的总体准确率与 FALLBACK 漏判数，选使「准确率最高且漏判最少」的 margin。只读。"""
import argparse, glob, hashlib, json
from pathlib import Path

ap = argparse.ArgumentParser()
ap.add_argument("--audit-dir", default="var/audit")
ap.add_argument("--tasks", default="../ModeTest/data/tasks/tasks.jsonl")
ap.add_argument("--version", required=True)
ap.add_argument("--domain", default="", help="只统计某个域，默认全部")
ap.add_argument("--topk", type=int, default=0, help="只统计某个 topK 的记录，默认全部")
a = ap.parse_args()

tasks = {}
for line in Path(a.tasks).read_text(encoding="utf-8").splitlines():
    if line.strip():
        t = json.loads(line)
        tasks[hashlib.sha256(t["prompt"].encode("utf-8")).hexdigest()] = t

samples = {}
for f in sorted(glob.glob(str(Path(a.audit_dir)/"audit-*.jsonl"))):
    for line in Path(f).read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        r = json.loads(line)
        if r.get("libraryVersion") != a.version or r.get("taskSha256") not in tasks:
            continue
        if a.domain and tasks[r["taskSha256"]].get("domain") != a.domain:
            continue
        if a.topk and r.get("topK") != a.topk:
            continue
        dec = r.get("decision") or {}
        # Gate 实际使用的是温度标定后的 probabilities；用 rawProbabilities 会系统性低估 margin
        # 的效果（标定会锐化分布）。故优先取 probabilities，仅在缺失时回退 raw。
        raw = dec.get("probabilities") or dec.get("rawProbabilities")
        order = dec.get("optionOrder") or []
        if not raw or not order or len(raw) != len(order):
            continue
        acc = set(tasks[r["taskSha256"]].get("acceptableCards") or [])
        # 同一任务取最新一条（与 evaluate-routing.py 口径一致），避免同版本多次重跑被重复计数
        samples[r["taskSha256"]] = (raw, order, acc)
samples = list(samples.values())

def replay(raw, order, acc, margin):
    n = len(raw); none_i = n - 1
    best = max(range(n), key=lambda i: raw[i])
    if order[best].get("cardId") is None and margin > 0:
        bc = max(range(none_i), key=lambda i: raw[i])
        if raw[none_i] < raw[bc] + margin:
            best = bc
    card = order[best].get("cardId")
    hit = card is not None and card in acc
    leak = card is None and bool(acc)
    return hit, leak

print(f"样本 {len(samples)} 条（版本 {a.version}）")
best = None
for m in [x/100 for x in range(0, 61)]:
    hit = leak = 0
    for raw, order, acc in samples:
        h, l = replay(raw, order, acc, m)
        hit += h; leak += l
    acc_r = hit/len(samples)
    print(f"margin={m:.2f}  准确率={hit}/{len(samples)}={acc_r:.1%}  漏判={leak}")
    if best is None or (hit, -leak) > (best[1], -best[2]):
        best = (m, hit, leak)
print(f"\n建议 noneMargin = {best[0]:.2f}（准确率 {best[1]}/{len(samples)}，漏判 {best[2]}）")
