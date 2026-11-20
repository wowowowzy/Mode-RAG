#!/usr/bin/env python3
"""
候选池诊断（只读）：
1) acceptableCards 的域分布 —— 决定 P1 按域过滤白名单是否安全（P0.5）；
2) 候选池的域构成与正确卡排名 —— 验证 P1 过滤效果（前 3 命中率、候选数）；
3) 约束族覆盖率 —— P2 候选收缩的保守性验收。

在 ModeRouter 目录下运行：
  python tools/analyze-candidates.py --version <libraryVersion>
  python tools/analyze-candidates.py --version <libraryVersion> --tasks ../ModeTest/data/tasks/holdout-frozen.jsonl
"""
import argparse
import glob
import hashlib
import json
from collections import Counter, defaultdict
from pathlib import Path

import re

YAML_KEY = re.compile(r"^\s*family:\s*(\S+)\s*$", re.M)


def load_tasks(path):
    idx = {}
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        if line.strip():
            t = json.loads(line)
            idx[hashlib.sha256(t["prompt"].encode("utf-8")).hexdigest()] = t
    return idx


def load_audit(audit_dir, version):
    rows = {}
    for f in sorted(glob.glob(str(Path(audit_dir) / "audit-*.jsonl"))):
        for line in Path(f).read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            r = json.loads(line)
            if r.get("libraryVersion") == version:
                rows[r.get("taskSha256")] = r  # 同任务取最新
    return rows


def domain_of_card(card_id):
    return card_id.split("-", 1)[0] if card_id else "?"


def load_families(cards_dir):
    """从卡片 YAML 的 taxonomy.family 读取 family；P2 之前的文件没有该字段。"""
    families = {}
    for path in Path(cards_dir).rglob("*.yaml"):
        text = path.read_text(encoding="utf-8")
        match = YAML_KEY.search(text)
        card_id = path.stem
        families[card_id] = match.group(1) if match else ""
    return families


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--audit-dir", default="var/audit")
    ap.add_argument("--tasks", default="../ModeTest/data/tasks/tasks.jsonl")
    ap.add_argument("--cards-dir", default="data/cards")
    ap.add_argument("--version", required=True)
    ap.add_argument("--out", default="")
    args = ap.parse_args()

    tasks = load_tasks(args.tasks)
    audit = load_audit(args.audit_dir, args.version)
    families = load_families(args.cards_dir)

    acc_domain = defaultdict(Counter)      # task domain -> acceptable card domain -> count
    pool_domain = defaultdict(Counter)     # task domain -> candidate domain -> count
    pool_sizes = defaultdict(list)
    ranks = defaultdict(list)
    filtered_pools = Counter()
    family_hits = Counter()
    family_total = Counter()

    for h, t in tasks.items():
        if h not in audit:
            continue
        d = t["domain"]
        acceptable = t.get("acceptableCards") or []
        for card in acceptable:
            acc_domain[d][domain_of_card(card)] += 1

        rec = audit[h].get("recall") or {}
        candidates = [c["cardId"] for c in rec.get("candidates", [])]
        for card in candidates:
            pool_domain[d][domain_of_card(card)] += 1
        pool_sizes[d].append(len(candidates))
        if rec.get("domainFilter"):
            filtered_pools[(d, rec["domainFilter"])] += 1

        rank = next((i + 1 for i, c in enumerate(candidates) if c in acceptable), None)
        if rank is not None:
            ranks[d].append(rank)

        for card in candidates:
            fam = families.get(card, "")
            if fam:
                family_total[d] += 1
                if card in acceptable:
                    family_hits[d] += 1

    lines = []
    def p(s=""):
        print(s)
        lines.append(s)

    p(f"库版本 {args.version}  任务 {len(tasks)}  审计覆盖 {sum(1 for h in tasks if h in audit)}")
    p("\n[1] acceptableCards 的域分布（P0.5：决定 P1 过滤白名单）")
    for d in ("planning", "debate", "general"):
        if acc_domain[d]:
            p(f"  {d:10s} -> {dict(acc_domain[d])}")
    debate_general = acc_domain["debate"].get("general", 0)
    p(f"  结论: debate 任务出现 general 可接受卡 {debate_general} 次 -> "
      + ("P1 过滤需放宽为 debate+general" if debate_general else "P1 可只保留 debate 卡"))

    p("\n[2] 候选池域构成与正确卡排名（P1）")
    for d in ("planning", "debate"):
        sizes = pool_sizes[d]
        if not sizes:
            continue
        total = sum(pool_domain[d].values())
        share = {k: f"{v/total:.0%}" for k, v in pool_domain[d].most_common()}
        rr = ranks[d]
        top3 = sum(1 for r in rr if r <= 3)
        p(f"  {d:8s} 候选均 {sum(sizes)/len(sizes):.1f} 中位 {sorted(sizes)[len(sizes)//2]} "
          f"域占比 {share}  正确卡前3命中 {top3}/{len(rr)}={top3/max(1,len(rr)):.0%}")
    for key, n in sorted(filtered_pools.items()):
        p(f"  带 domainFilter 的请求: {key} -> {n}")

    p("\n[3] 约束族覆盖率（P2，需卡片 YAML 已含 taxonomy.family）")
    for d in ("planning", "debate"):
        if family_total[d]:
            p(f"  {d:8s} 候选中可识别 family {family_hits[d]}/{family_total[d]} "
              f"= {family_hits[d]/family_total[d]:.0%}")

    if args.out:
        Path(args.out).write_text("\n".join(lines), encoding="utf-8")
        print(f"\n已写出 {args.out}")


if __name__ == "__main__":
    main()
