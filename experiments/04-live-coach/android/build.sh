#!/bin/bash
# Build the Serve Coach Live APK with the spin meter's current engine, and copy it to ../release.
# Needs the Android SDK (ANDROID_HOME, or sdk.dir in local.properties) and Gradle 8.9+.
set -e
cd "$(dirname "$0")"
cp ../../02-spin-meter/web/engine.js app/src/main/assets/engine.js
# the native part (cpp/hwtex.c): rebuilt when the NDK is there, else the committed one is used
NDK_BIN=$(ls -d ${ANDROID_HOME:-/opt/android-sdk}/ndk/*/toolchains/llvm/prebuilt/linux-x86_64/bin 2>/dev/null | tail -1)
if [ -n "$NDK_BIN" ]; then
  mkdir -p app/src/main/jniLibs/arm64-v8a
  "$NDK_BIN/aarch64-linux-android29-clang" -shared -fPIC -O2 -o app/src/main/jniLibs/arm64-v8a/libhwtex.so \
    app/src/main/cpp/hwtex.c -lEGL -lGLESv2 -landroid -lnativewindow
fi
gradle --no-daemon -q assembleRelease
V=$(grep versionName app/build.gradle.kts | sed -E 's/.*"(.*)".*/\1/')
mkdir -p ../release
cp app/build/outputs/apk/release/app-release.apk ../release/serve-coach-live-$V.apk
echo "../release/serve-coach-live-$V.apk"
