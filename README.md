# Poker Odds ♠️

A native Android app that tells you your **chance of winning** a Texas Hold'em
hand. Pick your two hole cards, add any community cards on the board, choose how
many opponents you're up against, and it runs a Monte-Carlo simulation to
estimate your win / tie / lose probabilities — plus a breakdown of how often
your hand improves to each poker category.

<p align="center">
  <em>Kotlin · Jetpack Compose · Material 3</em>
</p>

## Download

Grab the latest signed APK from the **[releases page][releases]**, or from the
project **[download page][pages]**.

[releases]: https://github.com/vtlinh/Poker-Stats/releases/latest
[pages]: https://vtlinh.github.io/Poker-Stats/

> The app is distributed as an APK. On your device, enable **Install unknown
> apps** for your browser/file manager, then open the downloaded APK.

## Features

- 🃏 Tap-to-pick card selector for your hand and the board (flop / turn / river).
- 👥 1–9 opponents.
- 📊 Win, tie, and equity (split-pot-aware) percentages.
- 📈 Probability that your hand ends up as each category (pair … straight flush).
- ⚡ Fast, allocation-light Monte-Carlo engine (100k deals per query by default).
- 🌗 Light & dark themes.

## How it works

The core is a small, framework-free Kotlin engine:

- **`HandEvaluator`** reduces any 5–7 card hand to a single comparable `Int`
  score, so ranking hands is one integer comparison. It evaluates directly from
  rank/suit counts instead of enumerating all 21 five-card subsets, which keeps
  the simulation fast. Correctness is pinned by a unit test that cross-checks it
  against a brute-force reference over 50,000 random hands.
- **`EquityCalculator`** deals the unknown cards thousands of times with a
  partial Fisher–Yates shuffle, evaluates every player's best hand, and tallies
  how often the hero wins, ties, or loses.

Sample equities the engine reproduces (heads-up, pre-flop):

| Hand   | Win % (vs 1 random hand) |
|--------|--------------------------|
| A♠ A♥  | ~85%                     |
| A♠ K♠  | ~67%                     |
| 7♦ 2♣  | ~35% (the classic worst) |

## Build from source

Requirements: JDK 17 and the Android SDK (or just open in Android Studio).

```bash
./gradlew test                 # run the engine unit tests
./gradlew :app:assembleDebug   # build a debug APK -> app/build/outputs/apk/debug/
```

## Signing your own release

Provide a keystore via environment variables (or a local `keystore.properties`)
— see [`CLAUDE.md`](CLAUDE.md#signing--release) for the full table — then:

```bash
./gradlew :app:assembleRelease
```

## Continuous integration

| Workflow      | Trigger        | Does                                                |
|---------------|----------------|-----------------------------------------------------|
| `ci.yml`      | push / PR      | unit tests, lint, debug APK                          |
| `release.yml` | `v*` git tag   | builds a **signed** release APK, publishes a Release |
| `pages.yml`   | push to `main` | deploys the `docs/` download page to GitHub Pages    |

To cut a release: `git tag v1.0.0 && git push origin v1.0.0`.

## Project layout

See [`CLAUDE.md`](CLAUDE.md) for the full map and contributor notes.

## Disclaimer

For educational and entertainment use. Please gamble responsibly.
