#!/bin/bash
# Build the Serve Coach Live APK with the spin meter's current engine, and copy it to ../release.
# Needs the Android SDK (ANDROID_HOME, or sdk.dir in local.properties) and Gradle 8.9+.
set -e
cd "$(dirname "$0")"
cp ../../02-spin-meter/web/engine.js app/src/main/assets/engine.js
gradle --no-daemon -q assembleRelease
V=$(grep versionName app/build.gradle.kts | sed -E 's/.*"(.*)".*/\1/')
mkdir -p ../release
cp app/build/outputs/apk/release/app-release.apk ../release/serve-coach-live-$V.apk
echo "../release/serve-coach-live-$V.apk"
