# Equity table generator (C + Python)

The app does **no** poker math at runtime — it looks up precomputed preflop
equities from a bundled SQLite database
(`app/src/main/assets/poker_equity.db`). This directory regenerates that
database. The heavy Monte-Carlo runs in **C** (`equity.c`); **Python** builds
the SQLite file and holds the tests.

## What's in the DB

Table `equity`, one row per (starting hand × total players):

| column       | meaning                                                        |
|--------------|----------------------------------------------------------------|
| `hand_class` | canonical starting hand, e.g. `AA`, `AKs`, `AKo`, `72o` (169)  |
| `players`    | total players at the table, 2–6                                |
| `win`        | probability the hand wins outright (0..1)                      |
| `tie`        | probability of a split pot                                     |
| `lose`       | probability of losing                                          |
| `categories` | JSON: hero's final-hand-category distribution                  |

169 hands × 5 player counts = **845 rows**. The app displays win probability
with **ties counted as wins** (`win + tie`); storing them separately keeps that
a display choice.

## Files

- `equity.c` — the evaluator + Monte-Carlo simulator (OpenMP-parallel). A
  7-card hand is scored directly from rank/suit counts; `--selftest`
  cross-checks it against a brute-force best-of-21 reference over 300k random
  hands plus a set of known hands.
- `build_equity_db.py` — packs the generator's TSV into the SQLite asset.
- `generate_db.py` — one-shot: compile → self-test → simulate → build DB.
- `test_equity.py` — `pytest` suite (self-test + canonical equities).
- `Makefile` — `make`, `make selftest`, `make test`, `make db`.

## Regenerating the DB

Needs a C compiler with OpenMP (gcc/clang) and Python 3.

```bash
cd tools
make db                 # 1,000,000 deals/cell -> app/src/main/assets/poker_equity.db
make db TRIALS=300000   # faster, noisier
# or, pure-Python driver:
python3 generate_db.py --trials 1000000
```

The default 1M deals/cell gives ≈ ±0.1 pp Monte-Carlo noise. Canonical sanity
checks the output reproduces: `AA` heads-up ≈ 85%, `AA` 6-handed ≈ 49%,
`72o` heads-up ≈ 32% outright win.

## Testing

```bash
cd tools
make test        # C self-test + pytest
```

The `equity-tools` GitHub Actions workflow runs the same on every change under
`tools/`.
