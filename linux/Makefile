LOG_LEVEL ?= INFO
export BUILD ?= $(shell pwd)/build
export PREFIX ?= $(HOME)/.local

run: build
	$(BUILD)/rec "%FT%T%z.mp3"

gui: build
	env AUDIO_JOURNAL_REC=$(BUILD)/rec AUDIO_JOURNAL_DEVICE=$(BUILD)/device ./audio-journal --log=$(LOG_LEVEL) gui test test-2

install: build
	install -D -T audio-journal $(PREFIX)/bin/audio-journal
	install --strip -D -T $(BUILD)/rec $(PREFIX)/bin/audio-journal-rec
	install --strip -D -T $(BUILD)/device $(PREFIX)/bin/audio-journal-device

build:
	$(MAKE) -C src

clean:
	rm -rf $(BUILD) .*.flag

.PHONY: run install build libr clean
