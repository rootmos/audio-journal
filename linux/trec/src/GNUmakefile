CC = gcc
LOG_LEVEL ?= INFO

CFLAGS ?= -O1 -Wall -Werror
CFLAGS += -DLOG_LEVEL=LOG_$(LOG_LEVEL)

CFLAGS += -I$(BUILD)/usr/include
LDFLAGS += -L$(BUILD)/usr/lib

all: $(foreach i, device trec, $(BUILD)/$(i))

$(BUILD)/trec: trec.c
	$(CC) -o "$@" $(CFLAGS) $< $(LDFLAGS) -lm -lr -lasound

$(BUILD)/device: device.c
	$(CC) -o "$@" $(CFLAGS) $(shell pkg-config libpulse --cflags) $< $(LDFLAGS) -lr $(shell pkg-config libpulse --libs)

.PHONY: all
