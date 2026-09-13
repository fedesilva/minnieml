CC = clang
CFLAGS = -O3 -flto -march=native -fomit-frame-pointer -fno-stack-protector  -DNDEBUG
BINDIR = bin
JAVA ?= java
JAVAC ?= javac
NATIVE_IMAGE ?= native-image
NATIVE_IMAGE_FLAGS ?= -O3 -march=native --no-fallback
PYTHON ?= python3

# Include Python in drag races only when BENCH_SLOW=1.
ifeq ($(BENCH_SLOW),1)
PYTHON_BENCH = '$(PYTHON) $(1).py'
endif

# MML optimization level: 1 on Apple Silicon, 3 on x86.
# Override with the MML_OPT env var or `make MML_OPT=...`.
ifeq ($(shell uname -s)$(shell uname -m),Darwinarm64)
MML_OPT ?= 1
else
MML_OPT ?= 3
endif
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
     $(BINDIR)/quicksort-c $(BINDIR)/quicksort-go $(BINDIR)/quicksort-rs \
     $(BINDIR)/matmul-c $(BINDIR)/matmul-opt-c $(BINDIR)/matmul-restricted-c $(BINDIR)/matmul-go $(BINDIR)/matmul-bce-go $(BINDIR)/matmul-opt-go \
     $(BINDIR)/matmul-opt-bce-go $(BINDIR)/matmul-rs $(BINDIR)/matmul-opt-rs \
     $(BINDIR)/nqueens-c $(BINDIR)/nqueens-go $(BINDIR)/euclidean-ext-c \
     $(BINDIR)/Sieve.class $(BINDIR)/Quicksort.class $(BINDIR)/Matmul.class \
     $(BINDIR)/sieve-java-native $(BINDIR)/quicksort-java-native $(BINDIR)/matmul-java-native

java: $(BINDIR)/Sieve.class $(BINDIR)/Quicksort.class $(BINDIR)/Matmul.class

java-native: $(BINDIR)/sieve-java-native $(BINDIR)/quicksort-java-native $(BINDIR)/matmul-java-native

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
	mmlc -I -b $(BUILDDIR) -O$(MML_OPT) -o $@ $<

# Sieve
$(BINDIR)/Sieve.class: Sieve.java | $(BINDIR)
	$(JAVAC) -d $(BINDIR) $<

$(BINDIR)/sieve-java-native: $(BINDIR)/Sieve.class
	$(NATIVE_IMAGE) $(NATIVE_IMAGE_FLAGS) -cp $(BINDIR) -o $@ Sieve

$(BINDIR)/sieve-c: sieve.c | $(BINDIR)
	$(CC) $(CFLAGS) -o $@ $<

$(BINDIR)/sieve-go: sieve.go | $(BINDIR)
	go build -o $@ $<

$(BINDIR)/sieve-opt-go: sieve-opt.go | $(BINDIR)
	go build -o $@ $<

$(BINDIR)/sieve-rs: sieve.rs | $(BINDIR)
	rustc -O -o $@ $<

$(BINDIR)/sieve-mml: sieve.mml | $(BINDIR)
	mmlc -I -b $(BUILDDIR) -O$(MML_OPT) -o $@ $<

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
	mmlc -I -b $(BUILDDIR) -O0 -o $@ $<

$(BINDIR)/sieve-mml-O0-no-tco: sieve.mml | $(BINDIR)
	mmlc -I -b $(BUILDDIR) -O0 --no-tco -o $@ $<

$(BINDIR)/sieve-mml-O1-tco: sieve.mml | $(BINDIR)
	mmlc -I -b $(BUILDDIR) -O1 -o $@ $<

$(BINDIR)/sieve-mml-O1-no-tco: sieve.mml | $(BINDIR)
	mmlc -I -b $(BUILDDIR) -O1 --no-tco -o $@ $<

$(BINDIR)/sieve-mml-O2-tco: sieve.mml | $(BINDIR)
	mmlc -I -b $(BUILDDIR) -O2 -o $@ $<

$(BINDIR)/sieve-mml-O2-no-tco: sieve.mml | $(BINDIR)
	mmlc -I -b $(BUILDDIR) -O2 --no-tco -o $@ $<

$(BINDIR)/sieve-mml-O3-tco: sieve.mml | $(BINDIR)
	mmlc -I -b $(BUILDDIR) -O3 -o $@ $<

$(BINDIR)/sieve-mml-O3-no-tco: sieve.mml | $(BINDIR)
	mmlc -I -b $(BUILDDIR) -O3 --no-tco -o $@ $<

# Quicksort
$(BINDIR)/Quicksort.class: Quicksort.java | $(BINDIR)
	$(JAVAC) -d $(BINDIR) $<

$(BINDIR)/quicksort-java-native: $(BINDIR)/Quicksort.class
	$(NATIVE_IMAGE) $(NATIVE_IMAGE_FLAGS) -cp $(BINDIR) -o $@ Quicksort

$(BINDIR)/quicksort-c: quicksort.c | $(BINDIR)
	$(CC) $(CFLAGS) -o $@ $<

$(BINDIR)/quicksort-go: quicksort.go | $(BINDIR)
	go build -o $@ $<

$(BINDIR)/quicksort-rs: quicksort.rs | $(BINDIR)
	rustc -O -o $@ $<

$(BINDIR)/quicksort-mml: quicksort.mml | $(BINDIR)
	mmlc -I -b $(BUILDDIR) -O$(MML_OPT) -o $@ $<

# Matrix Multiplication
$(BINDIR)/Matmul.class: Matmul.java | $(BINDIR)
	$(JAVAC) -d $(BINDIR) $<

$(BINDIR)/matmul-java-native: $(BINDIR)/Matmul.class
	$(NATIVE_IMAGE) $(NATIVE_IMAGE_FLAGS) -cp $(BINDIR) -o $@ Matmul

$(BINDIR)/matmul-c: matmul.c | $(BINDIR)
	$(CC) $(CFLAGS) -o $@ $<

$(BINDIR)/matmul-opt-c: matmul-opt.c | $(BINDIR)
	$(CC) $(CFLAGS) -o $@ $<

$(BINDIR)/matmul-restricted-c: matmul-restricted.c | $(BINDIR)
	$(CC) $(CFLAGS) -o $@ $<

$(BINDIR)/matmul-rs: matmul.rs | $(BINDIR)
	rustc -O -o $@ $<

$(BINDIR)/matmul-opt-rs: matmul-opt.rs | $(BINDIR)
	rustc -O -o $@ $<

$(BINDIR)/matmul-go: matmul.go | $(BINDIR)
	go build -o $@ $<

$(BINDIR)/matmul-bce-go: matmul-bce.go | $(BINDIR)
	go build -o $@ $<

$(BINDIR)/matmul-opt-go: matmul-opt.go | $(BINDIR)
	go build -o $@ $<

$(BINDIR)/matmul-opt-bce-go: matmul-opt-bce.go | $(BINDIR)
	go build -o $@ $<

$(BINDIR)/matmul-mml: mat-mul.mml | $(BINDIR)
	mmlc -I -b $(BUILDDIR) -O$(MML_OPT) -o $@ $<

$(BINDIR)/matmul-opt-mml: mat-mul-opt.mml Makefile | $(BINDIR)
	mmlc -I -b $(BUILDDIR) -O$(MML_OPT) --llvm-opt-arg=-force-vector-interleave=4 -o $@ $<

$(BINDIR)/matmul-mml-O0-tco: mat-mul.mml | $(BINDIR)
	mmlc -I -b $(BUILDDIR) -O0 -o $@ $<

$(BINDIR)/matmul-mml-O0-no-tco: mat-mul.mml | $(BINDIR)
	mmlc -I -b $(BUILDDIR) -O0 --no-tco -o $@ $<

$(BINDIR)/matmul-mml-O1-tco: mat-mul.mml | $(BINDIR)
	mmlc -I -b $(BUILDDIR) -O1 -o $@ $<

$(BINDIR)/matmul-mml-O1-no-tco: mat-mul.mml | $(BINDIR)
	mmlc -I -b $(BUILDDIR) -O1 --no-tco -o $@ $<

$(BINDIR)/matmul-mml-O2-tco: mat-mul.mml | $(BINDIR)
	mmlc -I -b $(BUILDDIR) -O2 -o $@ $<

$(BINDIR)/matmul-mml-O2-no-tco: mat-mul.mml | $(BINDIR)
	mmlc -I -b $(BUILDDIR) -O2 --no-tco -o $@ $<

$(BINDIR)/matmul-mml-O3-tco: mat-mul.mml | $(BINDIR)
	mmlc -I -b $(BUILDDIR) -O3 -o $@ $<

$(BINDIR)/matmul-mml-O3-no-tco: mat-mul.mml | $(BINDIR)
	mmlc -I -b $(BUILDDIR) -O3 --no-tco -o $@ $<

$(BINDIR)/matmul-opt-mml-O0-tco: mat-mul-opt.mml | $(BINDIR)
	mmlc -I -b $(BUILDDIR) -O0 -o $@ $<

$(BINDIR)/matmul-opt-mml-O0-no-tco: mat-mul-opt.mml | $(BINDIR)
	mmlc -I -b $(BUILDDIR) -O0 --no-tco -o $@ $<

$(BINDIR)/matmul-opt-mml-O1-tco: mat-mul-opt.mml | $(BINDIR)
	mmlc -I -b $(BUILDDIR) -O1 -o $@ $<

$(BINDIR)/matmul-opt-mml-O1-no-tco: mat-mul-opt.mml | $(BINDIR)
	mmlc -I -b $(BUILDDIR) -O1 --no-tco -o $@ $<

$(BINDIR)/matmul-opt-mml-O2-tco: mat-mul-opt.mml | $(BINDIR)
	mmlc -I -b $(BUILDDIR) -O2 -o $@ $<

$(BINDIR)/matmul-opt-mml-O2-no-tco: mat-mul-opt.mml | $(BINDIR)
	mmlc -I -b $(BUILDDIR) -O2 --no-tco -o $@ $<

$(BINDIR)/matmul-opt-mml-O3-tco: mat-mul-opt.mml | $(BINDIR)
	mmlc -I -b $(BUILDDIR) -O3 -o $@ $<

$(BINDIR)/matmul-opt-mml-O3-no-tco: mat-mul-opt.mml | $(BINDIR)
	mmlc -I -b $(BUILDDIR) -O3 --no-tco -o $@ $<

# N-Queens
$(BINDIR)/nqueens-c: nqueens.c | $(BINDIR)
	$(CC) $(CFLAGS) -o $@ $<

$(BINDIR)/nqueens-go: nqueens.go | $(BINDIR)
	go build -o $@ $<

$(BINDIR)/nqueens-mml: nqueens.mml | $(BINDIR)
	mmlc -I -b $(BUILDDIR) -O$(MML_OPT) -o $@ $<

# Euclidean Extended GCD
$(BINDIR)/euclidean-ext-c: euclidean-ext.c | $(BINDIR)
	$(CC) $(CFLAGS) -o $@ $<

$(BINDIR)/euclidean-ext-mml: euclidean-ext.mml | $(BINDIR)
	mmlc -I -b $(BUILDDIR) -O$(MML_OPT) -o $@ $<

# Benchmarks
# Each run includes process startup. Java classes and native images are built before
# timing. JVM entries launch a separate JVM per run, including hyperfine warmups.
bench-sieve: $(BINDIR)/sieve-c $(BINDIR)/sieve-go $(BINDIR)/sieve-rs $(BINDIR)/sieve-mml \
	$(BINDIR)/Sieve.class $(BINDIR)/sieve-java-native sieve.py $(RESULTS_DEP)
	hyperfine -N --warmup 20 --runs 200 \
		$(call EXPORT_FLAGS,sieve) \
		'$(BINDIR)/sieve-c' \
		'$(BINDIR)/sieve-mml' \
		'$(BINDIR)/sieve-rs' \
		'$(BINDIR)/sieve-go' \
		'$(JAVA) -cp $(BINDIR) Sieve' \
		'$(BINDIR)/sieve-java-native' \
		$(call PYTHON_BENCH,sieve)

bench-sieve-time: $(BINDIR)/sieve-c $(BINDIR)/sieve-go $(BINDIR)/sieve-rs $(BINDIR)/sieve-mml \
	$(BINDIR)/Sieve.class $(BINDIR)/sieve-java-native sieve.py
	/usr/bin/time -l $(BINDIR)/sieve-c
	/usr/bin/time -l $(BINDIR)/sieve-mml
	/usr/bin/time -l $(BINDIR)/sieve-rs
	/usr/bin/time -l $(BINDIR)/sieve-go
	/usr/bin/time -l $(JAVA) -cp $(BINDIR) Sieve
	/usr/bin/time -l $(BINDIR)/sieve-java-native
ifeq ($(BENCH_SLOW),1)
	/usr/bin/time -l $(PYTHON) sieve.py
endif

bench-self-sieve: $(SELF_SIEVE_BINARIES) $(RESULTS_DEP)
	hyperfine -N --warmup 20 --runs 200 \
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
	hyperfine -N --warmup 20 --runs 200 \
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
	hyperfine -N --warmup 20 --runs 200 \
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

bench-quicksort: $(BINDIR)/quicksort-c $(BINDIR)/quicksort-mml \
	$(BINDIR)/quicksort-go $(BINDIR)/quicksort-rs $(BINDIR)/Quicksort.class \
	$(BINDIR)/quicksort-java-native quicksort.py $(RESULTS_DEP)
	hyperfine -N --warmup 10 --runs 200 \
		$(call EXPORT_FLAGS,quicksort) \
		'$(BINDIR)/quicksort-c' \
		'$(BINDIR)/quicksort-mml' \
		'$(BINDIR)/quicksort-rs' \
		'$(BINDIR)/quicksort-go' \
		'$(JAVA) -cp $(BINDIR) Quicksort' \
		'$(BINDIR)/quicksort-java-native' \
		$(call PYTHON_BENCH,quicksort)

bench-quicksort-time: $(BINDIR)/quicksort-c $(BINDIR)/quicksort-mml \
	$(BINDIR)/quicksort-go $(BINDIR)/quicksort-rs $(BINDIR)/Quicksort.class \
	$(BINDIR)/quicksort-java-native quicksort.py
	/usr/bin/time -l $(BINDIR)/quicksort-c
	/usr/bin/time -l $(BINDIR)/quicksort-mml
	/usr/bin/time -l $(BINDIR)/quicksort-rs
	/usr/bin/time -l $(BINDIR)/quicksort-go
	/usr/bin/time -l $(JAVA) -cp $(BINDIR) Quicksort
	/usr/bin/time -l $(BINDIR)/quicksort-java-native
ifeq ($(BENCH_SLOW),1)
	/usr/bin/time -l $(PYTHON) quicksort.py
endif

bench-matmul: $(BINDIR)/matmul-c $(BINDIR)/matmul-restricted-c $(BINDIR)/matmul-go \
	$(BINDIR)/matmul-bce-go $(BINDIR)/matmul-opt-go $(BINDIR)/matmul-mml $(BINDIR)/matmul-opt-mml $(BINDIR)/matmul-opt-c \
	$(BINDIR)/matmul-opt-bce-go $(BINDIR)/matmul-rs $(BINDIR)/matmul-opt-rs $(BINDIR)/matmul-java-native $(RESULTS_DEP)
	hyperfine -N --warmup 20 --runs 50 \
		$(call EXPORT_FLAGS,matmul) \
		'$(BINDIR)/matmul-c' \
		'$(BINDIR)/matmul-opt-c' \
		'$(BINDIR)/matmul-restricted-c' \
		'$(BINDIR)/matmul-mml' \
		'$(BINDIR)/matmul-opt-mml' \
		'$(BINDIR)/matmul-rs' \
		'$(BINDIR)/matmul-opt-rs' \
		'$(BINDIR)/matmul-go' \
		'$(BINDIR)/matmul-bce-go' \
		'$(BINDIR)/matmul-opt-go' \
		'$(BINDIR)/matmul-opt-bce-go' \
		'$(BINDIR)/matmul-java-native'

bench-matmul-time: $(BINDIR)/matmul-c $(BINDIR)/matmul-restricted-c $(BINDIR)/matmul-go \
	$(BINDIR)/matmul-bce-go $(BINDIR)/matmul-opt-go $(BINDIR)/matmul-mml $(BINDIR)/matmul-opt-mml \
	$(BINDIR)/matmul-opt-bce-go $(BINDIR)/matmul-opt-c $(BINDIR)/matmul-rs $(BINDIR)/matmul-opt-rs $(BINDIR)/matmul-java-native
	/usr/bin/time -l $(BINDIR)/matmul-c
	/usr/bin/time -l $(BINDIR)/matmul-opt-c
	/usr/bin/time -l $(BINDIR)/matmul-restricted-c
	/usr/bin/time -l $(BINDIR)/matmul-mml
	/usr/bin/time -l $(BINDIR)/matmul-opt-mml
	/usr/bin/time -l $(BINDIR)/matmul-rs
	/usr/bin/time -l $(BINDIR)/matmul-opt-rs
	/usr/bin/time -l $(BINDIR)/matmul-go
	/usr/bin/time -l $(BINDIR)/matmul-bce-go
	/usr/bin/time -l $(BINDIR)/matmul-opt-go
	/usr/bin/time -l $(BINDIR)/matmul-opt-bce-go
	/usr/bin/time -l $(BINDIR)/matmul-java-native

bench-nqueens: $(BINDIR)/nqueens-c $(BINDIR)/nqueens-go $(BINDIR)/nqueens-mml $(RESULTS_DEP)
	hyperfine -N --warmup 5 --runs 20 \
		$(call EXPORT_FLAGS,nqueens) \
		'$(BINDIR)/nqueens-c' \
		'$(BINDIR)/nqueens-go' \
		'$(BINDIR)/nqueens-mml'

bench-nqueens-time: $(BINDIR)/nqueens-c $(BINDIR)/nqueens-go $(BINDIR)/nqueens-mml
	/usr/bin/time -l $(BINDIR)/nqueens-c
	/usr/bin/time -l $(BINDIR)/nqueens-go
	/usr/bin/time -l $(BINDIR)/nqueens-mml

bench-euclidean: $(BINDIR)/euclidean-ext-c $(BINDIR)/euclidean-ext-mml $(RESULTS_DEP)
	hyperfine -N --warmup 10 --runs 200 \
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

.PHONY: all java java-native mml clean bench bench-time bench-sieve bench-sieve-time bench-quicksort \
	bench-quicksort-time bench-matmul bench-matmul-time bench-nqueens bench-nqueens-time \
	bench-euclidean bench-euclidean-time bench-self-sieve bench-self-sieve-time \
	bench-self-matmul bench-self-matmul-time bench-self-matmul-opt bench-self-matmul-opt-time
