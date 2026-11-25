"""Spending of the search bench: Refuseless tokens and dollars per model and variant (from the JSON lines the bench
writes), paid search calls, and the key's own counter from GET /v1/credits. The key comes from .env and is never printed.

python tools/bench_spend.py data/build/bench [out.md]
"""
import collections, glob, json, os, sys, urllib.request

root = sys.argv[1]
here = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
env = {}
for line in open(os.path.join(here, '.env'), encoding='utf-8'):
    if '=' in line and not line.lstrip().startswith('#'):
        k, v = line.split('=', 1)
        env[k.strip()] = v.strip().split()[0] if v.strip() else ''


def credits():
    req = urllib.request.Request('https://api.refuseless.com/v1/credits',
                                 headers={'Authorization': 'Bearer ' + env['REFUSELESS_API_KEY'], 'User-Agent': 'Kento-bench/0.6'})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


rows = [json.loads(l) for f in sorted(glob.glob(os.path.join(root, 'bench-research-*.jsonl')))
        for l in open(f, encoding='utf-8') if l.strip()]
sum_keys = ('input', 'cached', 'output', 'cost', 'rounds', 'paid', 'free', 'seconds')
by = collections.defaultdict(lambda: collections.Counter())
for r in rows:
    c = by[(r['model'], r['variant'])]
    c['answers'] += 1
    for k in sum_keys:
        c[k] += r[k]
total = collections.Counter()
lines = ['| Model | Variant | Answers | Requests | Input tokens | Cached | Output tokens | $ Refuseless | Paid searches | Free searches |',
         '|---|---|---|---|---|---|---|---|---|---|']
for (model, variant), c in sorted(by.items()):
    total.update(c)
    lines.append(f"| {model} | {variant} | {c['answers']} | {c['rounds']} | {c['input']:,} | {c['cached']:,} | {c['output']:,} | "
                 f"{c['cost']:.4f} | {c['paid']} | {c['free']} |")
lines.append(f"| **total** | | {total['answers']} | {total['rounds']} | {total['input']:,} | {total['cached']:,} | {total['output']:,} | "
             f"**{total['cost']:.4f}** | {total['paid']} | {total['free']} |")
try:
    c = credits()
    lines.append('')
    lines.append('Refuseless /v1/credits now: ' + ', '.join(f"{k}={c.get(k)}" for k in ('available', 'balance', 'reserved', 'key_spent', 'unit')))
except Exception as e:
    lines.append(f'Refuseless /v1/credits unavailable: {e}')
text = '\n'.join(lines)
print(text)
if len(sys.argv) > 2:
    open(sys.argv[2], 'w', encoding='utf-8').write(text + '\n')
