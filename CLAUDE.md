# CLAUDE.md

Guidance for Claude Code (and humans) working in this repository.

## What this is

**Poker Pro** is a native Android app that shows your probability of winning a
Texas Hold'em hand *preflop*. You pick your two hole cards and the total number
of players (2–6); the app answers with a **single database lookup** of
precomputed equities — it does **no** poker math at runtime.

## Tech stack

- **Language:** Kotlin · **UI:** Jetpack Compose (Material 3)
- **Architecture:** single-Activity, `AndroidViewModel` + `StateFlow`
- **Data:** read-only SQLite asset (`poker_equity.db`)
- **Build:** Gradle (Kotlin DSL) with a version catalog (`gradle/libs.versions.toml`)
- **Min SDK:** 26 · **Target/Compile SDK:** 34 · **JDK:** 17

## Project layout

```
app/src/main/java/com/pokerstats/odds/
├── engine/                 # Card model only — NO poker math
│   ├── Card.kt             # Rank, Suit, Card (0..51 index), full deck
│   └── HandCategory.kt     # the 9 categories (names match the DB's JSON keys)
├── data/
│   └── EquityDatabase.kt   # Copies the asset DB out, answers lookups (hand class, players)
├── update/
│   └── UpdateManager.kt    # Checks GitHub Releases, downloads/installs APK, cleans up
├── ui/
│   ├── PokerViewModel.kt · PokerScreen.kt · theme/
│   └── ...
└── MainActivity.kt
app/src/main/assets/poker_equity.db   # Precomputed equities (169 hands × players 2–6 = 845 rows)
tools/                                 # C + Python generator for poker_equity.db (see tools/README.md)
```

## Precomputed equities (the DB)

- The app is a pure lookup: `EquityDatabase.lookup(cardA, cardB, players)` maps
  the two cards to a canonical class (`AA`, `AKs`, `AKo`, `72o`, …) and reads
  one indexed row. **No poker math on device — there is no Kotlin evaluator.**
- The DB stores `win`, `tie`, `lose` and a hand-category distribution per cell.
  `win` is the sole-win rate; `tie` is **split-pot equity** (a k-way tie counts
  `1/k`, not a whole win); `lose = 1 - win - tie`. The UI shows the hero's
  equity, `win + tie`.
- Two fold-adjusted equities ride alongside (same 169×5 rows, `user_version 3`):
  - **`win_fold`/`tie_fold`** — **Pre-flop fold**: opponents (and the hero) fold
    hands whose equity is below break-even `1/players`; the field is thinned once
    before showdown.
  - **`post_fold_win`/`post_fold_tie`** — **Post-flop fold**: pre-flop survivors
    also fold weak *flops* (bucketed flop equity below the shrunk break-even
    `1/remaining`), the hero included, averaged over every flop. A hand the hero
    folds pre-flop has no post-flop equity (0). The UI shows three ascending
    splits: **Win Probability → Pre-flop fold → Post-flop fold** (post-flop can
    dip below pre-flop for drawing hands, since the hero folds the flops it
    misses).
- `EquityDatabase` copies the asset to the app's databases dir on first use and
  recopies if the asset's `PRAGMA user_version` changes.

## The equity generator (C + Python, in `tools/`)

The numbers are computed **offline**, never in the app:

- `tools/equity.c` — an OpenMP-parallel Monte-Carlo simulator. Its 7-card
  evaluator scores a hand directly from rank/suit counts into one comparable
  int (category in the top bits, kicker nibbles below); handles the wheel.
  `./equity --selftest` cross-checks it against a brute-force best-of-21
  reference over 300k random hands. It deals whole random 6-max tables and
  records **every** player's outcome at once (aggregating into the 169 classes);
  each table is **reused as a nested k-player game** for every table size (2..6)
  by taking players `0..k-1` and the shared board, so the six hands are ranked
  once and serve all sizes. It also reuses each shuffle for several disjoint
  games and reduces with integer accumulators, so output is deterministic
  regardless of thread count. The `target` argument (default 1,000,000) is the
  sample count for the rarest class; commoner classes get proportionally more.
  It runs three passes over reused deals: (1) raw preflop equity — which also
  fills a RAM-only bucketed flop-equity table (made 5-card category × flush-draw
  × straight-draw, relative to the hand) used only to decide post-flop folds;
  (2) the **Pre-flop fold** table; and (3) the **Post-flop fold** table via
  `run_postfold` (two fold rounds — pre-flop `equity < 1/k`, then post-flop
  `bucketed flop equity < 1/remaining` — then showdown among survivors, recorded
  per pre-flop survivor and averaged over flops). The bucketed table is never
  shipped, so there is no Kotlin flop math and no parity risk.
- `tools/build_equity_db.py` packs the generator's TSV into the SQLite asset;
  `generate_db.py` / `make db` runs the whole pipeline.
- `tools/test_equity.py` (`make test`) pins canonical equities (`AA` heads-up
  ≈ 85%, `AA` 6-handed ≈ 49%, `72o` worst), checks the fold invariants
  (pre-flop folders have zero post-flop equity; premiums gain from the thinned
  field), and runs the C self-test.
- **If you change the evaluator, keep `make test` green and regenerate the DB.**
  The `HandCategory` enum names in the app must stay in sync with the category
  keys emitted by `equity.c`.

## Versioning: `major.minor.build`

Computed in `app/build.gradle.kts` at build time:

- `major` = current year − 2025
- `minor` = ISO week of the year
- `build` = commits in the current ISO week + 1 (resets weekly, grows per build);
  override with the `BUILD_VERSION` env var.

For local/PR builds `versionCode = (major*100 + minor)*1000 + build` (monotonic);
CI checks out with `fetch-depth: 0` so the weekly commit count is accurate. The
rolling release overrides both `versionName`/`versionCode` via env vars (see
below).

On the **rolling release** (`release.yml`, on every push to `main`), the
workflow sets `VERSION_CODE = 1_000_000 + <run number>` and a matching
`VERSION_NAME = <major>.<minor>.<run number>`, pinning the published APK's
version. The `versionCode` is monotonic and always above older date-based
installs (e.g. `130002`), so each new build is offered as an update. Debug/PR
builds keep the date-based computation. **No git tags — just merge to `main`.**

## In-app auto-update (tagless rolling release)

Every push to `main` publishes the signed APK plus a `version.json`
(`{ "versionCode": N, "versionName": "a.b.c" }`) to a single fixed GitHub
release tagged **`poker-latest`**, clobbering the previous assets. The download
URLs are therefore constant:

- `releases/download/poker-latest/poker-odds.apk`
- `releases/download/poker-latest/version.json`

`UpdateManager` (banner wired in `PokerScreen`/`PokerViewModel`):

1. On launch **and every time the app returns to the foreground**,
   `cleanupOldDownloads` deletes any downloaded APK ≤ the current
   `BuildConfig.VERSION_CODE`, then `checkForUpdate` GETs `version.json` and
   compares its integer `versionCode` against the installed build. No tags, no
   release enumeration — one unauthenticated request answers "is there an
   update?".
2. If newer, a top banner appears. Tapping downloads `poker-odds.apk` to
   `filesDir/updates/poker-odds-<versionCode>.apk` — **once per version** (an
   existing file is reused).
3. Installs via `FileProvider` + the system package installer, requesting
   "install unknown apps" if needed.

Requires `INTERNET` + `REQUEST_INSTALL_PACKAGES` and a `FileProvider`
(`res/xml/file_paths.xml`). `UPDATE_REPO` is a `BuildConfig` field.

## Common commands

```bash
./gradlew :app:assembleDebug   # debug APK
./gradlew :app:assembleRelease # signed release APK (needs signing config)
./gradlew lint
cd tools && make test          # equity generator: C self-test + pytest
cd tools && make db            # regenerate the SQLite asset (1M deals/cell)
```

## Signing & release

Release signing is driven by secrets (env vars, then optional local
`keystore.properties`): `KEYSTORE_FILE`/`storeFile`,
`KEYSTORE_PASSWORD`/`storePassword`, `KEY_ALIAS`/`keyAlias`,
`KEY_PASSWORD`/`keyPassword`. If absent, the release build is unsigned. See
`.github/workflows/release.yml`.

## CI / CD (GitHub Actions)

- **`ci.yml`** — push/PR: lint + a **signed release** APK artifact (same build type
  that ships), `fetch-depth: 0`.
- **`equity-tools.yml`** — push/PR touching `tools/**`: builds the C generator and runs its tests.
- **`release.yml`** — push to `main` (tagless rolling release): signed APK + `version.json` clobbered onto the fixed `poker-latest` GitHub Release.
- **`pages.yml`** — deploys `docs/` (download page) to GitHub Pages.

## Conventions

- `engine/` holds only the card model + category enum — no Android imports, no
  poker math. All hand evaluation lives in `tools/equity.c`.
- UI state flows one way: Compose reads `PokerViewModel.uiState`; actions emit a
  new immutable `PokerUiState`.
- The app must not compute equities at runtime — regenerate the DB instead.
