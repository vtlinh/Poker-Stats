#!/usr/bin/env python3
"""Turn the generator's equity.tsv into the shipped SQLite asset.

Usage:
    python3 tools/build_equity_db.py equity.tsv app/src/main/assets/poker_equity.db
"""
import os
import sqlite3
import sys


def main() -> None:
    tsv = sys.argv[1] if len(sys.argv) > 1 else "equity.tsv"
    out = sys.argv[2] if len(sys.argv) > 2 else "app/src/main/assets/poker_equity.db"
    os.makedirs(os.path.dirname(out), exist_ok=True)
    if os.path.exists(out):
        os.remove(out)

    con = sqlite3.connect(out)
    cur = con.cursor()
    cur.execute("PRAGMA user_version = 4")
    cur.execute(
        """CREATE TABLE equity (
            hand_class     TEXT NOT NULL,
            players        INTEGER NOT NULL,
            win            REAL NOT NULL,
            tie            REAL NOT NULL,
            lose           REAL NOT NULL,
            win_fold       REAL NOT NULL,
            tie_fold       REAL NOT NULL,
            post_fold_win  REAL NOT NULL,
            post_fold_tie  REAL NOT NULL,
            turn_fold_win  REAL NOT NULL,
            turn_fold_tie  REAL NOT NULL,
            river_fold_win REAL NOT NULL,
            river_fold_tie REAL NOT NULL,
            categories     TEXT NOT NULL,
            PRIMARY KEY (hand_class, players)
        )"""
    )
    rows = 0
    with open(tsv) as f:
        for line in f:
            (key, players, win, tie, lose, win_fold, tie_fold,
             post_fold_win, post_fold_tie, turn_fold_win, turn_fold_tie,
             river_fold_win, river_fold_tie, cats) = line.rstrip("\n").split("\t")
            cur.execute(
                "INSERT INTO equity VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                (key, int(players), float(win), float(tie), float(lose),
                 float(win_fold), float(tie_fold),
                 float(post_fold_win), float(post_fold_tie),
                 float(turn_fold_win), float(turn_fold_tie),
                 float(river_fold_win), float(river_fold_tie), cats),
            )
            rows += 1
    con.commit()
    cur.execute("PRAGMA integrity_check")
    assert cur.fetchone()[0] == "ok", "integrity check failed"
    con.close()
    print(f"wrote {rows} rows to {out} ({os.path.getsize(out)} bytes)")


if __name__ == "__main__":
    main()
