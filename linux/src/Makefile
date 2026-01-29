CC = gcc
LOG_LEVEL ?= INFO

CFLAGS ?= -O1 -Wall -Werror
CFLAGS += -DLOG_LEVEL=LOG_$(LOG_LEVEL)

CFLAGS += -I$(BUILD)/usr/include
LDFLAGS += -L$(BUILD)/usr/lib

all: $(foreach i, device rec, $(BUILD)/$(i))

$(BUILD)/rec: rec.c
	$(CC) -o "$@" $(CFLAGS) $< $(LDFLAGS) -lm -lasound

$(BUILD)/device: device.c
	$(CC) -o "$@" $(CFLAGS) $(shell pkg-config libpulse --cflags) $< $(LDFLAGS) $(shell pkg-config libpulse --libs)

.PHONY: all
