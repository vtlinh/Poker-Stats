/*
 * equity.c — fast Monte-Carlo generator for the precomputed preflop-equity table.
 *
 * For each total player count (2..MAX_PLAYERS) it deals whole random tables and
 * records the win/tie/lose equity and final-hand-category distribution for
 * *every* player at once, aggregating into the 169 canonical starting-hand
 * classes. It prints one TSV row per (hand, players) to stdout:
 *
 *     hand  players  win  tie  lose  win_fold  tie_fold  post_fold_win  post_fold_tie  {cats}
 *   (tab-separated)
 *
 * `win` is the sole-win rate; `tie` is split-pot *equity* (a k-way tie counts
 * 1/k, not a whole win); `lose = 1 - win - tie` is the field's share. So the
 * hero's true equity is `win + tie`.
 *
 * `win_fold`/`tie_fold` are the pre-flop-fold equity: the hero's win/tie rate
 * when every player whose own N-player equity is below break-even (1/N) folds
 * pre-flop. That includes the hero — a below-break-even hero folds and loses
 * (win_fold = 0).
 *
 * `post_fold_win`/`post_fold_tie` add a *second* fold round: after the flop, the
 * pre-flop survivors also fold when their bucketed flop equity is below
 * 1/remaining (the hero too — a folded flop counts as a loss), averaged over all
 * flops. So it can be lower than the pre-flop-fold number for hands that miss the
 * flop often. `build_equity_db.py` packs the TSV into the SQLite asset.
 *
 * The 7-card evaluator works directly from rank/suit counts (no 21-subset
 * enumeration); `--selftest` cross-checks it against a brute-force best-of-21
 * reference and a set of known hands. Parallelised with OpenMP over deck
 * shuffles, each seeded by its index and reduced with integer accumulators, so
 * output is deterministic regardless of thread count.
 *
 * Build:  cc -O3 -fopenmp -o equity equity.c
 * Run:    ./equity [target]     (samples for the rarest class; default 1000000)
 *         ./equity --selftest
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <limits.h>

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

/* --- record-everyone Monte-Carlo ---------------------------------------- */
/*
 * Rather than fixing one hero hand per cell, each simulated game deals a full
 * table of `players` random hands sharing one board and records the win/tie
 * outcome for *every* player at once — so a single game updates several of the
 * 169 hand-class rows. Ties are scored as split-pot equity: a k-way tie is
 * worth 1/k, accumulated as the exact integer TIE_SCALE/k (TIE_SCALE =
 * lcm(2..6) = 60) so the parallel integer reduction is deterministic
 * regardless of thread count.
 *
 * One deck shuffle is reused for as many disjoint games as fit (a `players`
 * game needs 5 + 2*players cards). That is unbiased: every fixed block of
 * positions in a uniform shuffle is itself a uniform deal.
 */
#define TIE_SCALE 60  /* lcm(2,3,4,5,6): TIE_SCALE/k is exact for k <= 6 */

typedef struct {
    long long cnt[NUM_HANDS];        /* appearances of each hand class */
    long long win[NUM_HANDS];        /* deals won outright */
    long long tie_scaled[NUM_HANDS]; /* split-pot equity, scaled by TIE_SCALE */
    long long cat[NUM_HANDS][9];     /* final 7-card category counts */
} stats_t;

typedef struct {
    long long cnt[NUM_HANDS];
    long long win[NUM_HANDS];
    long long tie_scaled[NUM_HANDS];
} fold_stats_t;

/*
 * Flop texture buckets. The post-flop fold pass needs each player's equity on
 * the flop only to decide a fold (is it below 1/remaining?), and the shipped
 * number is averaged over all flops, so a coarse bucket whose error washes out
 * is enough. A bucket keys on the drivers of flop equity relative to the hand:
 * the made 5-card category (2 hole + 3 flop), flush-draw strength, and whether
 * a straight draw is present.  cat(0..8) x flush(0/1/2) x straight(0/1) = 54.
 */
#define NUM_BUCKETS (9 * 3 * 2)

static int flop_bucket(int c1, int c2, const int flop[3]) {
    int five[5] = { c1, c2, flop[0], flop[1], flop[2] };
    int cat = evaluate(five, 5) >> CATEGORY_SHIFT;      /* 0..8 made hand */

    int sc[4] = {0};
    for (int i = 0; i < 5; i++) sc[card_suit(five[i])]++;
    int maxs = 0;
    for (int s = 0; s < 4; s++) if (sc[s] > maxs) maxs = sc[s];
    int flush = maxs >= 5 ? 2 : (maxs == 4 ? 1 : 0);    /* made / draw / none */

    int rmask = 0;
    for (int i = 0; i < 5; i++) rmask |= 1 << card_rank(five[i]);
    if (rmask & (1 << 14)) rmask |= 1 << 1;             /* ace plays low */
    int straight = 0;
    for (int lo = 1; lo <= 10 && !straight; lo++) {
        int cnt = 0;
        for (int r = lo; r < lo + 5; r++) if (rmask & (1 << r)) cnt++;
        if (cnt >= 4) straight = 1;                     /* >=4 to a straight */
    }
    return (cat * 3 + flush) * 2 + straight;
}

/* Bucketed flop equity accumulators, indexed [field size][class][bucket]. Small
 * enough (~64k cells) for per-thread copies + reduction, like stats_t. */
typedef struct {
    long long cnt[MAX_PLAYERS + 1][NUM_HANDS][NUM_BUCKETS];
    long long win[MAX_PLAYERS + 1][NUM_HANDS][NUM_BUCKETS];
    long long tie_scaled[MAX_PLAYERS + 1][NUM_HANDS][NUM_BUCKETS];
} flop_stats_t;

/* Shuffles needed so the rarest cell (a suited hand — 4 of the 1326 two-card
 * combos — at the smallest table) is sampled ~`target` times. Each 6-max game
 * is reused for every table size, and a 2-player game deals the fewest hands,
 * so MIN_PLAYERS sets the pace. A suited class appears 4*MIN_PLAYERS/1326 times
 * per shuffle-game; solve for the shuffle count. Integer math, no libm. */
static long shuffles_for(long target) {
    int block = 5 + 2 * MAX_PLAYERS;
    int gps = 52 / block;
    long long num = (long long)target * 1326;
    long long den = 4LL * MIN_PLAYERS; /* 4 suited combos, smallest table */
    long games = (long)((num + den - 1) / den);
    return (games + gps - 1) / gps;
}

/*
 * One shared driver for both passes. Each shuffle deals full 6-max tables; every
 * table is then *reused* as a nested k-player game for k = 2..MAX_PLAYERS by
 * taking players 0..k-1 and the same board — so the six 7-card hands are ranked
 * once and serve every table size. Results land in out_eq[k] / out_fold[k].
 *
 * `equity` (win+tie per class, per table size) is required for the fold pass and
 * NULL for pass 1. When present, a player folds if its class equity is below the
 * table's break-even 1/k; a folding hero takes nothing.
 */
static void run_nested(long target, uint64_t seed_base,
                       const double equity[][MAX_PLAYERS + 1],
                       stats_t *out_eq, fold_stats_t *out_fold,
                       flop_stats_t *out_flopeq) {
    int block = 5 + 2 * MAX_PLAYERS;   /* 6-max game: 5 board + 12 hole */
    int gps = 52 / block;
    long units = shuffles_for(target);
    for (int k = 0; k <= MAX_PLAYERS; k++) {
        if (out_eq) memset(&out_eq[k], 0, sizeof(out_eq[k]));
        if (out_fold) memset(&out_fold[k], 0, sizeof(out_fold[k]));
    }
    if (out_flopeq) memset(out_flopeq, 0, sizeof(*out_flopeq));

    #pragma omp parallel
    {
        stats_t *le = out_eq ? calloc(MAX_PLAYERS + 1, sizeof(stats_t)) : NULL;
        fold_stats_t *lf = out_fold ? calloc(MAX_PLAYERS + 1, sizeof(fold_stats_t)) : NULL;
        flop_stats_t *lfe = out_flopeq ? calloc(1, sizeof(flop_stats_t)) : NULL;
        #pragma omp for schedule(dynamic, 256)
        for (long u = 0; u < units; u++) {
            rng_t rng;
            rng_seed(&rng, seed_base + (uint64_t)u);
            int deck[52];
            for (int c = 0; c < 52; c++) deck[c] = c;
            int need = gps * block;
            for (int i = 0; i < need; i++) {
                int j = i + rng_bounded(&rng, 52 - i);
                int tmp = deck[i]; deck[i] = deck[j]; deck[j] = tmp;
            }
            for (int g = 0; g < gps; g++) {
                int base = g * block;
                int hand[7];
                for (int i = 0; i < 5; i++) hand[2 + i] = deck[base + i]; /* shared board */
                int flop[3] = { deck[base], deck[base + 1], deck[base + 2] };
                int score[MAX_PLAYERS], cls[MAX_PLAYERS], bkt[MAX_PLAYERS];
                for (int p = 0; p < MAX_PLAYERS; p++) {
                    int a = deck[base + 5 + 2 * p];
                    int b = deck[base + 5 + 2 * p + 1];
                    hand[0] = a; hand[1] = b;
                    score[p] = evaluate(hand, 7); /* ranked once, reused below */
                    cls[p] = classify(a, b);
                    bkt[p] = lfe ? flop_bucket(a, b, flop) : 0;
                }
                /* Reuse the same six ranked hands as a nested k-player game. */
                for (int k = MIN_PLAYERS; k <= MAX_PLAYERS; k++) {
                    if (le) {
                        int mx = -1, tc = 0;
                        for (int p = 0; p < k; p++) {
                            if (score[p] > mx) { mx = score[p]; tc = 1; }
                            else if (score[p] == mx) tc++;
                        }
                        for (int p = 0; p < k; p++) {
                            int c = cls[p];
                            le[k].cnt[c]++;
                            le[k].cat[c][score[p] >> CATEGORY_SHIFT]++;
                            if (score[p] == mx) {
                                if (tc == 1) le[k].win[c]++;
                                else le[k].tie_scaled[c] += TIE_SCALE / tc;
                            }
                        }
                    }
                    if (lfe) {
                        /* Raw flop equity per (class, texture bucket, field=k):
                         * all k players go to showdown, keyed by each player's
                         * flop bucket. Drives post-flop fold decisions later. */
                        int mx = -1, tc = 0;
                        for (int p = 0; p < k; p++) {
                            if (score[p] > mx) { mx = score[p]; tc = 1; }
                            else if (score[p] == mx) tc++;
                        }
                        for (int p = 0; p < k; p++) {
                            lfe->cnt[k][cls[p]][bkt[p]]++;
                            if (score[p] == mx) {
                                if (tc == 1) lfe->win[k][cls[p]][bkt[p]]++;
                                else lfe->tie_scaled[k][cls[p]][bkt[p]] += TIE_SCALE / tc;
                            }
                        }
                    }
                    if (lf) {
                        double thresh = 1.0 / k;
                        for (int p = 0; p < k; p++) {
                            int c = cls[p];
                            lf[k].cnt[c]++;
                            if (equity[cls[p]][k] < thresh) continue; /* p folds */
                            int mx = -1, tc = 0; /* best among staying opponents */
                            for (int q = 0; q < k; q++) {
                                if (q == p || equity[cls[q]][k] < thresh) continue;
                                if (score[q] > mx) { mx = score[q]; tc = 1; }
                                else if (score[q] == mx) tc++;
                            }
                            if (mx < 0 || score[p] > mx) lf[k].win[c]++;
                            else if (score[p] == mx) lf[k].tie_scaled[c] += TIE_SCALE / (tc + 1);
                        }
                    }
                }
            }
        }
        #pragma omp critical
        {
            for (int k = MIN_PLAYERS; k <= MAX_PLAYERS; k++)
                for (int c = 0; c < NUM_HANDS; c++) {
                    if (le) {
                        out_eq[k].cnt[c] += le[k].cnt[c];
                        out_eq[k].win[c] += le[k].win[c];
                        out_eq[k].tie_scaled[c] += le[k].tie_scaled[c];
                        for (int j = 0; j < 9; j++) out_eq[k].cat[c][j] += le[k].cat[c][j];
                    }
                    if (lf) {
                        out_fold[k].cnt[c] += lf[k].cnt[c];
                        out_fold[k].win[c] += lf[k].win[c];
                        out_fold[k].tie_scaled[c] += lf[k].tie_scaled[c];
                    }
                }
            if (lfe)
                for (int k = MIN_PLAYERS; k <= MAX_PLAYERS; k++)
                    for (int c = 0; c < NUM_HANDS; c++)
                        for (int b = 0; b < NUM_BUCKETS; b++) {
                            out_flopeq->cnt[k][c][b] += lfe->cnt[k][c][b];
                            out_flopeq->win[k][c][b] += lfe->win[k][c][b];
                            out_flopeq->tie_scaled[k][c][b] += lfe->tie_scaled[k][c][b];
                        }
        }
        free(le);
        free(lf);
        free(lfe);
    }
}

/*
 * Pass 4 — aggregate post-flop fold, keyed (class, N), averaged over all flops.
 * Two fold rounds: pre-flop (a player folds when `equity[class][N] < 1/N`),
 * leaving `remaining`; then post-flop (a pre-flop survivor folds when its
 * bucketed flop equity `flopeq[remaining][class][bucket] < 1/remaining`). The
 * turn+river run out among the post-flop survivors; each pre-flop survivor's
 * win/split is recorded (a post-flop folder simply loses). Reuses the 6-max
 * nested deals; fold sets are recomputed per N (break-even `1/N` differs by N).
 */
static void run_postfold(long target, uint64_t seed_base,
                         const double equity[][MAX_PLAYERS + 1],
                         const double flopeq[][NUM_HANDS][NUM_BUCKETS],
                         fold_stats_t *out) {
    int block = 5 + 2 * MAX_PLAYERS;
    int gps = 52 / block;
    long units = shuffles_for(target);
    for (int k = 0; k <= MAX_PLAYERS; k++) memset(&out[k], 0, sizeof(out[k]));

    #pragma omp parallel
    {
        fold_stats_t *lo = calloc(MAX_PLAYERS + 1, sizeof(fold_stats_t));
        #pragma omp for schedule(dynamic, 256)
        for (long u = 0; u < units; u++) {
            rng_t rng;
            rng_seed(&rng, seed_base + (uint64_t)u);
            int deck[52];
            for (int c = 0; c < 52; c++) deck[c] = c;
            int need = gps * block;
            for (int i = 0; i < need; i++) {
                int j = i + rng_bounded(&rng, 52 - i);
                int tmp = deck[i]; deck[i] = deck[j]; deck[j] = tmp;
            }
            for (int g = 0; g < gps; g++) {
                int base = g * block;
                int hand[7];
                for (int i = 0; i < 5; i++) hand[2 + i] = deck[base + i];
                int flop[3] = { deck[base], deck[base + 1], deck[base + 2] };
                int score[MAX_PLAYERS], cls[MAX_PLAYERS], bkt[MAX_PLAYERS];
                for (int p = 0; p < MAX_PLAYERS; p++) {
                    int a = deck[base + 5 + 2 * p];
                    int b = deck[base + 5 + 2 * p + 1];
                    hand[0] = a; hand[1] = b;
                    score[p] = evaluate(hand, 7);
                    cls[p] = classify(a, b);
                    bkt[p] = flop_bucket(a, b, flop);
                }
                for (int k = MIN_PLAYERS; k <= MAX_PLAYERS; k++) {
                    double preth = 1.0 / k;
                    int stay[MAX_PLAYERS], remaining = 0;
                    for (int p = 0; p < k; p++) {
                        stay[p] = equity[cls[p]][k] >= preth;
                        if (stay[p]) remaining++;
                    }
                    double postth = remaining > 0 ? 1.0 / remaining : 1.0;
                    int in[MAX_PLAYERS];
                    for (int p = 0; p < k; p++) {
                        if (!stay[p]) { in[p] = 0; continue; }
                        in[p] = !(remaining >= 2 &&
                                  flopeq[remaining][cls[p]][bkt[p]] < postth);
                    }
                    int mx = -1, tc = 0; /* showdown among post-flop survivors */
                    for (int p = 0; p < k; p++) if (in[p]) {
                        if (score[p] > mx) { mx = score[p]; tc = 1; }
                        else if (score[p] == mx) tc++;
                    }
                    for (int p = 0; p < k; p++) {
                        if (!stay[p]) continue;      /* pre-flop folders: no row */
                        int c = cls[p];
                        lo[k].cnt[c]++;
                        if (!in[p]) continue;        /* folded on the flop -> loses */
                        if (score[p] == mx) {
                            if (tc == 1) lo[k].win[c]++;
                            else lo[k].tie_scaled[c] += TIE_SCALE / tc;
                        }
                    }
                }
            }
        }
        #pragma omp critical
        for (int k = MIN_PLAYERS; k <= MAX_PLAYERS; k++)
            for (int c = 0; c < NUM_HANDS; c++) {
                out[k].cnt[c] += lo[k].cnt[c];
                out[k].win[c] += lo[k].win[c];
                out[k].tie_scaled[c] += lo[k].tie_scaled[c];
            }
        free(lo);
    }
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
    /* `target` is the sample count for the rarest hand class (a pocket pair);
     * commoner classes are sampled proportionally more. */
    long target = 1000000;
    if (argc > 1) target = atol(argv[1]);

    hand_t hands[NUM_HANDS];
    int nh = build_hands(hands);
    build_class_index();

    /* Per (class, players) outputs. */
    static double W[NUM_HANDS][MAX_PLAYERS + 1];
    static double T[NUM_HANDS][MAX_PLAYERS + 1];
    static double L[NUM_HANDS][MAX_PLAYERS + 1];
    static double WF[NUM_HANDS][MAX_PLAYERS + 1];
    static double TF[NUM_HANDS][MAX_PLAYERS + 1];
    static double PFW[NUM_HANDS][MAX_PLAYERS + 1]; /* post-flop fold win */
    static double PFT[NUM_HANDS][MAX_PLAYERS + 1]; /* post-flop fold tie (split) */
    static double CAT[NUM_HANDS][MAX_PLAYERS + 1][9];
    static double equity[NUM_HANDS][MAX_PLAYERS + 1]; /* win + tie, per class */

    /* Pass 1: standard equity + category distribution for every table size in a
     * single set of 6-max shuffles (each reused as nested 2..6-player games). */
    stats_t *se = calloc(MAX_PLAYERS + 1, sizeof(stats_t));
    flop_stats_t *fe = calloc(1, sizeof(flop_stats_t)); /* bucketed flop equity, same deals */
    run_nested(target, 0x1ULL << 40, NULL, se, NULL, fe);
    long long min_samples = LLONG_MAX;
    int min_class = 0, min_players = 0;
    for (int players = MIN_PLAYERS; players <= MAX_PLAYERS; players++) {
        for (int c = 0; c < NUM_HANDS; c++) {
            long long n = se[players].cnt[c];
            if (n < min_samples) { min_samples = n; min_class = c; min_players = players; }
            double cnt = (double)n;
            double w = se[players].win[c] / cnt;
            double t = (double)se[players].tie_scaled[c] / (TIE_SCALE * cnt);
            W[c][players] = w;
            T[c][players] = t;
            L[c][players] = 1.0 - w - t;
            equity[c][players] = w + t;
            for (int j = 0; j < 9; j++) CAT[c][players][j] = se[players].cat[c][j] / cnt;
        }
    }

    /* Report sampling coverage; alert if any (class, players) cell is thinly
     * sampled. Every cell is worth at least ~`target` samples by design, so a
     * count below the floor means `target` is too small or the sizing broke. */
    long long shuffles = shuffles_for(target);
    fprintf(stderr, "sampling: %lld shuffles (2 passes); min %lld samples/class (%s %dp)\n",
            shuffles, min_samples, hands[min_class].label, min_players);
    if (min_samples < 1000)
        fprintf(stderr, "WARNING: %s %dp has only %lld samples (< 1000) — raise the target\n",
                hands[min_class].label, min_players, min_samples);

    /* Pass 2: fold-adjusted equity, deciding folds from the pass-1 table. */
    fold_stats_t *sf = calloc(MAX_PLAYERS + 1, sizeof(fold_stats_t));
    run_nested(target, 0x2ULL << 40, equity, NULL, sf, NULL);
    for (int players = MIN_PLAYERS; players <= MAX_PLAYERS; players++) {
        for (int c = 0; c < NUM_HANDS; c++) {
            double cnt = (double)sf[players].cnt[c];
            WF[c][players] = sf[players].win[c] / cnt;
            TF[c][players] = (double)sf[players].tie_scaled[c] / (TIE_SCALE * cnt);
        }
    }

    /* Bucketed flop equity (RAM-only intermediate) for the post-flop fold
     * decisions, from the same pass-1 deals. Common buckets get ~0.4×target
     * samples (dense); empty/rare buckets fall back to the class's equity. */
    static double flopeq[MAX_PLAYERS + 1][NUM_HANDS][NUM_BUCKETS];
    long long min_bucket = LLONG_MAX;
    for (int players = MIN_PLAYERS; players <= MAX_PLAYERS; players++)
        for (int c = 0; c < NUM_HANDS; c++)
            for (int b = 0; b < NUM_BUCKETS; b++) {
                long long n = fe->cnt[players][c][b];
                flopeq[players][c][b] = n
                    ? (fe->win[players][c][b] +
                       (double)fe->tie_scaled[players][c][b] / TIE_SCALE) / n
                    : equity[c][players];
                if (n > 0 && n < min_bucket) min_bucket = n;
            }
    free(fe);
    fprintf(stderr, "flop buckets: min %lld samples over non-empty (hand,bucket,players) cells\n",
            min_bucket);

    /* Pass 4: aggregate post-flop fold, using pass-1 equity + pass-3 flop equity. */
    fold_stats_t *pf = calloc(MAX_PLAYERS + 1, sizeof(fold_stats_t));
    run_postfold(target, 0x4ULL << 40, equity,
                 (const double (*)[NUM_HANDS][NUM_BUCKETS])flopeq, pf);
    for (int players = MIN_PLAYERS; players <= MAX_PLAYERS; players++)
        for (int c = 0; c < NUM_HANDS; c++) {
            double cnt = (double)pf[players].cnt[c];
            PFW[c][players] = cnt > 0 ? pf[players].win[c] / cnt : 0.0;
            PFT[c][players] = cnt > 0 ? (double)pf[players].tie_scaled[c] / (TIE_SCALE * cnt) : 0.0;
        }
    free(pf);
    free(se);
    free(sf);

    /* Print class-major, matching build_hands()/classify() order. */
    for (int c = 0; c < nh; c++) {
        for (int players = MIN_PLAYERS; players <= MAX_PLAYERS; players++) {
            printf("%s\t%d\t%.5f\t%.5f\t%.5f\t%.5f\t%.5f\t%.5f\t%.5f\t{",
                   hands[c].label, players,
                   W[c][players], T[c][players], L[c][players],
                   WF[c][players], TF[c][players],
                   PFW[c][players], PFT[c][players]);
            for (int j = 0; j < 9; j++)
                printf("%s\"%s\":%.5f", j ? "," : "", CATEGORY_NAMES[j], CAT[c][players][j]);
            printf("}\n");
        }
    }
    return 0;
}
