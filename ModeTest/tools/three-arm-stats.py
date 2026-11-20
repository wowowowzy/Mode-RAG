#!/usr/bin/env python3
"""三臂实验统计（P4）。只读，不调服务。

主指标：
- planning：约束全过率（passed == total），二分类；
- debate：LLM-judge 质量分（0–5），连续。

必须报告 oracle−baseline（骨架效应）、system−oracle（选卡损失）、system−baseline（净效应），
每项给出 95% bootstrap CI，不只给均值。禁止用 parsed=false 当"答案错误"。

  python tools/three-arm-stats.py
  python tools/three-arm-stats.py --in var/three-arm/results.jsonl --bootstrap 4000
"""
import argparse
import json
import random
from collections import defaultdict
from pathlib import Path

ap = argparse.ArgumentParser()
ap.add_argument("--in", dest="inp", default="var/three-arm/results.jsonl")
ap.add_argument("--domain", default="")
ap.add_argument("--bootstrap", type=int, default=2000)
ap.add_argument("--seed", type=int, default=20261120)
a = ap.parse_args()

rows = [json.loads(l) for l in Path(a.inp).read_text(encoding="utf-8").splitlines() if l.strip()]
if a.domain:
    rows = [r for r in rows if r["domain"] == a.domain]

# taskId -> arm -> row（同任务同臂取最后一条）
by_task = defaultdict(dict)
for r in rows:
    by_task[(r["domain"], r["taskId"])][r["arm"]] = r

ARMS = ("baseline", "oracle", "system")


def metric(r):
    if r["domain"] == "planning":
        if r.get("parsed") is False:
            return None  # 格式不合规，不计入质量统计，单独报告
        if r.get("total") is None or r.get("passed") is None:
            return None
        return 1.0 if r["passed"] == r["total"] else 0.0
    return r.get("score")


def mean(xs):
    return sum(xs) / len(xs) if xs else float("nan")


def ci(samples, seed):
    if len(samples) < 2:
        return (float("nan"), float("nan"))
    rng = random.Random(seed)
    boot = []
    n = len(samples)
    for _ in range(a.bootstrap):
        boot.append(mean([samples[rng.randrange(n)] for _ in range(n)]))
    boot.sort()
    lo = boot[int(0.025 * len(boot))]
    hi = boot[min(len(boot) - 1, int(0.975 * len(boot)))]
    return lo, hi


for domain in ("planning", "debate"):
    tasks = [t for (d, _), t in by_task.items() if d == domain]
    complete = [t for t in tasks if all(arm in t for arm in ARMS)]
    if not complete:
        continue
    print(f"\n=== {domain}: 总任务 {len(tasks)}，三臂齐全 {len(complete)} ===")

    # unparsed 单独报告，不混进均值
    unparsed = sum(1 for t in complete for arm in ARMS if t[arm].get("parsed") is False)
    print(f"parsed=false（格式不合规，不视为答案错误）: {unparsed}")

    values = {arm: [metric(t[arm]) for t in complete] for arm in ARMS}
    for arm in ARMS:
        xs = [x for x in values[arm] if x is not None]
        lo, hi = ci(xs, a.seed + ARMS.index(arm))
        suffix = "约束全过率" if domain == "planning" else "judge 均分"
        print(f"  {arm:9s} {suffix}={mean(xs):.3f}  95%CI=[{lo:.3f}, {hi:.3f}]  n={len(xs)}")

    pairs = []
    for t in complete:
        m = {arm: metric(t[arm]) for arm in ARMS}
        if any(v is None for v in m.values()):
            continue
        pairs.append(m)
    if not pairs:
        continue
    print("  效应分解（配对，bootstrap 95% CI）:")
    diffs = {
        "oracle − baseline（骨架效应）": ("oracle", "baseline"),
        "system − oracle（选卡损失）": ("system", "oracle"),
        "system − baseline（端到端净效应）": ("system", "baseline"),
    }
    for label, (left, right) in diffs.items():
        samples = [m[left] - m[right] for m in pairs]
        lo, hi = ci(samples, a.seed + len(label))
        print(f"    {label:32s} Δ={mean(samples):+.3f}  95%CI=[{lo:+.3f}, {hi:+.3f}]")

print("\n注意：样本量 <30/域 时以上区间仅供观察，不得据此下结论；"
      "parsed=false 记录已排除，不得当作'答案错误'。")
