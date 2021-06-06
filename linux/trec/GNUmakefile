export BUILD ?= $(shell pwd)/build

export PREFIX ?= $(HOME)/.local

run: build
	@$(BUILD)/trec "%FT%T%z.mp3"

install: build
	install --strip -D -t $(PREFIX)/bin $(BUILD)/trec

build: libr
	$(MAKE) -C src

libr: .libr.flag
.libr.flag:
	@mkdir -p "$(BUILD)"
	$(MAKE) -C libr install PREFIX="$(BUILD)/usr"
	@touch "$@"

clean:
	rm -rf $(BUILD) .*.flag

.PHONY: run install build libr clean
