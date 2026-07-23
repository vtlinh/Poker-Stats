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
├── engine/                 # Pure-Kotlin poker math (unit-tested; used offline to build the DB)
│   ├── Card.kt · HandEvaluator.kt · EquityCalculator.kt
├── data/
│   └── EquityDatabase.kt   # Copies the asset DB out, answers lookups (hand class, players)
├── update/
│   └── UpdateManager.kt    # Checks GitHub Releases, downloads/installs APK, cleans up
├── ui/
│   ├── PokerViewModel.kt · PokerScreen.kt · theme/
│   └── ...
└── MainActivity.kt
app/src/main/assets/poker_equity.db   # Precomputed equities (169 hands × players 2–6 = 845 rows)
tools/                                 # Regenerates poker_equity.db (see tools/README.md)
```

## Precomputed equities (the DB)

- The app is a pure lookup: `EquityDatabase.lookup(cardA, cardB, players)` maps
  the two cards to a canonical class (`AA`, `AKs`, `AKo`, `72o`, …) and reads
  one indexed row. No simulation on device.
- The DB is generated **offline** by `tools/GenerateEquityDb.kt`, which reuses
  `engine/` to Monte-Carlo 300k deals per cell, then `tools/build_equity_db.py`
  packs the TSV into SQLite. Regenerate only when the engine changes; see
  `tools/README.md`. Sanity: `AA` heads-up ≈ 85%, `AA` 6-handed ≈ 49%.
- `EquityDatabase` copies the asset to the app's databases dir on first use and
  recopies if the asset's `PRAGMA user_version` changes.

## The engine (still the source of truth for the numbers)

- `HandEvaluator` collapses any 5–7 card hand into one comparable `Int`
  (category in the top bits, kicker nibbles below); evaluates from rank/suit
  counts, not 21-subset enumeration; handles the wheel. Pinned by
  `directEvaluatorMatchesBruteForce` (50k random hands vs brute force).
- `EquityCalculator` Monte-Carlos win/tie/lose with an injectable `Random`.
- **Keep the engine tests green** — they encode canonical equities and feed the
  DB generator.

## Versioning: `major.minor.build`

Computed in `app/build.gradle.kts` at build time:

- `major` = current year − 2025
- `minor` = ISO week of the year
- `build` = commits in the current ISO week + 1 (resets weekly, grows per build);
  override with the `BUILD_VERSION` env var.

`versionCode = (major*100 + minor)*1000 + build` (monotonic). CI checks out with
`fetch-depth: 0` so the weekly commit count is accurate. **Release tags should
match the computed version** (e.g. `v1.30.2`) so the in-app updater compares
correctly.

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
./gradlew test                 # engine unit tests (JVM)
./gradlew :app:assembleDebug   # debug APK
./gradlew :app:assembleRelease # signed release APK (needs signing config)
./gradlew lint
```

## Signing & release

Release signing is driven by secrets (env vars, then optional local
`keystore.properties`): `KEYSTORE_FILE`/`storeFile`,
`KEYSTORE_PASSWORD`/`storePassword`, `KEY_ALIAS`/`keyAlias`,
`KEY_PASSWORD`/`keyPassword`. If absent, the release build is unsigned. See
`.github/workflows/release.yml`.

## CI / CD (GitHub Actions)

- **`ci.yml`** — push/PR: unit tests, lint, debug APK (`fetch-depth: 0`).
- **`release.yml`** — `v*` tag: signed release APK → GitHub Release as `poker-odds.apk`.
- **`pages.yml`** — deploys `docs/` (download page) to GitHub Pages.

## Conventions

- `engine/` stays free of Android imports (JVM-testable, drives the generator).
- UI state flows one way: Compose reads `PokerViewModel.uiState`; actions emit a
  new immutable `PokerUiState`.
- The app must not compute equities at runtime — extend the DB instead.
