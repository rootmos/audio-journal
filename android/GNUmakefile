GRADLE ?= gradle
GRADLE_OPTS ?= --warning-mode=none --console=plain
export ANDROID_SDK_ROOT = $(shell pwd)/sdk
export ANDROID_SDK_HOME = $(shell pwd)
export ANDROID_HOME = $(ANDROID_SDK_ROOT)
ANDROID_SDK_BIN = $(ANDROID_SDK_ROOT)/tools/bin
ADB ?= $(ANDROID_SDK_ROOT)/platform-tools/adb

build: $(ANDROID_SDK_ROOT)
	@$(GRADLE) $(GRADLE_OPTS) build

release: $(ANDROID_SDK_ROOT)
	@$(GRADLE) $(GRADLE_OPTS) assembleRelease

install: $(ANDROID_SDK_ROOT)
	@$(GRADLE) $(GRADLE_OPTS) installDebug

install-release: release
	@$(ADB) install app/build/outputs/apk/release/app-release.apk

start:
	@$(ADB) shell am start -n io.rootmos.audiojournal/.MainActivity

$(ANDROID_SDK_ROOT):
	./sdk.sh "$@"

clean:
	@$(GRADLE) $(GRADLE_OPTS) myClean

LOG_LEVEL ?= I
logs:
	adb logcat AudioJournal:$(LOG_LEVEL) AndroidRuntime:E *:S

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

.PHONY: build release install start clean
.PHONY: logs prepare-emulator run-emulator
