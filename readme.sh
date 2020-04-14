#!/bin/bash

set -o nounset -o pipefail -o errexit

OUT=README.md

cat <<EOF > "$OUT"
# AudioJournal

AudioJournal is an:
* Android app and Linux scripts to keep a diary of sounds
* 1-click recorder and uploader of jam/live/practice sessions to the cloud

## Android app
### Features
* PCM 16bit 48k recording
* FLAC and MP3 encoding
* metadata templates

### Screenshots
<p align="center">
  <img src="android/screenshots/main.png" width="40%"/>
  <img src="android/screenshots/recording.png" width="40%"/>
</p>

### Links
* Inspired by [Audio Recorder](https://f-droid.org/en/packages/com.github.axet.audiorecorder/)
  by [axet](https://gitlab.com/axet)

## Linux script

### Usage
\`\`\`
EOF
linux/audio-journal -h >> "$OUT" 2>&1

cat <<EOF >> "$OUT"
\`\`\`
EOF
