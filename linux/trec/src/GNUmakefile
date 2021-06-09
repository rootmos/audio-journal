CC = gcc
LOG_LEVEL ?= INFO

CFLAGS ?= -O1 -Wall -Werror
CFLAGS += -DLOG_LEVEL=LOG_$(LOG_LEVEL)
LDFLAGS = -lm -lr -lasound

CFLAGS += -I$(BUILD)/usr/include
LDFLAGS += -L$(BUILD)/usr/lib

all: $(foreach i, trec, $(BUILD)/$(i))

$(BUILD)/%: %.c
	$(CC) -o "$@" $(CFLAGS) $< $(LDFLAGS)

.PHONY: all
