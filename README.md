# Poker Odds ♠️

A native Android app that tells you your **chance of winning** a Texas Hold'em
hand before the flop. Pick your two hole cards and how many players are at the
table (2–6), and it instantly shows your win / tie / equity — plus how often
your hand improves to each poker category.

It's fast because it does **no** math on the phone: every answer is a single
lookup in a precomputed database that ships with the app.

<p align="center">
  <em>Kotlin · Jetpack Compose · Material 3 · offline SQLite lookup</em>
</p>

## Download

Grab the latest signed APK from the **[releases page][releases]** or the
project **[download page][pages]**. Once installed, the app **updates itself**:
when a newer release exists, a banner appears at the top — tap it to download and
install.

[releases]: https://github.com/vtlinh/Poker-Stats/releases/latest
[pages]: https://vtlinh.github.io/Poker-Stats/

> The app is distributed as an APK. Enable **Install unknown apps** for your
> browser/file manager (the app will prompt you) to install and self-update.

## Features

- 🃏 Tap-to-pick selector for your two hole cards.
- 👥 2–6 total players (you + up to 5 opponents).
- ⚡ Instant results — a pure database lookup, no on-device computation.
- 📊 Win, tie, and equity (split-pot-aware) percentages.
- 📈 Probability your hand ends up as each category (pair … straight flush).
- ⬆️ Built-in updater: checks GitHub Releases, downloads each version once,
  installs it, and cleans up the file afterwards.
- 🌗 Light & dark themes.

## How it works

- **Precomputed table.** A tiny SQLite asset holds win/tie/lose + the final
  hand-category distribution for all **169** starting-hand classes × **2–6**
  players (845 rows). The app maps your two cards to a class (`AA`, `AKs`,
  `72o`, …) and reads one indexed row.
- **The numbers come from a fast offline generator.** `tools/equity.c` is an
  OpenMP-parallel Monte-Carlo simulator (1,000,000 deals per cell) whose 7-card
  evaluator is cross-checked against a brute-force reference over 300k random
  hands (`./equity --selftest`). A small Python harness packs its output into
  the shipped SQLite DB. The app itself contains no poker-evaluation code.

Sample win chances the table reproduces (heads-up, preflop; ties count as wins):

| Hand   | Win % vs 1 opponent |
|--------|---------------------|
| A♠ A♥  | ~85%                |
| A♠ K♠  | ~67%                |
| 7♦ 2♣  | ~35% (classic worst)|

## Versioning

Builds are versioned `major.minor.build`:

- `major` = current year − 2025
- `minor` = ISO week of the year
- `build` = commits this week + 1 (resets each week)

So the first build of the 30th week of 2026 is `1.30.1`. `versionCode` is
derived monotonically. Tag releases with the computed version (e.g. `v1.30.2`)
so the in-app updater compares correctly.

## Build from source

Requirements: JDK 17 and the Android SDK (or Android Studio).

```bash
./gradlew :app:assembleDebug   # build a debug APK
```

The equity database is precomputed and checked in. Regenerating it (only needed
if you change the simulator) uses C + Python — see
[`tools/README.md`](tools/README.md):

```bash
cd tools && make test          # C self-test + Python tests
cd tools && make db            # regenerate the DB (1,000,000 deals/cell)
```

## Continuous integration

| Workflow            | Trigger          | Does                                                 |
|---------------------|------------------|------------------------------------------------------|
| `ci.yml`            | push / PR        | lint, debug APK                                      |
| `equity-tools.yml`  | push / PR (tools)| builds the C generator and runs its tests            |
| `release.yml`       | `v*` git tag     | builds a **signed** release APK, publishes a Release  |
| `pages.yml`         | push to `main`   | deploys the `docs/` download page to GitHub Pages     |

## Project layout

See [`CLAUDE.md`](CLAUDE.md) for the full map and contributor notes.

## Disclaimer

For educational and entertainment use. Please gamble responsibly.
