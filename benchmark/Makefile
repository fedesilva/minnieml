CC = clang
CFLAGS = -O3 -flto -march=native -DNDEBUG
BINDIR = bin

all: $(BINDIR)/fizzbuzz-c $(BINDIR)/fizzbuzz-go $(BINDIR)/ackermann-c $(BINDIR)/ackermann-go $(BINDIR)/ackermann-c-chacho $(BINDIR)/ackermann-c-mostrosity

$(BINDIR):
	mkdir -p $(BINDIR)

$(BINDIR)/fizzbuzz-c: fizzbuzz.c | $(BINDIR)
	$(CC) $(CFLAGS) -o $@ $<

$(BINDIR)/fizzbuzz-go: fizzbuzz.go | $(BINDIR)
	go build -o $@ $<

$(BINDIR)/ackermann-c: ackermann.c | $(BINDIR)
	$(CC) $(CFLAGS) -o $@ $<

$(BINDIR)/ackermann-c-chacho: ackermann-c-chacho.c | $(BINDIR)
	$(CC) $(CFLAGS) -o $@ $<

$(BINDIR)/ackermann-c-mostrosity: ackermann-c-mostrosity.c | $(BINDIR)
	$(CC) $(CFLAGS) -o $@ $<

$(BINDIR)/ackermann-go: ackermann.go | $(BINDIR)
	go build -o $@ $<

clean:
	rm -rf $(BINDIR)

.PHONY: all clean
