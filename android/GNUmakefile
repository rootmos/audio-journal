GRADLE ?= gradle
export ANDROID_SDK_ROOT ?= $(shell pwd)/sdk
export ANDROID_HOME = $(ANDROID_SDK_ROOT)
ANDROID_SDK_BIN=$(ANDROID_SDK_ROOT)/tools/bin

build: $(ANDROID_SDK_ROOT)
	$(GRADLE) build

install: $(ANDROID_SDK_ROOT)
	$(GRADLE) installDebug

$(ANDROID_SDK_ROOT):
	./sdk.sh "$@"

clean:
	$(GRADLE) clean

logs:
	adb logcat AudioJournal:D *:S

AVD=audio-journal-avd
AVD_ROOT=$(ANDROID_SDK_ROOT)/avd
SDK_ID=system-images;android-28;default;x86_64
prepare-emulator:
	$(ANDROID_SDK_BIN)/sdkmanager --install emulator "$(SDK_ID)"
	$(ANDROID_SDK_BIN)/avdmanager create avd \
		--force -n $(AVD) -k "$(SDK_ID)" -p $(AVD_ROOT)

run-emulator:
	$(ANDROID_SDK_ROOT)/tools/emulator -avd $(AVD)

.PHONY: build clean
.PHONY: logs prepare-emulator run-emulator
