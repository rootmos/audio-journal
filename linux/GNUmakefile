export BUILD ?= $(shell pwd)/build

export PREFIX ?= $(HOME)/.local

run: build
	$(BUILD)/rec "%FT%T%z.mp3"

install: build
	install -D -T audio-journal $(PREFIX)/bin/audio-journal
	install --strip -D -T $(BUILD)/rec $(PREFIX)/bin/audio-journal-rec
	install --strip -D -T $(BUILD)/device $(PREFIX)/bin/audio-journal-device

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
