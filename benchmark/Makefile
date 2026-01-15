CC = clang
CFLAGS = -O3 -flto -march=native -fomit-frame-pointer -fno-stack-protector  -DNDEBUG
BINDIR = bin
BUILDDIR = build
RESULTSDIR = results/$(shell date +%Y-%m-%d)

# Conditional export flags for hyperfine (set LOG_BENCH_RESULTS=1 to enable)
ifdef LOG_BENCH_RESULTS
EXPORT_FLAGS = --export-json $(RESULTSDIR)/$(1).json \
               --export-csv $(RESULTSDIR)/$(1).csv \
               --export-markdown $(RESULTSDIR)/$(1).md
RESULTS_DEP = | $(RESULTSDIR)
else
EXPORT_FLAGS =
RESULTS_DEP =
endif

all: $(BINDIR)/fizzbuzz-c $(BINDIR)/fizzbuzz2-c $(BINDIR)/fizzbuzz-go $(BINDIR)/fizzbuzz2-go \
     $(BINDIR)/ackermann-c $(BINDIR)/ackermann-go $(BINDIR)/ackermann-c-chacho $(BINDIR)/ackermann-unfair-c $(BINDIR)/ackermann-rs \
     $(BINDIR)/sieve-c $(BINDIR)/sieve-go $(BINDIR)/sieve-opt-go $(BINDIR)/sieve-rs \
     $(BINDIR)/quicksort-c $(BINDIR)/matmul-c $(BINDIR)/matmul-opt-c $(BINDIR)/matmul-restricted-c $(BINDIR)/matmul-go $(BINDIR)/matmul-bce-go $(BINDIR)/matmul-opt-go \
     $(BINDIR)/nqueens-c $(BINDIR)/euclidean-ext-c

mml: $(BINDIR)/sieve-mml $(BINDIR)/quicksort-mml $(BINDIR)/matmul-mml \
     $(BINDIR)/matmul-opt-mml $(BINDIR)/nqueens-mml $(BINDIR)/euclidean-ext-mml \
     $(BINDIR)/ackermann-mml \
     $(SELF_SIEVE_BINARIES) $(SELF_MATMUL_BINARIES) $(SELF_MATMUL_OPT_BINARIES)

$(BINDIR):
	mkdir -p $(BINDIR)

$(RESULTSDIR):
	mkdir -p $(RESULTSDIR)

# Fizzbuzz
$(BINDIR)/fizzbuzz-c: fizzbuzz.c | $(BINDIR)
	$(CC) $(CFLAGS) -o $@ $<

$(BINDIR)/fizzbuzz2-c: fizzbuzz2.c | $(BINDIR)
	$(CC) $(CFLAGS) -o $@ $<

$(BINDIR)/fizzbuzz-go: fizzbuzz.go | $(BINDIR)
	go build -o $@ $<

$(BINDIR)/fizzbuzz2-go: fizzbuzz2.go | $(BINDIR)
	go build -o $@ $<

# Ackermann
$(BINDIR)/ackermann-c: ackermann.c | $(BINDIR)
	$(CC) $(CFLAGS) -o $@ $<

$(BINDIR)/ackermann-c-chacho: ackermann-c-chacho.c | $(BINDIR)
	$(CC) $(CFLAGS) -o $@ $<

$(BINDIR)/ackermann-unfair-c: ackermann-unfair.c | $(BINDIR)
	$(CC) $(CFLAGS) -o $@ $<

$(BINDIR)/ackermann-go: ackermann.go | $(BINDIR)
	go build -o $@ $<

$(BINDIR)/ackermann-rs: ackermann.rs | $(BINDIR)
	rustc -O -o $@ $<

$(BINDIR)/ackermann-mml: ackermann.mml | $(BINDIR)
	mmlc bin -b $(BUILDDIR) -o $@ $<

# Sieve
$(BINDIR)/sieve-c: sieve.c | $(BINDIR)
	$(CC) $(CFLAGS) -o $@ $<

$(BINDIR)/sieve-go: sieve.go | $(BINDIR)
	go build -o $@ $<

$(BINDIR)/sieve-opt-go: sieve-opt.go | $(BINDIR)
	go build -o $@ $<

$(BINDIR)/sieve-rs: sieve.rs | $(BINDIR)
	rustc -O -o $@ $<

$(BINDIR)/sieve-mml: sieve.mml | $(BINDIR)
	mmlc bin -b $(BUILDDIR) -o $@ $<

# Self benchmark binaries (MML with various opt/TCO knobs)
SELF_SIEVE_BINARIES = \
	$(BINDIR)/sieve-mml-O0-tco \
	$(BINDIR)/sieve-mml-O0-no-tco \
	$(BINDIR)/sieve-mml-O1-tco \
	$(BINDIR)/sieve-mml-O1-no-tco \
	$(BINDIR)/sieve-mml-O2-tco \
	$(BINDIR)/sieve-mml-O2-no-tco \
	$(BINDIR)/sieve-mml-O3-tco \
	$(BINDIR)/sieve-mml-O3-no-tco

SELF_MATMUL_BINARIES = \
	$(BINDIR)/matmul-mml-O0-tco \
	$(BINDIR)/matmul-mml-O0-no-tco \
	$(BINDIR)/matmul-mml-O1-tco \
	$(BINDIR)/matmul-mml-O1-no-tco \
	$(BINDIR)/matmul-mml-O2-tco \
	$(BINDIR)/matmul-mml-O2-no-tco \
	$(BINDIR)/matmul-mml-O3-tco \
	$(BINDIR)/matmul-mml-O3-no-tco

SELF_MATMUL_OPT_BINARIES = \
	$(BINDIR)/matmul-opt-mml-O0-tco \
	$(BINDIR)/matmul-opt-mml-O0-no-tco \
	$(BINDIR)/matmul-opt-mml-O1-tco \
	$(BINDIR)/matmul-opt-mml-O1-no-tco \
	$(BINDIR)/matmul-opt-mml-O2-tco \
	$(BINDIR)/matmul-opt-mml-O2-no-tco \
	$(BINDIR)/matmul-opt-mml-O3-tco \
	$(BINDIR)/matmul-opt-mml-O3-no-tco

$(BINDIR)/sieve-mml-O0-tco: sieve.mml | $(BINDIR)
	mmlc bin -b $(BUILDDIR) -O 0 -o $@ $<

$(BINDIR)/sieve-mml-O0-no-tco: sieve.mml | $(BINDIR)
	mmlc bin -b $(BUILDDIR) -O 0 --no-tco -o $@ $<

$(BINDIR)/sieve-mml-O1-tco: sieve.mml | $(BINDIR)
	mmlc bin -b $(BUILDDIR) -O 1 -o $@ $<

$(BINDIR)/sieve-mml-O1-no-tco: sieve.mml | $(BINDIR)
	mmlc bin -b $(BUILDDIR) -O 1 --no-tco -o $@ $<

$(BINDIR)/sieve-mml-O2-tco: sieve.mml | $(BINDIR)
	mmlc bin -b $(BUILDDIR) -O 2 -o $@ $<

$(BINDIR)/sieve-mml-O2-no-tco: sieve.mml | $(BINDIR)
	mmlc bin -b $(BUILDDIR) -O 2 --no-tco -o $@ $<

$(BINDIR)/sieve-mml-O3-tco: sieve.mml | $(BINDIR)
	mmlc bin -b $(BUILDDIR) -O 3 -o $@ $<

$(BINDIR)/sieve-mml-O3-no-tco: sieve.mml | $(BINDIR)
	mmlc bin -b $(BUILDDIR) -O 3 --no-tco -o $@ $<

# Quicksort
$(BINDIR)/quicksort-c: quicksort.c | $(BINDIR)
	$(CC) $(CFLAGS) -o $@ $<

$(BINDIR)/quicksort-mml: quicksort.mml | $(BINDIR)
	mmlc bin -b $(BUILDDIR) -o $@ $<

# Matrix Multiplication
$(BINDIR)/matmul-c: matmul.c | $(BINDIR)
	$(CC) $(CFLAGS) -o $@ $<

$(BINDIR)/matmul-opt-c: matmul-opt.c | $(BINDIR)
	$(CC) $(CFLAGS) -o $@ $<

$(BINDIR)/matmul-restricted-c: matmul-restricted.c | $(BINDIR)
	$(CC) $(CFLAGS) -o $@ $<

$(BINDIR)/matmul-go: matmul.go | $(BINDIR)
	go build -o $@ $<

$(BINDIR)/matmul-bce-go: matmul-bce.go | $(BINDIR)
	go build -o $@ $<

$(BINDIR)/matmul-opt-go: matmul-opt.go | $(BINDIR)
	go build -o $@ $<

$(BINDIR)/matmul-mml: mat-mul.mml | $(BINDIR)
	mmlc bin -b $(BUILDDIR) -o $@ $<

$(BINDIR)/matmul-opt-mml: mat-mul-opt.mml | $(BINDIR)
	mmlc bin -b $(BUILDDIR) -o $@ $<

$(BINDIR)/matmul-mml-O0-tco: mat-mul.mml | $(BINDIR)
	mmlc bin -b $(BUILDDIR) -O 0 -o $@ $<

$(BINDIR)/matmul-mml-O0-no-tco: mat-mul.mml | $(BINDIR)
	mmlc bin -b $(BUILDDIR) -O 0 --no-tco -o $@ $<

$(BINDIR)/matmul-mml-O1-tco: mat-mul.mml | $(BINDIR)
	mmlc bin -b $(BUILDDIR) -O 1 -o $@ $<

$(BINDIR)/matmul-mml-O1-no-tco: mat-mul.mml | $(BINDIR)
	mmlc bin -b $(BUILDDIR) -O 1 --no-tco -o $@ $<

$(BINDIR)/matmul-mml-O2-tco: mat-mul.mml | $(BINDIR)
	mmlc bin -b $(BUILDDIR) -O 2 -o $@ $<

$(BINDIR)/matmul-mml-O2-no-tco: mat-mul.mml | $(BINDIR)
	mmlc bin -b $(BUILDDIR) -O 2 --no-tco -o $@ $<

$(BINDIR)/matmul-mml-O3-tco: mat-mul.mml | $(BINDIR)
	mmlc bin -b $(BUILDDIR) -O 3 -o $@ $<

$(BINDIR)/matmul-mml-O3-no-tco: mat-mul.mml | $(BINDIR)
	mmlc bin -b $(BUILDDIR) -O 3 --no-tco -o $@ $<

$(BINDIR)/matmul-opt-mml-O0-tco: mat-mul-opt.mml | $(BINDIR)
	mmlc bin -b $(BUILDDIR) -O 0 -o $@ $<

$(BINDIR)/matmul-opt-mml-O0-no-tco: mat-mul-opt.mml | $(BINDIR)
	mmlc bin -b $(BUILDDIR) -O 0 --no-tco -o $@ $<

$(BINDIR)/matmul-opt-mml-O1-tco: mat-mul-opt.mml | $(BINDIR)
	mmlc bin -b $(BUILDDIR) -O 1 -o $@ $<

$(BINDIR)/matmul-opt-mml-O1-no-tco: mat-mul-opt.mml | $(BINDIR)
	mmlc bin -b $(BUILDDIR) -O 1 --no-tco -o $@ $<

$(BINDIR)/matmul-opt-mml-O2-tco: mat-mul-opt.mml | $(BINDIR)
	mmlc bin -b $(BUILDDIR) -O 2 -o $@ $<

$(BINDIR)/matmul-opt-mml-O2-no-tco: mat-mul-opt.mml | $(BINDIR)
	mmlc bin -b $(BUILDDIR) -O 2 --no-tco -o $@ $<

$(BINDIR)/matmul-opt-mml-O3-tco: mat-mul-opt.mml | $(BINDIR)
	mmlc bin -b $(BUILDDIR) -O 3 -o $@ $<

$(BINDIR)/matmul-opt-mml-O3-no-tco: mat-mul-opt.mml | $(BINDIR)
	mmlc bin -b $(BUILDDIR) -O 3 --no-tco -o $@ $<

# N-Queens
$(BINDIR)/nqueens-c: nqueens.c | $(BINDIR)
	$(CC) $(CFLAGS) -o $@ $<

$(BINDIR)/nqueens-mml: nqueens.mml | $(BINDIR)
	mmlc bin -b $(BUILDDIR) -o $@ $<

# Euclidean Extended GCD
$(BINDIR)/euclidean-ext-c: euclidean-ext.c | $(BINDIR)
	$(CC) $(CFLAGS) -o $@ $<

$(BINDIR)/euclidean-ext-mml: euclidean-ext.mml | $(BINDIR)
	mmlc bin -b $(BUILDDIR) -o $@ $<

# Benchmarks
bench-sieve: $(BINDIR)/sieve-c $(BINDIR)/sieve-go $(BINDIR)/sieve-rs $(BINDIR)/sieve-mml $(RESULTS_DEP)
	hyperfine -N --warmup 20 --runs 500 \
		$(call EXPORT_FLAGS,sieve) \
		'$(BINDIR)/sieve-c' \
		'$(BINDIR)/sieve-mml' \
		'$(BINDIR)/sieve-rs' \
		'$(BINDIR)/sieve-go'

bench-sieve-time: $(BINDIR)/sieve-c $(BINDIR)/sieve-go $(BINDIR)/sieve-rs $(BINDIR)/sieve-mml
	/usr/bin/time -l $(BINDIR)/sieve-c
	/usr/bin/time -l $(BINDIR)/sieve-mml
	/usr/bin/time -l $(BINDIR)/sieve-rs
	/usr/bin/time -l $(BINDIR)/sieve-go

bench-self-sieve: $(SELF_SIEVE_BINARIES) $(RESULTS_DEP)
	hyperfine -N --warmup 20 --runs 500 \
		$(call EXPORT_FLAGS,self) \
		'$(BINDIR)/sieve-mml-O0-tco' \
		'$(BINDIR)/sieve-mml-O0-no-tco' \
		'$(BINDIR)/sieve-mml-O1-tco' \
		'$(BINDIR)/sieve-mml-O1-no-tco' \
		'$(BINDIR)/sieve-mml-O2-tco' \
		'$(BINDIR)/sieve-mml-O2-no-tco' \
		'$(BINDIR)/sieve-mml-O3-tco' \
		'$(BINDIR)/sieve-mml-O3-no-tco'

bench-self-sieve-time: $(SELF_SIEVE_BINARIES)
	/usr/bin/time -l $(BINDIR)/sieve-mml-O0-tco
	/usr/bin/time -l $(BINDIR)/sieve-mml-O0-no-tco
	/usr/bin/time -l $(BINDIR)/sieve-mml-O1-tco
	/usr/bin/time -l $(BINDIR)/sieve-mml-O1-no-tco
	/usr/bin/time -l $(BINDIR)/sieve-mml-O2-tco
	/usr/bin/time -l $(BINDIR)/sieve-mml-O2-no-tco
	/usr/bin/time -l $(BINDIR)/sieve-mml-O3-tco
	/usr/bin/time -l $(BINDIR)/sieve-mml-O3-no-tco

bench-self-matmul: $(SELF_MATMUL_BINARIES) $(RESULTS_DEP)
	hyperfine -N --warmup 20 --runs 500 \
		$(call EXPORT_FLAGS,self-matmul) \
		'$(BINDIR)/matmul-mml-O0-tco' \
		'$(BINDIR)/matmul-mml-O0-no-tco' \
		'$(BINDIR)/matmul-mml-O1-tco' \
		'$(BINDIR)/matmul-mml-O1-no-tco' \
		'$(BINDIR)/matmul-mml-O2-tco' \
		'$(BINDIR)/matmul-mml-O2-no-tco' \
		'$(BINDIR)/matmul-mml-O3-tco' \
		'$(BINDIR)/matmul-mml-O3-no-tco'

bench-self-matmul-time: $(SELF_MATMUL_BINARIES)
	/usr/bin/time -l $(BINDIR)/matmul-mml-O0-tco
	/usr/bin/time -l $(BINDIR)/matmul-mml-O0-no-tco
	/usr/bin/time -l $(BINDIR)/matmul-mml-O1-tco
	/usr/bin/time -l $(BINDIR)/matmul-mml-O1-no-tco
	/usr/bin/time -l $(BINDIR)/matmul-mml-O2-tco
	/usr/bin/time -l $(BINDIR)/matmul-mml-O2-no-tco
	/usr/bin/time -l $(BINDIR)/matmul-mml-O3-tco
	/usr/bin/time -l $(BINDIR)/matmul-mml-O3-no-tco

bench-self-matmul-opt: $(SELF_MATMUL_OPT_BINARIES) $(RESULTS_DEP)
	hyperfine -N --warmup 20 --runs 500 \
		$(call EXPORT_FLAGS,self-matmul-opt) \
		'$(BINDIR)/matmul-opt-mml-O0-tco' \
		'$(BINDIR)/matmul-opt-mml-O0-no-tco' \
		'$(BINDIR)/matmul-opt-mml-O1-tco' \
		'$(BINDIR)/matmul-opt-mml-O1-no-tco' \
		'$(BINDIR)/matmul-opt-mml-O2-tco' \
		'$(BINDIR)/matmul-opt-mml-O2-no-tco' \
		'$(BINDIR)/matmul-opt-mml-O3-tco' \
		'$(BINDIR)/matmul-opt-mml-O3-no-tco'

bench-self-matmul-opt-time: $(SELF_MATMUL_OPT_BINARIES)
	/usr/bin/time -l $(BINDIR)/matmul-opt-mml-O0-tco
	/usr/bin/time -l $(BINDIR)/matmul-opt-mml-O0-no-tco
	/usr/bin/time -l $(BINDIR)/matmul-opt-mml-O1-tco
	/usr/bin/time -l $(BINDIR)/matmul-opt-mml-O1-no-tco
	/usr/bin/time -l $(BINDIR)/matmul-opt-mml-O2-tco
	/usr/bin/time -l $(BINDIR)/matmul-opt-mml-O2-no-tco
	/usr/bin/time -l $(BINDIR)/matmul-opt-mml-O3-tco
	/usr/bin/time -l $(BINDIR)/matmul-opt-mml-O3-no-tco

bench-quicksort: $(BINDIR)/quicksort-c $(BINDIR)/quicksort-mml $(RESULTS_DEP)
	hyperfine -N --warmup 10 --runs 50 \
		$(call EXPORT_FLAGS,quicksort) \
		'$(BINDIR)/quicksort-c' \
		'$(BINDIR)/quicksort-mml'

bench-quicksort-time: $(BINDIR)/quicksort-c $(BINDIR)/quicksort-mml
	/usr/bin/time -l $(BINDIR)/quicksort-c
	/usr/bin/time -l $(BINDIR)/quicksort-mml

bench-matmul: $(BINDIR)/matmul-c $(BINDIR)/matmul-restricted-c $(BINDIR)/matmul-go \
	$(BINDIR)/matmul-bce-go $(BINDIR)/matmul-opt-go $(BINDIR)/matmul-mml $(BINDIR)/matmul-opt-mml $(BINDIR)/matmul-opt-c $(RESULTS_DEP)
	hyperfine -N --warmup 20 --runs 50 \
		$(call EXPORT_FLAGS,matmul) \
		'$(BINDIR)/matmul-c' \
		'$(BINDIR)/matmul-opt-c' \
		'$(BINDIR)/matmul-restricted-c' \
		'$(BINDIR)/matmul-mml' \
		'$(BINDIR)/matmul-opt-mml' \
		'$(BINDIR)/matmul-go' \
		'$(BINDIR)/matmul-bce-go' \
		'$(BINDIR)/matmul-opt-go'

bench-matmul-time: $(BINDIR)/matmul-c $(BINDIR)/matmul-restricted-c $(BINDIR)/matmul-go \
	$(BINDIR)/matmul-bce-go $(BINDIR)/matmul-opt-go $(BINDIR)/matmul-mml $(BINDIR)/matmul-opt-mml \
	$(BINDIR)/matmul-opt-c
	/usr/bin/time -l $(BINDIR)/matmul-c
	/usr/bin/time -l $(BINDIR)/matmul-opt-c
	/usr/bin/time -l $(BINDIR)/matmul-restricted-c
	/usr/bin/time -l $(BINDIR)/matmul-mml
	/usr/bin/time -l $(BINDIR)/matmul-opt-mml
	/usr/bin/time -l $(BINDIR)/matmul-go
	/usr/bin/time -l $(BINDIR)/matmul-bce-go
	/usr/bin/time -l $(BINDIR)/matmul-opt-go

bench-nqueens: $(BINDIR)/nqueens-c $(BINDIR)/nqueens-mml $(RESULTS_DEP)
	hyperfine -N --warmup 5 --runs 20 \
		$(call EXPORT_FLAGS,nqueens) \
		'$(BINDIR)/nqueens-c' \
		'$(BINDIR)/nqueens-mml'

bench-nqueens-time: $(BINDIR)/nqueens-c $(BINDIR)/nqueens-mml
	/usr/bin/time -l $(BINDIR)/nqueens-c
	/usr/bin/time -l $(BINDIR)/nqueens-mml

bench-euclidean: $(BINDIR)/euclidean-ext-c $(BINDIR)/euclidean-ext-mml $(RESULTS_DEP)
	hyperfine -N --warmup 10 --runs 50 \
		$(call EXPORT_FLAGS,euclidean) \
		'$(BINDIR)/euclidean-ext-c' \
		'$(BINDIR)/euclidean-ext-mml'

bench-euclidean-time: $(BINDIR)/euclidean-ext-c $(BINDIR)/euclidean-ext-mml
	/usr/bin/time -l $(BINDIR)/euclidean-ext-c
	/usr/bin/time -l $(BINDIR)/euclidean-ext-mml

bench-ackermann: $(BINDIR)/ackermann-c $(BINDIR)/ackermann-c-chacho $(BINDIR)/ackermann-mml \
                 $(BINDIR)/ackermann-rs $(BINDIR)/ackermann-go $(RESULTS_DEP)
	hyperfine -N --warmup 5 --runs 20 \
		$(call EXPORT_FLAGS,ackermann) \
		'$(BINDIR)/ackermann-c' \
		'$(BINDIR)/ackermann-c-chacho' \
		'$(BINDIR)/ackermann-mml' \
		'$(BINDIR)/ackermann-rs' \
		'$(BINDIR)/ackermann-go'

bench-ackermann-time: $(BINDIR)/ackermann-c $(BINDIR)/ackermann-c-chacho $(BINDIR)/ackermann-mml \
                      $(BINDIR)/ackermann-rs $(BINDIR)/ackermann-go
	/usr/bin/time -l $(BINDIR)/ackermann-c
	/usr/bin/time -l $(BINDIR)/ackermann-c-chacho
	/usr/bin/time -l $(BINDIR)/ackermann-mml
	/usr/bin/time -l $(BINDIR)/ackermann-rs
	/usr/bin/time -l $(BINDIR)/ackermann-go

bench: bench-sieve bench-quicksort bench-matmul bench-nqueens bench-euclidean bench-ackermann \
       bench-self-sieve bench-self-matmul bench-self-matmul-opt

bench-time: bench-sieve-time bench-quicksort-time bench-matmul-time bench-nqueens-time \
            bench-euclidean-time bench-ackermann-time bench-self-sieve-time bench-self-matmul-time \
            bench-self-matmul-opt-time

clean:
	rm -rf $(BINDIR) $(BUILDDIR)

.PHONY: all mml clean bench bench-time bench-sieve bench-sieve-time bench-quicksort \
	bench-quicksort-time bench-matmul bench-matmul-time bench-nqueens bench-nqueens-time \
	bench-euclidean bench-euclidean-time bench-self-sieve bench-self-sieve-time \
	bench-self-matmul bench-self-matmul-time bench-self-matmul-opt bench-self-matmul-opt-time
