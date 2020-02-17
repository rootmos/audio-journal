#!/bin/bash

set -o nounset -o pipefail -o errexit

OUT=$1

SDK_URL=https://dl.google.com/android/repository/sdk-tools-linux-4333796.zip
SDK_SHA256=92ffee5a1d98d856634e8b71132e8a95d96c83a63fde1099be3d86df3106def9

TMP=$(mktemp -d)
trap 'rm -rf $TMP' EXIT

wget -O "$TMP/sdk.zip" "$SDK_URL"

sha256sum -c <<EOF
$SDK_SHA256  $TMP/sdk.zip
EOF

mkdir -p "$OUT"
unzip -d "$OUT" "$TMP/sdk.zip"

"$OUT/tools/bin/sdkmanager" --licenses
