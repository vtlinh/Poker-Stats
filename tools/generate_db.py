#!/usr/bin/env python3
"""Compile the C evaluator, Monte-Carlo the equities, and build the SQLite asset.

A pure-Python convenience wrapper around the same steps as `make db`:

    python3 tools/generate_db.py --trials 1000000

The app ships the resulting app/src/main/assets/poker_equity.db and does nothing
but look rows up from it.
"""
import argparse
import os
import subprocess

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_ASSET = os.path.normpath(
    os.path.join(HERE, "..", "app", "src", "main", "assets", "poker_equity.db")
)


def compile_c() -> str:
    binary = os.path.join(HERE, "equity")
    cc = os.environ.get("CC", "cc")
    subprocess.run(
        [cc, "-O3", "-fopenmp", "-Wall", "-o", binary, "equity.c"],
        check=True,
        cwd=HERE,
    )
    return binary


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--trials", type=int, default=1_000_000,
                    help="Monte-Carlo deals per cell (default 1,000,000)")
    ap.add_argument("--out", default=DEFAULT_ASSET, help="output SQLite path")
    args = ap.parse_args()

    binary = compile_c()
    print(f"self-test...")
    subprocess.run([binary, "--selftest"], check=True, cwd=HERE)

    tsv = os.path.join(HERE, "equity.tsv")
    print(f"simulating {args.trials:,} deals/cell...")
    with open(tsv, "w") as f:
        subprocess.run([binary, str(args.trials)], check=True, stdout=f, cwd=HERE)

    subprocess.run(["python3", "build_equity_db.py", tsv, args.out], check=True, cwd=HERE)


if __name__ == "__main__":
    main()
