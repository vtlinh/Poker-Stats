# CLAUDE.md

Guidance for Claude Code (and humans) working in this repository.

## What this is

**Poker Odds** is a native Android app that estimates your probability of
winning a Texas Hold'em hand given your hole cards, the community cards on the
board, and the number of opponents. Equity is computed with a Monte-Carlo
simulation over the unknown cards.

## Tech stack

- **Language:** Kotlin
- **UI:** Jetpack Compose (Material 3)
- **Architecture:** single-Activity, `ViewModel` + `StateFlow` (unidirectional state)
- **Build:** Gradle (Kotlin DSL) with a version catalog at `gradle/libs.versions.toml`
- **Min SDK:** 26 · **Target/Compile SDK:** 34 · **JDK:** 17

## Project layout

```
app/src/main/java/com/pokerstats/odds/
├── engine/                 # Pure-Kotlin, framework-free poker math (unit-tested)
│   ├── Card.kt             # Rank, Suit, Card (0..51 index encoding), full deck
│   ├── HandEvaluator.kt    # Best-5-of-7 evaluator -> single comparable Int score
│   └── EquityCalculator.kt # Monte-Carlo win/tie/lose + hand-category breakdown
├── ui/
│   ├── PokerViewModel.kt   # UI state, card selection, runs the simulation
│   ├── PokerScreen.kt      # Compose UI: card picker, slots, results
│   └── theme/              # Colors, typography, Material 3 theme
└── MainActivity.kt
app/src/test/java/...       # JVM unit tests for the engine
```

## The engine (most important part)

- A hand is scored as one `Int`: the top 4 bits hold the `HandCategory` ordinal
  and the low 20 bits hold up to five 4-bit rank nibbles for kicker tie-breaks.
  A plain `Int` comparison then reproduces the full rules of poker, including
  the A-2-3-4-5 "wheel" straight.
- `HandEvaluator.evaluateIndices` works directly on rank/suit counts (no 21-way
  subset enumeration) so the Monte-Carlo hot loop stays fast. Its correctness is
  pinned by `directEvaluatorMatchesBruteForce`, which compares it against a
  brute-force best-of-21-subsets reference over 50k random hands.
- `EquityCalculator` accepts an injectable `Random` so equity tests are
  deterministic.

**If you touch the engine, keep the tests green** — they encode canonical
equities (e.g. AA is ~85% heads-up) and the evaluator cross-check.

## Common commands

```bash
./gradlew test                     # run the JVM unit tests (the engine)
./gradlew :app:assembleDebug       # build a debug APK
./gradlew :app:assembleRelease     # build a release APK (needs signing config)
./gradlew lint                     # Android lint
```

## Signing & release

Release signing is driven by secrets, never checked in. The build reads, in
order, environment variables then an optional local `keystore.properties`:

| Purpose        | Env var            | `keystore.properties` key |
|----------------|--------------------|---------------------------|
| Keystore file  | `KEYSTORE_FILE`    | `storeFile`               |
| Keystore pass  | `KEYSTORE_PASSWORD`| `storePassword`           |
| Key alias      | `KEY_ALIAS`        | `keyAlias`                |
| Key password   | `KEY_PASSWORD`     | `keyPassword`             |

If none are present, the release build is left unsigned (CI attaches the
signature). See `.github/workflows/release.yml`.

## CI / CD (GitHub Actions)

- **`ci.yml`** — on every push/PR: runs unit tests, Android lint, and builds a
  debug APK.
- **`release.yml`** — on a `v*` tag: builds a **signed** release APK and
  publishes it to a GitHub Release as `poker-odds.apk`.
- **`pages.yml`** — deploys `docs/` to GitHub Pages (the download landing page,
  which links to the latest release APK).

## Conventions

- Keep `engine/` free of Android framework imports so it stays unit-testable on
  the JVM.
- UI state flows one way: Compose reads `PokerViewModel.uiState`; user actions
  call `ViewModel` methods that emit a new immutable `PokerUiState`.
