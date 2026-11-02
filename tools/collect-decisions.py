#!/usr/bin/env python3
"""
对 tasks.jsonl 全量调 /v1/enhance，按 taskSha256 落盘进度，支持断点续跑。

真正的概率分布会写进 ModeRouter 的审计日志；本脚本只负责「触发 + 记录进度」，
因此可以随时中断，重跑会跳过已完成的条目。

用法（在 ModeRouter 目录下）：
  python tools/collect-decisions.py
  python tools/collect-decisions.py --limit 5        # 先试跑 5 条
"""
import argparse
import hashlib
import json
import sys
import time
import urllib.request
from pathlib import Path


def post(endpoint: str, task: str, timeout: int = 120) -> dict:
    body = json.dumps({"task": task}, ensure_ascii=False).encode("utf-8")
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
    args = parser.parse_args()

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
                response = post(args.endpoint, task["prompt"])
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
