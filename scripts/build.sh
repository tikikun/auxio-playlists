#!/usr/bin/env bash
# Builds the fork's debug APK without the Android NDK.
#
# Requirements:
#   - JDK 21 (brew install openjdk@21)
#   - Android SDK with platforms;android-37.0 and build-tools;37.0.0
#     (brew install --cask android-commandlinetools && sdkmanager "platforms;android-37.0" "build-tools;37.0.0")
#
# The native taglib/ffmpeg libraries are shipped prebuilt in app/src/main/jniLibs, so no NDK,
# CMake or ffmpeg source tree is needed. Pass -PwithFfmpegNatives=true to build ffmpeg from source.
set -euo pipefail

cd "$(dirname "$0")/.."

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

# The media submodule needs one patch to skip the NDK-only ffmpeg build.
if ! git -C media diff --quiet; then
    echo "warning: media submodule has local changes (expected: the ffmpeg patch)" >&2
fi

build_type="${1:-debug}"
./gradlew ":app:assemble${build_type^}" --no-daemon

echo
echo "APK: app/build/outputs/apk/${build_type}/"
