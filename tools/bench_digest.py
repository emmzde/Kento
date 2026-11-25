"""Page digests on the search bench: with (`base`) and without (`nodigest`) digests.

Usage: python tools/bench_digest.py <bench folder> [<bench folder> ...]
Reads bench-research-<model>-<variant>.jsonl of SearchBenchLiveTest and prints, per model and variant, the answers,
rounds, the input of the last round, total input, digests (count, fallbacks, ms) and dollars of the chat model and the
background model. Answers with at least one digest are also compared with the same question without digests.
"""
import json
import pathlib
import statistics
import sys
from collections import defaultdict


def rows(folders):
    for folder in folders:
        for path in sorted(pathlib.Path(folder).glob('bench-research-*.jsonl')):
            for line in path.read_text(encoding='utf-8').splitlines():
                if line.strip():
                    yield json.loads(line)


def mean(values):
    values = list(values)
    return statistics.mean(values) if values else 0


def main(folders):
    data = list(rows(folders))
    groups = defaultdict(list)
    for r in data:
        groups[(r['model'], r['variant'].split('-')[0])].append(r)
    print('| Model | Variant | Answers | Rounds | Last-round input | Input | Reads | Digests (fallback) | Digest ms mean / max | $ chat | $ digest | $ total | s |')
    print('|---|---|---|---|---|---|---|---|---|---|---|---|---|')
    for (model, variant), rs in sorted(groups.items()):
        runs = [d for r in rs for d in r.get('digestRuns', [])]
        ms = [d['ms'] for d in runs if not d['fallback']]
        chat = sum(r['cost'] for r in rs)
        digest = sum(r.get('digestCost', 0) for r in rs)
        print(f"| {model} | {variant} | {len(rs)} | {mean(r['rounds'] for r in rs):.2f} | {mean(r.get('lastInput', 0) for r in rs):.0f} | "
              f"{mean(r['input'] for r in rs):.0f} | {sum(r['reads'] for r in rs)} | {len(runs)} ({sum(d['fallback'] for d in runs)}) | "
              f"{mean(ms):.0f} / {max(ms) if ms else 0} | {chat:.4f} | {digest:.4f} | {chat + digest:.4f} | {mean(r['seconds'] for r in rs):.0f} |")
    # The same question of the same model with and without digests, for answers where a digest ran.
    print()
    print('| Model | Question | Last-round input with digests | without | change | digests |')
    print('|---|---|---|---|---|---|')
    by = defaultdict(lambda: defaultdict(list))
    for r in data:
        by[(r['model'], r['question'])][r['variant'].split('-')[0]].append(r)
    changes = defaultdict(list)
    for (model, question), variants in sorted(by.items()):
        with_digest = [r for r in variants.get('base', []) if r.get('digests', 0) > 0]
        without = variants.get('nodigest', [])
        if not with_digest or not without:
            continue
        a = mean(r['lastInput'] for r in with_digest)
        b = mean(r['lastInput'] for r in without)
        change = (a - b) / b * 100 if b else 0
        changes[model].append(change)
        print(f"| {model} | {question[:60]} | {a:.0f} | {b:.0f} | {change:+.0f}% | {sum(r['digests'] for r in with_digest)} |")
    for model, values in changes.items():
        print(f"\n{model}: mean change of the last-round input where digests ran: {mean(values):+.1f}% over {len(values)} questions")


if __name__ == '__main__':
    main(sys.argv[1:])
