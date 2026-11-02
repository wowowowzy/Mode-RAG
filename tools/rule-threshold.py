#!/usr/bin/env python3
"""
对任务集 × 卡片库算字符二元组余弦，输出「每题最高分」的分布，用于定 rule-min-score。

用法（在 ModeRouter 目录下）：
  python tools/rule-threshold.py data/cards ../ModeTest/data/tasks/tasks.jsonl
"""
import json
import math
import re
import sys
from pathlib import Path


def bigrams(text: str):
    filtered = "".join(ch.lower() for ch in text if ch.isalnum())
    if not filtered:
        return set()
    if len(filtered) == 1:
        return {filtered}
    return {filtered[i:i + 2] for i in range(len(filtered) - 1)}


def cosine(a, b):
    if not a or not b:
        return 0.0
    return len(a & b) / (math.sqrt(len(a)) * math.sqrt(len(b)))


def positive_applicable(text: str) -> str:
    """与 Card.positiveApplicableWhen 保持一致：截到「不适用于」之前。"""
    idx = text.find("不适用于")
    if idx <= 0:
        return text.strip()
    return text[:idx].strip(" ；;，,。、\n\r\t")


def main():
    cards_dir = Path(sys.argv[1] if len(sys.argv) > 1 else "data/cards")
    tasks_file = Path(sys.argv[2] if len(sys.argv) > 2 else "../ModeTest/data/tasks/tasks.jsonl")

    cards = []
    for path in cards_dir.rglob("*.yaml"):
        text = path.read_text(encoding="utf-8")
        match = re.search(r"^applicableWhen:\s*(.*)$", text, re.M)
        if match:
            cards.append(positive_applicable(match.group(1).strip()))
    print(f"卡片 {len(cards)} 张", file=sys.stderr)

    scored = []
    for line in tasks_file.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        task = json.loads(line)
        grams = bigrams(task["prompt"])
        best = max((cosine(grams, bigrams(c)) for c in cards), default=0.0)
        scored.append(best)

    scored.sort()
    n = len(scored)
    print(f"任务 {n} 条，最高相似度分布：")
    for p in (10, 25, 50, 75, 90):
        print(f"  P{p:<2} = {scored[int(n * p / 100)]:.4f}")
    print(f"  max = {scored[-1]:.4f}  min = {scored[0]:.4f}")
    print()
    print("阈值 → 回退比例：")
    for threshold in (0.02, 0.05, 0.08, 0.10, 0.12, 0.15, 0.20):
        fallback = sum(1 for s in scored if s <= threshold)
        print(f"  {threshold:.2f} → {fallback}/{n} = {fallback / n:.1%}")


if __name__ == "__main__":
    main()
