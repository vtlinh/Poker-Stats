# Equity table generator

The app does **no** poker math at runtime — it looks up precomputed preflop
equities from a bundled SQLite database
(`app/src/main/assets/poker_equity.db`). This directory regenerates that
database.

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

169 hands × 5 player counts = **845 rows**.

## Regenerating

The generator reuses the app's poker engine and Monte-Carlos 300k deals per
cell. It is deliberately **not** wired into the Gradle build (the DB is
generated once and committed).

With a JVM + the Kotlin compiler available:

```bash
# 1. Compile the engine + generator (engine sources are pure Kotlin/JVM)
kotlinc app/src/main/java/com/pokerstats/odds/engine/*.kt \
        tools/GenerateEquityDb.kt -include-runtime -d gen.jar

# 2. Run it -> writes equity.tsv (~1 min, multi-threaded)
java -jar gen.jar

# 3. Build the SQLite asset from the TSV
python3 tools/build_equity_db.py equity.tsv app/src/main/assets/poker_equity.db
```

The equities are Monte-Carlo estimates (±~0.2%). Canonical sanity checks the
output should reproduce: `AA` heads-up ≈ 85% win, `AA` 6-handed ≈ 49%,
`72o` heads-up ≈ 32% win.
