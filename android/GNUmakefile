GRADLE ?= gradle
GRADLE_OPTS ?= --warning-mode=none --console=plain
export ADB_OPTS ?=
export ANDROID_SDK_ROOT = $(shell pwd)/sdk
export ANDROID_SDK_HOME = $(shell pwd)
export ANDROID_HOME = $(ANDROID_SDK_ROOT)
ANDROID_SDK_BIN = $(ANDROID_SDK_ROOT)/tools/bin
export ADB ?= $(ANDROID_SDK_ROOT)/platform-tools/adb

build: $(ANDROID_SDK_ROOT)
	@$(GRADLE) $(GRADLE_OPTS) build

release: $(ANDROID_SDK_ROOT)
	@$(GRADLE) $(GRADLE_OPTS) assembleRelease

install: $(ANDROID_SDK_ROOT)
	@$(GRADLE) $(GRADLE_OPTS) installDebug

install-release: release
	@$(ADB) $(ADB_OPTS) install app/build/outputs/apk/release/app-release.apk

start:
	@$(ADB) $(ADB_OPTS) shell am start -n io.rootmos.audiojournal/.MainActivity

$(ANDROID_SDK_ROOT):
	./sdk.sh "$@"

clean:
	@$(GRADLE) $(GRADLE_OPTS) myClean

LOG_LEVEL ?= I
logs:
	@$(ADB) $(ADB_OPTS) logcat AudioJournal:$(LOG_LEVEL) AndroidRuntime:E *:S

screenshots:
	./screenshots.sh

AVD=audio-journal-avd
SDK_ID=system-images;android-28;default;x86_64
PLATFORM=platforms;android-28
DEVICE_ID=MI
prepare-emulator: $(ANDROID_SDK_ROOT)
	$(ANDROID_SDK_BIN)/sdkmanager --install \
		emulator platform-tools "$(SDK_ID)" "$(PLATFORM)"
	install -m 644 -D -t "$(ANDROID_SDK_HOME)/.android" devices.xml
	$(ANDROID_SDK_BIN)/avdmanager create avd \
		--force --name "$(AVD)" --package "$(SDK_ID)" \
		--device "$(DEVICE_ID)"

run-emulator:
	$(ANDROID_SDK_ROOT)/tools/emulator -avd $(AVD)

.PHONY: build release install start screenshots clean
.PHONY: logs prepare-emulator run-emulator
