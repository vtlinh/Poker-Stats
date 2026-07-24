/*
 * equity.c — fast Monte-Carlo generator for the precomputed preflop-equity table.
 *
 * For each of the 169 canonical Texas Hold'em starting hands and each total
 * player count (2..MAX_PLAYERS), it Monte-Carlos the win/tie/lose equity and
 * the hero's final-hand-category distribution, and prints one TSV row per
 * (hand, players) to stdout:
 *
 *     hand<TAB>players<TAB>win<TAB>tie<TAB>lose<TAB>win_fold<TAB>tie_fold<TAB>{category json}
 *
 * `win` is the sole-win rate; `tie` is split-pot *equity* (a k-way tie counts
 * 1/k, not a whole win); `lose = 1 - win - tie` is the field's share. So the
 * hero's true equity is `win + tie`.
 *
 * `win_fold`/`tie_fold` are the fold-adjusted equity: the hero's win/tie rate
 * when every player whose own N-player equity is below break-even (1/N) folds
 * pre-flop. That includes the hero — a below-break-even hero folds and loses
 * (win_fold = 0). `build_equity_db.py` packs the TSV into the SQLite asset.
 *
 * The 7-card evaluator works directly from rank/suit counts (no 21-subset
 * enumeration); `--selftest` cross-checks it against a brute-force best-of-21
 * reference and a set of known hands. Parallelised with OpenMP; each cell is
 * seeded by its index so output is deterministic regardless of thread count.
 *
 * Build:  cc -O3 -fopenmp -o equity equity.c
 * Run:    ./equity [trials]      (default 1000000)
 *         ./equity --selftest
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>

#define MAX_PLAYERS 6
#define MIN_PLAYERS 2
#define NUM_HANDS 169
#define CATEGORY_SHIFT 20

/* Card index 0..51 = (rank-2)*4 + suit, rank 2..14, suit 0..3. */
static inline int card_rank(int c) { return c / 4 + 2; }
static inline int card_suit(int c) { return c % 4; }

static const char *CATEGORY_NAMES[9] = {
    "HIGH_CARD", "PAIR", "TWO_PAIR", "THREE_OF_A_KIND", "STRAIGHT",
    "FLUSH", "FULL_HOUSE", "FOUR_OF_A_KIND", "STRAIGHT_FLUSH"
};

/* Highest card of a 5-card straight present in `mask`, or 0. Handles the wheel. */
static inline int straight_high(int mask) {
    if (mask & (1 << 14)) mask |= (1 << 1); /* ace plays low for A-2-3-4-5 */
    int run = 0;
    for (int r = 14; r >= 1; r--) {
        if (mask & (1 << r)) {
            if (++run >= 5) return r + 4;
        } else {
            run = 0;
        }
    }
    return 0;
}

/*
 * Evaluate the best 5-card hand out of `n` (5..7) cards into a single
 * comparable int: category in the top bits, up to five 4-bit rank nibbles
 * below. The category ordinal is `score >> CATEGORY_SHIFT`.
 */
static int evaluate(const int *cards, int n) {
    int rank_count[15] = {0};
    int suit_count[4] = {0};
    int suit_rank_mask[4] = {0};
    int rank_mask = 0;

    for (int i = 0; i < n; i++) {
        int r = card_rank(cards[i]);
        int s = card_suit(cards[i]);
        rank_count[r]++;
        suit_count[s]++;
        suit_rank_mask[s] |= (1 << r);
        rank_mask |= (1 << r);
    }

    int flush_suit = -1;
    for (int s = 0; s < 4; s++) {
        if (suit_count[s] >= 5) { flush_suit = s; break; }
    }
    if (flush_suit >= 0) {
        int sf = straight_high(suit_rank_mask[flush_suit]);
        if (sf) return (8 << CATEGORY_SHIFT) + sf;
    }

    int quad = 0, trip1 = 0, trip2 = 0, pair1 = 0, pair2 = 0;
    for (int r = 14; r >= 2; r--) {
        switch (rank_count[r]) {
            case 4: if (!quad) quad = r; break;
            case 3: if (!trip1) trip1 = r; else if (!trip2) trip2 = r; break;
            case 2: if (!pair1) pair1 = r; else if (!pair2) pair2 = r; break;
        }
    }

    if (quad) {
        int kicker = 0;
        for (int r = 14; r >= 2; r--) if (r != quad && rank_count[r]) { kicker = r; break; }
        return (7 << CATEGORY_SHIFT) + (quad << 4) + kicker;
    }
    if (trip1 && (trip2 || pair1)) {
        int pair = trip2 ? trip2 : pair1;
        return (6 << CATEGORY_SHIFT) + (trip1 << 4) + pair;
    }
    if (flush_suit >= 0) {
        int v = 0, cnt = 0;
        for (int r = 14; r >= 2 && cnt < 5; r--)
            if (suit_rank_mask[flush_suit] & (1 << r)) { v = v * 16 + r; cnt++; }
        return (5 << CATEGORY_SHIFT) + v;
    }
    int sh = straight_high(rank_mask);
    if (sh) return (4 << CATEGORY_SHIFT) + sh;

    if (trip1) {
        int v = trip1, cnt = 0;
        for (int r = 14; r >= 2 && cnt < 2; r--) if (r != trip1 && rank_count[r]) { v = v * 16 + r; cnt++; }
        return (3 << CATEGORY_SHIFT) + v;
    }
    if (pair1 && pair2) {
        int kicker = 0;
        for (int r = 14; r >= 2; r--) if (r != pair1 && r != pair2 && rank_count[r]) { kicker = r; break; }
        return (2 << CATEGORY_SHIFT) + (pair1 << 8) + (pair2 << 4) + kicker;
    }
    if (pair1) {
        int v = pair1, cnt = 0;
        for (int r = 14; r >= 2 && cnt < 3; r--) if (r != pair1 && rank_count[r]) { v = v * 16 + r; cnt++; }
        return (1 << CATEGORY_SHIFT) + v;
    }
    int v = 0, cnt = 0;
    for (int r = 14; r >= 2 && cnt < 5; r--) if (rank_mask & (1 << r)) { v = v * 16 + r; cnt++; }
    return (0 << CATEGORY_SHIFT) + v;
}

/* --- fast PRNG (splitmix64 seed -> xoshiro256**) ------------------------- */
typedef struct { uint64_t s[4]; } rng_t;

static inline uint64_t rotl(uint64_t x, int k) { return (x << k) | (x >> (64 - k)); }

static void rng_seed(rng_t *r, uint64_t seed) {
    for (int i = 0; i < 4; i++) {
        seed += 0x9E3779B97F4A7C15ULL;
        uint64_t z = seed;
        z = (z ^ (z >> 30)) * 0xBF58476D1CE4E5B9ULL;
        z = (z ^ (z >> 27)) * 0x94D049BB133111EBULL;
        r->s[i] = z ^ (z >> 31);
    }
}

static inline uint64_t rng_next(rng_t *r) {
    uint64_t *s = r->s;
    uint64_t result = rotl(s[1] * 5, 7) * 9;
    uint64_t t = s[1] << 17;
    s[2] ^= s[0]; s[3] ^= s[1]; s[1] ^= s[2]; s[0] ^= s[3]; s[2] ^= t;
    s[3] = rotl(s[3], 45);
    return result;
}

/* Uniform int in [0, bound). */
static inline int rng_bounded(rng_t *r, int bound) {
    return (int)(rng_next(r) % (uint64_t)bound);
}

/* --- canonical starting hands ------------------------------------------- */
static const char RANK_SYMS[13] = {'A','K','Q','J','T','9','8','7','6','5','4','3','2'};
static const int  RANK_VALS[13] = {14,13,12,11,10,9,8,7,6,5,4,3,2};

typedef struct {
    char label[4];
    int c1, c2; /* representative card indices */
} hand_t;

static int card_index(int rank, int suit) { return (rank - 2) * 4 + suit; }

static int build_hands(hand_t *out) {
    int n = 0;
    for (int i = 0; i < 13; i++) {
        for (int j = i; j < 13; j++) {
            int hi = RANK_VALS[i], lo = RANK_VALS[j];
            hand_t h;
            if (i == j) {
                h.label[0] = RANK_SYMS[i]; h.label[1] = RANK_SYMS[i]; h.label[2] = '\0';
                h.c1 = card_index(hi, 0); h.c2 = card_index(hi, 1);
                out[n++] = h;
            } else {
                h.label[0] = RANK_SYMS[i]; h.label[1] = RANK_SYMS[j]; h.label[2] = 's'; h.label[3] = '\0';
                h.c1 = card_index(hi, 0); h.c2 = card_index(lo, 0);
                out[n++] = h;
                hand_t o;
                o.label[0] = RANK_SYMS[i]; o.label[1] = RANK_SYMS[j]; o.label[2] = 'o'; o.label[3] = '\0';
                o.c1 = card_index(hi, 0); o.c2 = card_index(lo, 1);
                out[n++] = o;
            }
        }
    }
    return n;
}

/*
 * Map any two hole cards to their canonical hand index (0..168), matching the
 * order build_hands() emits (pair, then suited, then offsuit). Used to look up
 * an opponent's precomputed equity when deciding whether they fold.
 */
static int g_idx_pair[15];          /* [rank]      -> index */
static int g_idx_suited[15][15];    /* [hi][lo]    -> index */
static int g_idx_offsuit[15][15];   /* [hi][lo]    -> index */

static void build_class_index(void) {
    int n = 0;
    for (int i = 0; i < 13; i++) {
        for (int j = i; j < 13; j++) {
            int hi = RANK_VALS[i], lo = RANK_VALS[j];
            if (i == j) {
                g_idx_pair[hi] = n++;
            } else {
                g_idx_suited[hi][lo] = n++;
                g_idx_offsuit[hi][lo] = n++;
            }
        }
    }
}

static inline int classify(int a, int b) {
    int ra = card_rank(a), rb = card_rank(b);
    int hi = ra > rb ? ra : rb;
    int lo = ra > rb ? rb : ra;
    if (ra == rb) return g_idx_pair[hi];
    return (card_suit(a) == card_suit(b)) ? g_idx_suited[hi][lo] : g_idx_offsuit[hi][lo];
}

/* --- one Monte-Carlo cell ----------------------------------------------- */
typedef struct {
    double win, tie, lose;
    double cat[9];
} result_t;

static result_t simulate(int c1, int c2, int players, long trials, uint64_t seed) {
    int opponents = players - 1;
    int deck[52], deck_n = 0;
    for (int c = 0; c < 52; c++) if (c != c1 && c != c2) deck[deck_n++] = c;

    int need = 5 + 2 * opponents; /* full board + opponents' hole cards */
    long wins = 0;            /* deals the hero is the sole best hand */
    double tie_equity = 0.0;  /* split-pot equity: 1/k per k-way tie */
    long cat_counts[9] = {0};

    rng_t rng;
    rng_seed(&rng, seed);

    int hero[7];
    hero[0] = c1; hero[1] = c2;
    int opp[7];

    for (long t = 0; t < trials; t++) {
        /* partial Fisher-Yates: draw `need` cards to the front of deck */
        for (int i = 0; i < need; i++) {
            int j = i + rng_bounded(&rng, deck_n - i);
            int tmp = deck[i]; deck[i] = deck[j]; deck[j] = tmp;
        }
        /* board = first 5 drawn; opponents' holes follow */
        for (int i = 0; i < 5; i++) { hero[2 + i] = deck[i]; opp[2 + i] = deck[i]; }

        int hero_score = evaluate(hero, 7);
        cat_counts[hero_score >> CATEGORY_SHIFT]++;

        int best_opp = -1;
        int tie_opp = 0; /* opponents sharing the current best opponent score */
        int idx = 5;
        for (int o = 0; o < opponents; o++) {
            opp[0] = deck[idx++];
            opp[1] = deck[idx++];
            int s = evaluate(opp, 7);
            if (s > best_opp) { best_opp = s; tie_opp = 1; }
            else if (s == best_opp) tie_opp++;
        }

        if (hero_score > best_opp) wins++;
        else if (hero_score == best_opp) tie_equity += 1.0 / (tie_opp + 1);
        /* else the hero is beaten and takes nothing */
    }

    result_t r;
    double n = (double)trials;
    r.win = wins / n;
    r.tie = tie_equity / n;         /* split-pot equity share, not a raw count */
    r.lose = 1.0 - r.win - r.tie;   /* the rest of the pot goes to the field */
    for (int c = 0; c < 9; c++) r.cat[c] = cat_counts[c] / n;
    return r;
}

/*
 * Fold-adjusted equity for a hero who is playing (caller has already checked
 * the hero's hand clears break-even). Every opponent folds pre-flop when their
 * own N-player equity (win + tie) is below the break-even threshold 1/N, and
 * only the rest go to showdown. `equity[class][players]` is the standard
 * preflop equity table computed by the first pass. If every opponent folds the
 * hero wins uncontested. Ties count as wins for the hero, mirroring the app's
 * headline win-probability metric.
 */
static void simulate_fold(int c1, int c2, int players, long trials, uint64_t seed,
                          const double equity[][MAX_PLAYERS + 1],
                          double *out_win, double *out_tie) {
    int opponents = players - 1;
    int deck[52], deck_n = 0;
    for (int c = 0; c < 52; c++) if (c != c1 && c != c2) deck[deck_n++] = c;

    int need = 5 + 2 * opponents;
    double thresh = 1.0 / players;
    long wins = 0;            /* deals the hero wins outright (or uncontested) */
    double tie_equity = 0.0;  /* split-pot equity: 1/k per k-way tie */

    rng_t rng;
    rng_seed(&rng, seed);

    int hero[7];
    hero[0] = c1; hero[1] = c2;
    int opp[7];

    for (long t = 0; t < trials; t++) {
        for (int i = 0; i < need; i++) {
            int j = i + rng_bounded(&rng, deck_n - i);
            int tmp = deck[i]; deck[i] = deck[j]; deck[j] = tmp;
        }
        for (int i = 0; i < 5; i++) { hero[2 + i] = deck[i]; opp[2 + i] = deck[i]; }

        int hero_score = evaluate(hero, 7);

        int best_opp = -1;
        int tie_opp = 0; /* staying opponents sharing the best opponent score */
        int idx = 5;
        for (int o = 0; o < opponents; o++) {
            int a = deck[idx++];
            int b = deck[idx++];
            if (equity[classify(a, b)][players] < thresh) continue; /* opponent folds */
            opp[0] = a; opp[1] = b;
            int s = evaluate(opp, 7);
            if (s > best_opp) { best_opp = s; tie_opp = 1; }
            else if (s == best_opp) tie_opp++;
        }

        if (best_opp < 0 || hero_score > best_opp) wins++; /* uncontested or sole best */
        else if (hero_score == best_opp) tie_equity += 1.0 / (tie_opp + 1);
    }

    double n = (double)trials;
    *out_win = wins / n;
    *out_tie = tie_equity / n;
}

/* --- self test ----------------------------------------------------------- */
static int parse_card(const char *s) {
    const char *R = "23456789TJQKA";
    const char *S = "cdhs";
    int rank = (int)(strchr(R, s[0]) - R) + 2;
    int suit = (int)(strchr(S, s[1]) - S);
    return card_index(rank, suit);
}

static int eval_str(const char *codes[], int n) {
    int cards[7];
    for (int i = 0; i < n; i++) cards[i] = parse_card(codes[i]);
    return evaluate(cards, n);
}

static int category_of(int score) { return score >> CATEGORY_SHIFT; }

static int brute_best_of_7(const int *c) {
    int best = -1;
    for (int a = 0; a < 3; a++)
    for (int b = a + 1; b < 4; b++)
    for (int d = b + 1; d < 5; d++)
    for (int e = d + 1; e < 6; e++)
    for (int f = e + 1; f < 7; f++) {
        int sub[5] = { c[a], c[b], c[d], c[e], c[f] };
        int s = evaluate(sub, 5);
        if (s > best) best = s;
    }
    return best;
}

static int selftest(void) {
    int fails = 0;
    #define CHECK(cond, msg) do { if (!(cond)) { printf("FAIL: %s\n", msg); fails++; } } while (0)

    { const char *h[] = {"As","Ks","Qs","Js","Ts"}; CHECK(category_of(eval_str(h,5)) == 8, "royal is straight flush"); }
    { const char *h[] = {"As","Ah","Ad","Ac","Kd"}; CHECK(category_of(eval_str(h,5)) == 7, "quads"); }
    { const char *h[] = {"As","Ah","Ad","Kc","Kd"}; CHECK(category_of(eval_str(h,5)) == 6, "full house"); }
    { const char *h[] = {"As","Js","9s","5s","2s"}; CHECK(category_of(eval_str(h,5)) == 5, "flush"); }
    { const char *h[] = {"9c","8d","7h","6s","5c"}; CHECK(category_of(eval_str(h,5)) == 4, "straight"); }
    { const char *h[] = {"Ah","2c","3d","4s","5h"}; CHECK(category_of(eval_str(h,5)) == 4, "wheel straight"); }
    { const char *h[] = {"Qs","Qh","Qd","7c","2d"}; CHECK(category_of(eval_str(h,5)) == 3, "trips"); }
    { const char *h[] = {"Js","Jh","4d","4c","9d"}; CHECK(category_of(eval_str(h,5)) == 2, "two pair"); }
    { const char *h[] = {"Ts","Th","6d","4c","2d"}; CHECK(category_of(eval_str(h,5)) == 1, "pair"); }
    { const char *h[] = {"As","Jh","9d","6c","3d"}; CHECK(category_of(eval_str(h,5)) == 0, "high card"); }

    /* ordering: pair of aces beats king high (the classic packing bug) */
    { const char *pa[] = {"As","Ah","5h","3c","2c"}; const char *kh[] = {"Ks","Jd","9h","7c","5s"};
      CHECK(eval_str(pa,5) > eval_str(kh,5), "pair of aces > king high"); }
    /* wheel is the lowest straight */
    { const char *six[] = {"6h","2c","3d","4s","5h"}; const char *wheel[] = {"Ah","2c","3d","4s","5h"};
      CHECK(eval_str(six,5) > eval_str(wheel,5), "six-high straight > wheel"); }
    /* best 5 of 7: flush chosen over a pair */
    { const char *h[] = {"As","Ks","9s","5s","2s","Kh","Kd"}; CHECK(category_of(eval_str(h,7)) == 5, "flush from 7"); }
    /* two trips in 7 -> full house */
    { const char *h[] = {"As","Ah","Ad","Kc","Kd","Kh","2s"}; CHECK(category_of(eval_str(h,7)) == 6, "full house from two trips"); }

    /* brute-force cross-check over random 7-card hands */
    rng_t rng; rng_seed(&rng, 20260723ULL);
    int mism = 0;
    for (int it = 0; it < 300000; it++) {
        int deck[52]; for (int i = 0; i < 52; i++) deck[i] = i;
        int hand[7];
        for (int i = 0; i < 7; i++) {
            int j = i + rng_bounded(&rng, 52 - i);
            int tmp = deck[i]; deck[i] = deck[j]; deck[j] = tmp;
            hand[i] = deck[i];
        }
        if (evaluate(hand, 7) != brute_best_of_7(hand)) mism++;
    }
    CHECK(mism == 0, "direct evaluator matches brute force over 300k hands");
    printf("brute-force mismatches: %d\n", mism);

    if (fails == 0) printf("SELFTEST OK\n");
    else printf("SELFTEST FAILED: %d\n", fails);
    return fails;
    #undef CHECK
}

/* --- main ---------------------------------------------------------------- */
int main(int argc, char **argv) {
    if (argc > 1 && strcmp(argv[1], "--selftest") == 0) {
        return selftest() == 0 ? 0 : 1;
    }
    long trials = 1000000;
    if (argc > 1) trials = atol(argv[1]);

    hand_t hands[NUM_HANDS];
    int nh = build_hands(hands);
    build_class_index();

    int nP = MAX_PLAYERS - MIN_PLAYERS + 1;
    int total = nh * nP;
    /* flat task list so OpenMP can split evenly */
    typedef struct { int hand_idx, players; } task_t;
    task_t *tasks = malloc(sizeof(task_t) * total);
    int ti = 0;
    for (int h = 0; h < nh; h++)
        for (int p = MIN_PLAYERS; p <= MAX_PLAYERS; p++)
            tasks[ti++] = (task_t){ h, p };

    result_t *results = malloc(sizeof(result_t) * total);

    /* Pass 1: standard equity (all players go to showdown). */
    #pragma omp parallel for schedule(dynamic)
    for (int i = 0; i < total; i++) {
        hand_t h = hands[tasks[i].hand_idx];
        results[i] = simulate(h.c1, h.c2, tasks[i].players, trials, (uint64_t)(i + 1));
    }

    /* Standard equity (win + tie) indexed by [hand class][players], used by
     * pass 2 to decide which opponents fold. Class index == hand index. */
    static double equity[NUM_HANDS][MAX_PLAYERS + 1];
    for (int i = 0; i < total; i++)
        equity[tasks[i].hand_idx][tasks[i].players] = results[i].win + results[i].tie;

    /* Pass 2: fold-adjusted equity. Everyone folds a hand whose N-player
     * equity is below break-even (1/N) — including the hero. If the hero's own
     * hand is below break-even the hero folds and simply loses (win = 0);
     * otherwise the hero plays on against whichever opponents stayed. */
    double *win_fold = malloc(sizeof(double) * total);
    double *tie_fold = malloc(sizeof(double) * total);
    #pragma omp parallel for schedule(dynamic)
    for (int i = 0; i < total; i++) {
        hand_t h = hands[tasks[i].hand_idx];
        int p = tasks[i].players;
        if (equity[tasks[i].hand_idx][p] < 1.0 / p) {
            win_fold[i] = 0.0; tie_fold[i] = 0.0; /* hero folds -> loses */
        } else {
            simulate_fold(h.c1, h.c2, p, trials, (uint64_t)(i + 1 + total),
                          equity, &win_fold[i], &tie_fold[i]);
        }
    }

    for (int i = 0; i < total; i++) {
        hand_t h = hands[tasks[i].hand_idx];
        result_t r = results[i];
        printf("%s\t%d\t%.5f\t%.5f\t%.5f\t%.5f\t%.5f\t{",
               h.label, tasks[i].players, r.win, r.tie, r.lose, win_fold[i], tie_fold[i]);
        for (int c = 0; c < 9; c++)
            printf("%s\"%s\":%.5f", c ? "," : "", CATEGORY_NAMES[c], r.cat[c]);
        printf("}\n");
    }

    free(tasks);
    free(results);
    free(win_fold);
    free(tie_fold);
    return 0;
}
