"""Sums up a ConversationBenchLiveTest run: per variant the cache share, dollars, time and tokens of each turn.

    python tools/bench_conversation.py data/build/conversation
"""
import glob
import json
import os
import statistics
import sys

folder = sys.argv[1] if len(sys.argv) > 1 else "build/conversation"
for path in sorted(glob.glob(os.path.join(folder, "turns-*.jsonl"))):
    rows = [json.loads(line) for line in open(path, encoding="utf-8") if line.strip()]
    main = [r for r in rows if r["chat"] == "main"]
    if not main:
        continue
    name = os.path.basename(path)[len("turns-"):-len(".jsonl")]
    total_in = sum(r["input"] for r in main)
    cached = sum(r["cached"] for r in main)
    cost = sum(r["cost"] for r in main)
    print(f"## {name}: {len(main)} turns")
    print(f"- input {total_in} tokens, cached {cached} ({100 * cached / max(total_in, 1):.0f}%), output {sum(r['output'] for r in main)}"
          f" (reasoning {sum(r['reasoning'] for r in main)})")
    print(f"- ${cost:.4f} in all, ${cost / len(main):.5f} per answer; requests {sum(r['requests'] for r in main)}")
    answer = [r["answer_ms"] / 1000 for r in main if r["answer_ms"] >= 0]
    total = [r["total_ms"] / 1000 for r in main]
    first = [r["first_ms"] / 1000 for r in main if r["first_ms"] >= 0]
    print(f"- first token median {statistics.median(first):.1f} s; first answer word median {statistics.median(answer):.0f} s,"
          f" max {max(answer):.0f} s; whole answer median {statistics.median(total):.0f} s, max {max(total):.0f} s")
    print(f"- largest output of one turn {max(r['output'] for r in main)}; errors: {[r['turn'] for r in main if r['error']]}")
    tools = [t for r in main for t in r.get("tools", [])]
    print(f"- tool calls: {', '.join(f'{t} x{tools.count(t)}' for t in sorted(set(tools))) or 'none'}")
    print("| turn | input | cached | out | thinking | $ | first word, s | all, s | tools |")
    print("|---|---|---|---|---|---|---|---|---|")
    for r in rows:
        tag = r["turn"] if r["chat"] == "main" else "new chat"
        print(f"| {tag} | {r['input']} | {r['cached']} | {r['output']} | {r['reasoning']} | {r['cost']:.5f} | "
              f"{r['answer_ms'] / 1000:.0f} | {r['total_ms'] / 1000:.0f} | {', '.join(r.get('tools', []))} |")
    print()
extra = sorted(glob.glob(os.path.join(folder, "extra-*.txt")))
for path in extra:
    print(os.path.basename(path), open(path, encoding="utf-8").read().strip().replace("\n", "; "))
spent = os.path.join(folder, "spent.txt")
if os.path.exists(spent):
    print(open(spent, encoding="utf-8").read().strip())
