"""Tests for the C equity generator.

Run with:  cd tools && python3 -m pytest -q
(also invoked by `make test` and the equity-tools CI workflow).
"""
import os
import re
import subprocess

import pytest

HERE = os.path.dirname(os.path.abspath(__file__))
BINARY = os.path.join(HERE, "equity")


@pytest.fixture(scope="session")
def equity_binary():
    cc = os.environ.get("CC", "cc")
    subprocess.run(
        [cc, "-O3", "-fopenmp", "-Wall", "-o", BINARY, "equity.c"],
        check=True, cwd=HERE,
    )
    return BINARY


def _generate(binary, trials):
    out = subprocess.run(
        [binary, str(trials)], cwd=HERE, capture_output=True, text=True, check=True
    ).stdout
    data = {}
    for line in out.splitlines():
        p = line.split("\t")
        data[(p[0], int(p[1]))] = {
            "win": float(p[2]), "tie": float(p[3]), "lose": float(p[4]),
            "win_fold": float(p[5]), "tie_fold": float(p[6]),
        }
    return data


def test_selftest_passes(equity_binary):
    # Evaluator vs brute force + known-hand assertions (exit 0 on success).
    assert subprocess.run([equity_binary, "--selftest"], cwd=HERE).returncode == 0


def test_shape(equity_binary):
    data = _generate(equity_binary, 20_000)
    assert len(data) == 169 * 5  # 169 hands x players 2..6
    assert {p for (_, p) in data} == {2, 3, 4, 5, 6}


def test_canonical_equities(equity_binary):
    data = _generate(equity_binary, 80_000)

    def win_incl_ties(hand, players):  # the app counts a tie as a win
        r = data[(hand, players)]
        return r["win"] + r["tie"]

    # Pocket aces heads-up ~85%.
    assert win_incl_ties("AA", 2) == pytest.approx(0.852, abs=0.01)
    # AKs heads-up ~67%.
    assert win_incl_ties("AKs", 2) == pytest.approx(0.67, abs=0.01)
    # 72o is the worst starting hand.
    assert win_incl_ties("72o", 2) < 0.40
    # AA 6-handed ~49%.
    assert win_incl_ties("AA", 6) == pytest.approx(0.49, abs=0.02)
    # win/tie/lose form a probability distribution (values are printed rounded
    # to 5 decimals, so allow for that rounding).
    for r in data.values():
        assert r["win"] + r["tie"] + r["lose"] == pytest.approx(1.0, abs=1e-4)


def test_more_opponents_lowers_equity(equity_binary):
    data = _generate(equity_binary, 40_000)
    heads_up = data[("AA", 2)]["win"] + data[("AA", 2)]["tie"]
    six_way = data[("AA", 6)]["win"] + data[("AA", 6)]["tie"]
    assert heads_up > six_way


def test_fold_equity_model(equity_binary):
    # Everyone (including the hero) folds hands below break-even (1/N):
    #  - a clearly-below-break-even hero folds and loses -> fold equity == 0
    #  - a clearly-above hero plays; opponents folding weak hands only help, so
    #    the fold-adjusted rate is >= the plain rate (allow for MC noise).
    data = _generate(equity_binary, 40_000)
    for (hand, players), r in data.items():
        std = r["win"] + r["tie"]
        fold = r["win_fold"] + r["tie_fold"]
        threshold = 1.0 / players
        assert 0.0 <= fold <= 1.0
        if std < threshold - 0.02:
            assert fold == 0.0, f"{hand} {players}: expected fold 0, got {fold}"
        elif std > threshold + 0.02:
            assert fold >= std - 0.02, f"{hand} {players}: fold {fold} < std {std}"


def test_fold_boosts_multiway_more_than_headsup(equity_binary):
    # AA clears break-even at every table size, so it always plays. Folding
    # matters more as the table grows (more opponents to fold), so AA gains more
    # equity from the fold assumption six-handed than heads-up.
    data = _generate(equity_binary, 60_000)

    def gain(hand, players):
        r = data[(hand, players)]
        return (r["win_fold"] + r["tie_fold"]) - (r["win"] + r["tie"])

    assert gain("AA", 6) > gain("AA", 2)


def test_weak_hero_folds_to_zero(equity_binary):
    # 72o is far below break-even everywhere, so the hero always folds it.
    data = _generate(equity_binary, 40_000)
    for players in (2, 3, 4, 5, 6):
        r = data[("72o", players)]
        assert r["win_fold"] + r["tie_fold"] == 0.0


def _min_samples(binary, target):
    """Run the generator and return (min_samples, stderr) from its summary."""
    res = subprocess.run(
        [binary, str(target)], cwd=HERE, capture_output=True, text=True, check=True
    )
    m = re.search(r"min (\d+) samples/class", res.stderr)
    assert m, f"no sampling summary on stderr: {res.stderr!r}"
    return int(m.group(1)), res.stderr


def test_every_class_well_sampled(equity_binary):
    # Guard against a future change that under-samples any of the 169 classes:
    # at a normal target every class must clear the 1000-sample floor.
    min_samples, stderr = _min_samples(equity_binary, 20_000)
    assert min_samples >= 1000, stderr
    assert "WARNING" not in stderr


def test_undersampling_alerts(equity_binary):
    # With a tiny target the rarest class falls below 1000 samples and the
    # generator must alert on stderr.
    min_samples, stderr = _min_samples(equity_binary, 500)
    assert min_samples < 1000
    assert "WARNING" in stderr
