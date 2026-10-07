#!/usr/bin/env bash
# Installs what is needed to build the Android app from the command line on
# Debian/Ubuntu (e.g. this repo's devcontainer): a JDK and the Android SDK.
# Android Studio users don't need this — the IDE brings both.
#
#   android/scripts/setup-toolchain.sh
#
# The SDK goes to ~/Android/Sdk (override with ANDROID_HOME).
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
sdk="${ANDROID_HOME:-$HOME/Android/Sdk}"

if ! command -v java >/dev/null; then
  sudo apt-get update -qq
  sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq openjdk-21-jdk-headless unzip
fi

if [ ! -x "$sdk/cmdline-tools/latest/bin/sdkmanager" ]; then
  mkdir -p "$sdk/cmdline-tools"
  zip="$(curl -fsS https://dl.google.com/android/repository/repository2-3.xml |
    grep -oE 'commandlinetools-linux-[0-9]+_latest\.zip' | sort -t- -k3 -n | tail -1)"
  curl -fsS -o /tmp/cmdline-tools.zip "https://dl.google.com/android/repository/$zip"
  rm -rf "$sdk/cmdline-tools/latest" "$sdk/cmdline-tools/cmdline-tools"
  unzip -q /tmp/cmdline-tools.zip -d "$sdk/cmdline-tools"
  mv "$sdk/cmdline-tools/cmdline-tools" "$sdk/cmdline-tools/latest"
  rm /tmp/cmdline-tools.zip
fi

yes | "$sdk/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null || true
# the platform must match compileSdk in app/build.gradle.kts
"$sdk/cmdline-tools/latest/bin/sdkmanager" "platform-tools" "platforms;android-37.0" "build-tools;36.0.0" >/dev/null

# point Gradle at the SDK, keeping whatever else local.properties holds (e.g. gemini.apiKey)
props="$here/../local.properties"
touch "$props"
{ grep -v '^sdk\.dir=' "$props" || true; echo "sdk.dir=$sdk"; } >"$props.tmp"
mv "$props.tmp" "$props"
echo "Android SDK ready at $sdk"
