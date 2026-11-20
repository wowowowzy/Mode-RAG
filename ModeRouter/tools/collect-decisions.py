#!/usr/bin/env python3
"""
对 tasks.jsonl 全量调 /v1/enhance，按 taskSha256 落盘进度，支持断点续跑。

真正的概率分布会写进 ModeRouter 的审计日志；本脚本只负责「触发 + 记录进度」，
因此可以随时中断，重跑会跳过已完成的条目。

用法（在 ModeRouter 目录下）：
  python tools/collect-decisions.py --topk-map "planning=8,debate=16"   # 跨域全量（推荐）
  python tools/collect-decisions.py --limit 5                            # 先试跑 5 条
  python tools/collect-decisions.py --tasks <holdout.jsonl> --topk-map "planning=8,debate=16"
"""
import argparse
import hashlib
import json
import sys
import time
import urllib.request
from pathlib import Path


def post(endpoint: str, task: str, topk: int = 8, timeout: int = 120) -> dict:
    body = json.dumps({"task": task, "options": {"topK": topk}},
                      ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        endpoint, data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--tasks", default="../ModeTest/data/tasks/tasks.jsonl")
    parser.add_argument("--endpoint", default="http://127.0.0.1:18080/v1/enhance")
    parser.add_argument("--out", default="var/fit/progress.jsonl")
    parser.add_argument("--sleep", type=float, default=0.0)
    parser.add_argument("--limit", type=int, default=0)
    # 召回候选池大小：客户端默认 planning=8 / debate=16，故通用默认取 8。
    # 注意：跨域采集必须传 --topk-map "planning=8,debate=16"，否则 debate 记录会被
    # 错误的 topK 污染，离线分析结论不可信（默认值只适用于单域 planning）。
    parser.add_argument("--topk", type=int, default=8,
                        help="单域候选池大小，默认 8；跨域采集请改用 --topk-map")
    # 按域覆盖：如 "planning=8,debate=16"。命中时优先于 --topk。
    parser.add_argument("--topk-map", default="",
                        help="按域指定候选池，如 'planning=8,debate=16'；跨域采集必传")
    args = parser.parse_args()

    topk_map = {}
    for item in filter(None, (x.strip() for x in args.topk_map.split(","))):
        domain, _, value = item.partition("=")
        topk_map[domain.strip()] = int(value)

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)

    done = set()
    if out.exists():
        for line in out.read_text(encoding="utf-8").splitlines():
            if line.strip():
                done.add(json.loads(line)["taskSha256"])
        print(f"已有进度 {len(done)} 条，将跳过", flush=True)

    tasks = [json.loads(line) for line in
             Path(args.tasks).read_text(encoding="utf-8").splitlines() if line.strip()]
    if args.limit:
        tasks = tasks[:args.limit]

    versions = set()
    started_all = time.time()
    ok = fail = 0
    with out.open("a", encoding="utf-8") as handle:
        for index, task in enumerate(tasks, 1):
            task_hash = hashlib.sha256(task["prompt"].encode("utf-8")).hexdigest()
            if task_hash in done:
                continue
            began = time.time()
            try:
                response = post(args.endpoint, task["prompt"],
                                topk_map.get(task.get("domain"), args.topk))
            except Exception as error:
                fail += 1
                print(f"[{index}/{len(tasks)}] FAIL {task['taskId']}: {error}",
                      file=sys.stderr, flush=True)
                continue
            meta = response.get("meta") or {}
            versions.add(meta.get("libraryVersion"))
            record = {
                "taskId": task["taskId"],
                "taskSha256": task_hash,
                "action": meta.get("action"),
                "cardId": meta.get("cardId"),
                "confidence": meta.get("confidence"),
                "libraryVersion": meta.get("libraryVersion"),
                "ms": int((time.time() - began) * 1000),
            }
            handle.write(json.dumps(record, ensure_ascii=False) + "\n")
            handle.flush()
            ok += 1
            print(f"[{index}/{len(tasks)}] {task['taskId']} {record['action']} "
                  f"{record['cardId']} {record['ms']}ms", flush=True)
            if args.sleep:
                time.sleep(args.sleep)

    elapsed = int(time.time() - started_all)
    print(f"\n完成 {ok} 条，失败 {fail} 条，用时 {elapsed}s，库版本集合: {versions}")
    if len(versions) > 1:
        print("警告：过程中库版本发生了变化，拟合前请确认版本一致", file=sys.stderr)


if __name__ == "__main__":
    main()
