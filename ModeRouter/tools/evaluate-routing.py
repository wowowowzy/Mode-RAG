#!/usr/bin/env python3
"""
选卡质量评测：复算总体/分域/按 split 分组的准确率、召回/精判责任拆分、FALLBACK 漏判、卡选择分布。
正式报告统一使用严格口径（argmax 选中的卡 ∈ acceptableCards）；等价类口径仅作 debate 观察性附注。
只读，不改服务。在 ModeRouter 目录下运行：
  python tools/evaluate-routing.py --version <libraryVersion> [--tasks ../ModeTest/data/tasks/holdout-frozen.jsonl]
"""
import argparse
import glob
import hashlib
import json
from collections import Counter, defaultdict
from pathlib import Path


BUCKETS = ("planning", "debate", "general")


def empty_stat():
    return dict(n=0, recall=0, judge=0, inject=0, fallback=0, degraded=0, equiv=0)


def load_tasks(path):
    idx = {}
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        if line.strip():
            t = json.loads(line)
            idx[hashlib.sha256(t["prompt"].encode("utf-8")).hexdigest()] = t
    return idx


def load_audit(audit_dir, version):
    rows = []
    for f in sorted(glob.glob(str(Path(audit_dir) / "audit-*.jsonl"))):
        for line in Path(f).read_text(encoding="utf-8").splitlines():
            if line.strip():
                r = json.loads(line)
                if r.get("libraryVersion") == version:
                    rows.append(r)
    return rows


def load_equiv(path):
    """加载语义等价类：cardId -> 组名。仅用于放宽 debate 的标注任意性。"""
    if not path:
        return {}
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    mapping = {}
    for group, cards in data.items():
        for card in cards:
            mapping[card] = group
    return mapping


def same_equiv_group(card, acceptable, equiv):
    group = equiv.get(card)
    return group is not None and any(equiv.get(a) == group for a in acceptable)


def split_of(task):
    return (task.get("meta") or {}).get("split") or "unspecified"


def render_stat(label, s):
    n = s["n"]
    return (f"[{label}] n={n} 召回命中={s['recall']}({s['recall']/max(1,n):.0%}) "
            f"精判命中={s['judge']}({s['judge']/max(1,n):.0%}) "
            f"INJECT={s['inject']} FALLBACK={s['fallback']} DEGRADED={s['degraded']}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--audit-dir", default="var/audit")
    ap.add_argument("--tasks", default="../ModeTest/data/tasks/tasks.jsonl",
                    help="任务集；用 holdout-frozen.jsonl 可单独评测独立集")
    ap.add_argument("--version", required=True)
    ap.add_argument("--out", default="")
    ap.add_argument("--split", default="",
                    help="只统计某个 split（train/val/test/holdout），默认全部")
    ap.add_argument("--equiv", default="",
                    help="语义等价类映射 JSON（如 tools/debate-equivalence.json），仅放宽 debate 口径")
    args = ap.parse_args()

    tasks = load_tasks(args.tasks)
    audit = load_audit(args.audit_dir, args.version)
    equiv = load_equiv(args.equiv)

    by_task = {}
    for r in audit:
        h = r.get("taskSha256")
        if h in tasks:
            by_task[h] = r

    stat = defaultdict(empty_stat)
    by_split = defaultdict(empty_stat)
    chosen = Counter()
    mutex_mis = []
    fallback_leak = []
    total = dict(n=0, judge=0, equiv=0)

    for h, t in tasks.items():
        if h not in by_task:
            continue
        split = split_of(t)
        if args.split and split != args.split:
            continue
        r = by_task[h]
        d = t["domain"]
        acc = set(t.get("acceptableCards") or [])
        cand = set(c["cardId"] for c in r["recall"]["candidates"])
        gate = r["gate"]
        action = gate["action"]
        card = gate.get("cardId")
        recall_ok = bool(acc & cand)
        judge_ok = action == "INJECT" and card in acc
        equiv_ok = judge_ok or (
            d == "debate" and action == "INJECT" and card is not None
            and same_equiv_group(card, acc, equiv))

        values = dict(recall=recall_ok, judge=judge_ok, equiv=equiv_ok,
                      inject=action == "INJECT", fallback=action == "FALLBACK",
                      degraded=action == "DEGRADED")
        for bucket in (stat[d], stat["TOTAL"], by_split[split]):
            bucket["n"] += 1
            for key, value in values.items():
                bucket[key] += value
        total["n"] += 1
        total["judge"] += judge_ok
        total["equiv"] += equiv_ok
        if card:
            chosen[card] += 1
        if action == "FALLBACK" and acc:
            fallback_leak.append(t["taskId"])
        if card == "planning-mutex-serialize" and card not in acc:
            mutex_mis.append(t["taskId"])

    lines = []
    def p(s=""):
        print(s)
        lines.append(s)

    p(f"库版本 {args.version}  覆盖任务 {total['n']}/{len(tasks)}"
      + (f"（仅 split={args.split}）" if args.split else ""))
    p(f"总体选卡准确率（严格）: {total['judge']}/{total['n']} = {total['judge']/max(1,total['n']):.1%}")

    p("按 split 分组:")
    for split in sorted(by_split):
        p("  " + render_stat(split, by_split[split]))

    p("按域:")
    for d in BUCKETS:
        if stat[d]["n"]:
            p("  " + render_stat(d, stat[d]))

    if equiv:
        p("—— 等价类口径（仅用于观察 debate 标注任意性，正式报告用严格口径）——")
        p(f"总体等价类准确率: {total['equiv']}/{total['n']} = {total['equiv']/max(1,total['n']):.1%}"
          f"（严格口径 {total['judge']}/{total['n']}）")
        for d in BUCKETS:
            s = stat[d]
            if s["n"]:
                p(f"[{d}] 等价类命中={s['equiv']}({s['equiv']/s['n']:.0%}) "
                  f"严格命中={s['judge']}({s['judge']/s['n']:.0%})")
    p(f"FALLBACK 漏判（有可接受卡却回退）: {len(fallback_leak)} 条 -> {fallback_leak[:20]}")
    p(f"mutex-serialize 误选: {len(mutex_mis)} 条 -> {mutex_mis[:20]}")
    p("卡选择分布 Top10:")
    for c, n in chosen.most_common(10):
        p(f"   {c:34s} {n}")
    p(f"被选过的卡数: {len(chosen)}/48")

    if args.out:
        Path(args.out).write_text("\n".join(lines), encoding="utf-8")
        print(f"\n已写出 {args.out}")


if __name__ == "__main__":
    main()
