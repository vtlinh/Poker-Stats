# Build & run the precomputed-equity generator.
#
#   make            compile the C evaluator
#   make selftest   compile + run the evaluator self-test
#   make test       self-test + Python tests (pytest)
#   make db         generate the SQLite asset with TRIALS deals/cell (default 1M)
#   make clean      remove build artifacts

CC     ?= cc
CFLAGS ?= -O3 -fopenmp -Wall
TRIALS ?= 1000000
ASSET  := ../app/src/main/assets/poker_equity.db

equity: equity.c
	$(CC) $(CFLAGS) -o equity equity.c

.PHONY: selftest test db clean
selftest: equity
	./equity --selftest

test: equity
	./equity --selftest
	python3 -m pytest -q

db: equity
	./equity $(TRIALS) > equity.tsv
	python3 build_equity_db.py equity.tsv $(ASSET)

clean:
	rm -f equity equity.tsv
