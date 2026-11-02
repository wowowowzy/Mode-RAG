#!/usr/bin/env python3
"""
按库版本 / 日期筛选审计日志并输出统计。

跨版本记录不可合并统计：不同 libraryVersion 对应不同卡片库，决策行为不可直接比较。

用法（在 ModeRouter 目录下）：
  python tools/audit-filter.py --version a4eaf1689a64
  python tools/audit-filter.py --since 2026-11-01 --json
"""
import argparse
import collections
import json
from pathlib import Path


def load(audit_dir: Path):
    for path in sorted(audit_dir.glob("audit-*.jsonl")):
        for line in path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line:
                yield json.loads(line)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--audit-dir", default="var/audit")
    parser.add_argument("--version", help="只保留指定 libraryVersion")
    parser.add_argument("--since", help="只保留该日期及之后的记录（YYYY-MM-DD）")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    rows = list(load(Path(args.audit_dir)))
    if args.version:
        rows = [r for r in rows if r.get("libraryVersion") == args.version]
    if args.since:
        rows = [r for r in rows if (r.get("ts") or "")[:10] >= args.since]

    stat = {
        "records": len(rows),
        "uniqueTasks": len({r.get("taskSha256") for r in rows}),
        "byVersion": dict(collections.Counter(r.get("libraryVersion") for r in rows)),
        "byAction": dict(collections.Counter((r.get("gate") or {}).get("action") for r in rows)),
        "byEngine": dict(collections.Counter((r.get("decision") or {}).get("engine") for r in rows)),
        "byTemperature": dict(collections.Counter(
            str((r.get("decision") or {}).get("temperature")) for r in rows)),
        "legacy": {
            "budgetTokens": sum(1 for r in rows if "budgetTokens" in r),
        },
    }

    if args.json:
        print(json.dumps(stat, ensure_ascii=False, indent=2))
    else:
        for key, value in stat.items():
            print(f"{key}: {value}")


if __name__ == "__main__":
    main()
