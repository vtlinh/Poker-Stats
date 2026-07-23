# CLAUDE.md

Guidance for Claude Code (and humans) working in this repository.

## What this is

**Poker Odds** is a native Android app that shows your probability of winning a
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
  The UI shows win probability with **ties counted as wins** (`win + tie`).
- `EquityDatabase` copies the asset to the app's databases dir on first use and
  recopies if the asset's `PRAGMA user_version` changes.

## The equity generator (C + Python, in `tools/`)

The numbers are computed **offline**, never in the app:

- `tools/equity.c` — an OpenMP-parallel Monte-Carlo simulator. Its 7-card
  evaluator scores a hand directly from rank/suit counts into one comparable
  int (category in the top bits, kicker nibbles below); handles the wheel.
  `./equity --selftest` cross-checks it against a brute-force best-of-21
  reference over 300k random hands. Default 1,000,000 deals/cell.
- `tools/build_equity_db.py` packs the generator's TSV into the SQLite asset;
  `generate_db.py` / `make db` runs the whole pipeline.
- `tools/test_equity.py` (`make test`) pins canonical equities (`AA` heads-up
  ≈ 85%, `AA` 6-handed ≈ 49%, `72o` worst) and runs the C self-test.
- **If you change the evaluator, keep `make test` green and regenerate the DB.**
  The `HandCategory` enum names in the app must stay in sync with the category
  keys emitted by `equity.c`.

## Versioning: `major.minor.build`

Computed in `app/build.gradle.kts` at build time:

- `major` = current year − 2025
- `minor` = ISO week of the year
- `build` = commits in the current ISO week + 1 (resets weekly, grows per build);
  override with the `BUILD_VERSION` env var.

`versionCode = (major*100 + minor)*1000 + build` (monotonic). CI checks out with
`fetch-depth: 0` so the weekly commit count is accurate.

On a **release** build, `release.yml` sets `RELEASE_VERSION_NAME` from the git
tag, which pins the APK's `versionName`/`versionCode` to exactly the tag (e.g.
tag `v1.30.2` → app version `1.30.2`). This keeps the in-app updater — which
compares the release tag against the installed app's self-reported version —
always consistent. Debug/CI builds keep the date-based computation. So: **tag
each release `v<major>.<minor>.<build>` with an incrementing number.**

## In-app auto-update

`UpdateManager` (banner wired in `PokerScreen`/`PokerViewModel`):

1. On launch, `cleanupOldDownloads` deletes any downloaded APK ≤ the current
   version, then `checkForUpdate` queries the `UPDATE_REPO` GitHub
   `releases/latest` and compares versions numerically.
2. If newer, a top banner appears. Tapping downloads `poker-odds.apk` to
   `filesDir/updates/poker-odds-<version>.apk` — **once per version** (an
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

- **`ci.yml`** — push/PR: lint, debug APK (`fetch-depth: 0`).
- **`equity-tools.yml`** — push/PR touching `tools/**`: builds the C generator and runs its tests.
- **`release.yml`** — `v*` tag: signed release APK → GitHub Release as `poker-odds.apk`.
- **`pages.yml`** — deploys `docs/` (download page) to GitHub Pages.

## Conventions

- `engine/` holds only the card model + category enum — no Android imports, no
  poker math. All hand evaluation lives in `tools/equity.c`.
- UI state flows one way: Compose reads `PokerViewModel.uiState`; actions emit a
  new immutable `PokerUiState`.
- The app must not compute equities at runtime — regenerate the DB instead.
