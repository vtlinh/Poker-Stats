/*
 * equity.c — fast Monte-Carlo generator for the precomputed preflop-equity table.
 *
 * For each total player count (2..MAX_PLAYERS) it deals whole random tables and
 * records the win/tie/lose equity and final-hand-category distribution for
 * *every* player at once, aggregating into the 169 canonical starting-hand
 * classes. It prints one TSV row per (hand, players) to stdout:
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

/* Full-table games needed so the rarest class (a pocket pair — 6 of the 1326
 * two-card combos) is sampled ~`target` times. Integer math, no libm. */
static long games_for(int players, long target) {
    long long num = (long long)target * 1326;
    long long den = 6LL * players;
    return (long)((num + den - 1) / den);
}

/* Pass 1: standard equity + category distribution for every class at `players`. */
static void run_equity(int players, long target, uint64_t seed_base, stats_t *out) {
    int block = 5 + 2 * players;
    int gps = 52 / block;                 /* disjoint games per shuffle */
    long units = (games_for(players, target) + gps - 1) / gps;
    memset(out, 0, sizeof(*out));

    #pragma omp parallel
    {
        stats_t loc;
        memset(&loc, 0, sizeof(loc));
        #pragma omp for schedule(dynamic, 512)
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
                int score[MAX_PLAYERS], cls[MAX_PLAYERS];
                int mx = -1, tc = 0;
                for (int p = 0; p < players; p++) {
                    int a = deck[base + 5 + 2 * p];
                    int b = deck[base + 5 + 2 * p + 1];
                    hand[0] = a; hand[1] = b;
                    int s = evaluate(hand, 7);
                    score[p] = s; cls[p] = classify(a, b);
                    if (s > mx) { mx = s; tc = 1; }
                    else if (s == mx) tc++;
                }
                for (int p = 0; p < players; p++) {
                    int c = cls[p];
                    loc.cnt[c]++;
                    loc.cat[c][score[p] >> CATEGORY_SHIFT]++;
                    if (score[p] == mx) {
                        if (tc == 1) loc.win[c]++;
                        else loc.tie_scaled[c] += TIE_SCALE / tc; /* 1/k split */
                    }
                }
            }
        }
        #pragma omp critical
        for (int c = 0; c < NUM_HANDS; c++) {
            out->cnt[c] += loc.cnt[c];
            out->win[c] += loc.win[c];
            out->tie_scaled[c] += loc.tie_scaled[c];
            for (int j = 0; j < 9; j++) out->cat[c][j] += loc.cat[c][j];
        }
    }
}

/* Pass 2: fold-adjusted equity. Everyone whose class equity is below 1/players
 * folds pre-flop — the hero included, so a folding hero simply loses. `equity`
 * is the win+tie table from pass 1, indexed by class. */
static void run_fold(int players, long target, uint64_t seed_base,
                     const double *equity, fold_stats_t *out) {
    int block = 5 + 2 * players;
    int gps = 52 / block;
    long units = (games_for(players, target) + gps - 1) / gps;
    double thresh = 1.0 / players;
    memset(out, 0, sizeof(*out));

    #pragma omp parallel
    {
        fold_stats_t loc;
        memset(&loc, 0, sizeof(loc));
        #pragma omp for schedule(dynamic, 512)
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
                int score[MAX_PLAYERS], cls[MAX_PLAYERS], stay[MAX_PLAYERS];
                for (int p = 0; p < players; p++) {
                    int a = deck[base + 5 + 2 * p];
                    int b = deck[base + 5 + 2 * p + 1];
                    hand[0] = a; hand[1] = b;
                    score[p] = evaluate(hand, 7);
                    cls[p] = classify(a, b);
                    stay[p] = equity[cls[p]] >= thresh;
                }
                for (int p = 0; p < players; p++) {
                    int c = cls[p];
                    loc.cnt[c]++;
                    if (!stay[p]) continue;   /* hero folds -> takes nothing */
                    int mx = -1, tc = 0;      /* best among staying opponents */
                    for (int q = 0; q < players; q++) {
                        if (q == p || !stay[q]) continue;
                        if (score[q] > mx) { mx = score[q]; tc = 1; }
                        else if (score[q] == mx) tc++;
                    }
                    if (mx < 0 || score[p] > mx) loc.win[c]++; /* uncontested or best */
                    else if (score[p] == mx) loc.tie_scaled[c] += TIE_SCALE / (tc + 1);
                }
            }
        }
        #pragma omp critical
        for (int c = 0; c < NUM_HANDS; c++) {
            out->cnt[c] += loc.cnt[c];
            out->win[c] += loc.win[c];
            out->tie_scaled[c] += loc.tie_scaled[c];
        }
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
    static double CAT[NUM_HANDS][MAX_PLAYERS + 1][9];
    static double equity[NUM_HANDS][MAX_PLAYERS + 1]; /* win + tie, per class */

    /* Pass 1: standard equity + category distribution. Each (class,players) is
     * seeded from a disjoint block so output is deterministic across threads. */
    for (int players = MIN_PLAYERS; players <= MAX_PLAYERS; players++) {
        stats_t s;
        run_equity(players, target, ((uint64_t)players * 2) << 32, &s);
        for (int c = 0; c < NUM_HANDS; c++) {
            double cnt = (double)s.cnt[c];
            double w = s.win[c] / cnt;
            double t = (double)s.tie_scaled[c] / (TIE_SCALE * cnt);
            W[c][players] = w;
            T[c][players] = t;
            L[c][players] = 1.0 - w - t;
            equity[c][players] = w + t;
            for (int j = 0; j < 9; j++) CAT[c][players][j] = s.cat[c][j] / cnt;
        }
    }

    /* Pass 2: fold-adjusted equity, deciding folds from the pass-1 table. */
    for (int players = MIN_PLAYERS; players <= MAX_PLAYERS; players++) {
        double col[NUM_HANDS];
        for (int c = 0; c < NUM_HANDS; c++) col[c] = equity[c][players];
        fold_stats_t f;
        run_fold(players, target, (((uint64_t)players * 2) + 1) << 32, col, &f);
        for (int c = 0; c < NUM_HANDS; c++) {
            double cnt = (double)f.cnt[c];
            WF[c][players] = f.win[c] / cnt;
            TF[c][players] = (double)f.tie_scaled[c] / (TIE_SCALE * cnt);
        }
    }

    /* Print class-major, matching build_hands()/classify() order. */
    for (int c = 0; c < nh; c++) {
        for (int players = MIN_PLAYERS; players <= MAX_PLAYERS; players++) {
            printf("%s\t%d\t%.5f\t%.5f\t%.5f\t%.5f\t%.5f\t{",
                   hands[c].label, players,
                   W[c][players], T[c][players], L[c][players],
                   WF[c][players], TF[c][players]);
            for (int j = 0; j < 9; j++)
                printf("%s\"%s\":%.5f", j ? "," : "", CATEGORY_NAMES[j], CAT[c][players][j]);
            printf("}\n");
        }
    }
    return 0;
}
