#!/bin/bash

set -o nounset -o pipefail -o errexit

ADB=${ADB-adb}
ADB_OPTS=${ADB_OPTS-}

screenshot() {
    $ADB $ADB_OPTS shell /system/bin/screencap -p /sdcard/screenshot.png
    $ADB $ADB_OPTS pull /sdcard/screenshot.png "$1"
}

run() {
    $ADB $ADB_OPTS shell am start -n "$1"
}

countdown() {
    SEC=$1
    for i in $(seq $SEC); do
        echo "$((SEC-i+1))..."
        sleep 1
    done
    echo "go!"
}

run "io.rootmos.audiojournal/.MainActivity"
countdown 5
screenshot "screenshots/main.png"

run "io.rootmos.audiojournal/.RecordingActivity"
countdown 5
screenshot "screenshots/recording.png"
