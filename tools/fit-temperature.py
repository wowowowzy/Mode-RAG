#!/usr/bin/env python3
"""
从审计日志拟合温度 T。只读，不改服务。

方法：
  - 标签 y：argmax 落点是否在任务的 acceptableCards 里
  - 置信度：max(softmax(raw logits 经 T 缩放))
  - 目标：最小化验证集 ECE（15 个等宽 bin）
  - 切分：meta.split == "val" 拟合，== "test" 只报告

关键性质：温度缩放是保序变换，不改变 argmax，所以拟合 T 不影响任何
INJECT / FALLBACK 判定，只影响 meta.confidence 的数值。

用法（在 ModeRouter 目录下）：
  python tools/fit-temperature.py --version a4eaf1689a64
"""
import argparse
import hashlib
import json
from pathlib import Path


def load_audit(audit_dir: Path):
    rows = []
    for path in sorted(audit_dir.glob("audit-*.jsonl")):
        for line in path.read_text(encoding="utf-8").splitlines():
            if line.strip():
                rows.append(json.loads(line))
    return rows


def load_tasks(path: Path):
    index = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip():
            task = json.loads(line)
            index[hashlib.sha256(task["prompt"].encode("utf-8")).hexdigest()] = task
    return index


def rescale(probabilities, temperature):
    inverse = 1.0 / temperature
    powered = [max(0.0, p) ** inverse for p in probabilities]
    total = sum(powered)
    return [p / total for p in powered] if total > 0 else list(probabilities)


def ece(confidences, accuracies, bins=15):
    total = len(confidences)
    if total == 0:
        return float("nan")
    buckets = [[] for _ in range(bins)]
    for confidence, accuracy in zip(confidences, accuracies):
        index = min(int(confidence * bins), bins - 1)
        buckets[index].append((confidence, accuracy))
    result = 0.0
    for bucket in buckets:
        if not bucket:
            continue
        mean_confidence = sum(c for c, _ in bucket) / len(bucket)
        mean_accuracy = sum(a for _, a in bucket) / len(bucket)
        result += (len(bucket) / total) * abs(mean_accuracy - mean_confidence)
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--audit-dir", default="var/audit")
    parser.add_argument("--tasks", default="../ModeTest/data/tasks/tasks.jsonl")
    parser.add_argument("--version", required=True)
    parser.add_argument("--lo", type=float, default=0.5)
    parser.add_argument("--hi", type=float, default=3.0)
    parser.add_argument("--step", type=float, default=0.01)
    args = parser.parse_args()

    tasks = load_tasks(Path(args.tasks))
    samples = []
    skipped_no_prob = 0
    for row in load_audit(Path(args.audit_dir)):
        if row.get("libraryVersion") != args.version:
            continue
        task = tasks.get(row.get("taskSha256"))
        if task is None:
            continue
        decision = row.get("decision") or {}
        raw = decision.get("rawProbabilities")
        order = decision.get("optionOrder") or []
        if not raw or not order or len(raw) != len(order):
            skipped_no_prob += 1
            continue

        acceptable = set(task.get("acceptableCards") or [])
        best_index = max(range(len(raw)), key=lambda i: raw[i])
        best_card = order[best_index].get("cardId")
        if best_card is None:
            label = 1 if not acceptable else 0
        else:
            label = 1 if best_card in acceptable else 0

        samples.append({
            "raw": raw,
            "label": label,
            "split": (task.get("meta") or {}).get("split", ""),
            "action": (row.get("gate") or {}).get("action"),
        })

    print(f"可用样本 {len(samples)} 条（版本 {args.version}），跳过无概率记录 {skipped_no_prob} 条")
    for split in ("train", "val", "test"):
        subset = [s for s in samples if s["split"] == split]
        if subset:
            positives = sum(s["label"] for s in subset)
            print(f"  {split}: {len(subset)} 条，正例 {positives} ({positives / len(subset):.1%})")
    unlabeled = [s for s in samples if s["split"] not in ("train", "val", "test")]
    if unlabeled:
        print(f"  (未标注 split): {len(unlabeled)} 条")

    fit = [s for s in samples if s["split"] == "val"]
    if not fit:
        print("警告：没有 val 样本，退化为全量拟合（结论置信度较低）")
        fit = samples
    if not fit:
        print("没有可拟合样本，退出")
        return

    positives = sum(s["label"] for s in fit)
    ratio = positives / len(fit)
    print(f"\n拟合集 {len(fit)} 条，正例比例 {ratio:.1%}")

    best_temperature, best_ece = 1.0, float("inf")
    temperature = args.lo
    while temperature <= args.hi + 1e-9:
        confidences, accuracies = [], []
        for sample in fit:
            confidences.append(max(rescale(sample["raw"], temperature)))
            accuracies.append(sample["label"])
        score = ece(confidences, accuracies)
        if score < best_ece:
            best_temperature, best_ece = round(temperature, 2), score
        temperature += args.step

    print(f"最佳 T = {best_temperature}（val ECE = {best_ece:.4f}）")

    test = [s for s in samples if s["split"] == "test"]
    if test:
        for label, value in (("未标定 T=1.00", 1.0), (f"标定 T={best_temperature:.2f}", best_temperature)):
            confidences = [max(rescale(s["raw"], value)) for s in test]
            accuracies = [s["label"] for s in test]
            print(f"  test {label}: ECE = {ece(confidences, accuracies):.4f}（{len(test)} 条）")

    print("\n判定闸门：")
    if ratio < 0.15:
        print("  ✗ val 正例 < 15%，不建议落地 T —— 先查选卡质量（卡片 applicableWhen / 召回）")
    elif ratio < 0.40:
        print("  △ val 正例 15–40%，可落地但需在报告中注明「标签口径偏严」")
    else:
        print("  ✓ val 正例 ≥ 40%，可正常落地")

    print(f"\n若落地，把 application.yml 改成：")
    print(f"  temperature: ${{MODEROUTE_TEMPERATURE:{best_temperature}}}")


if __name__ == "__main__":
    main()
