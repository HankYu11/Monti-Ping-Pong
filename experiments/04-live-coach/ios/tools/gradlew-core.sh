#!/bin/sh
# Builds ios/shared (the Android core for iOS) into shared/build/XCFrameworks/release.
# Xcode's environment has no JAVA_HOME or Gradle on its PATH, so both are found here.
# Offline on a Mac that has built it before; on CI (CI set) it may download.
export PATH="/Users/hankyu/Desktop/pingpong/tools/bin:/opt/homebrew/bin:/usr/local/bin:$PATH"
[ -z "$JAVA_HOME" ] && JAVA_HOME=$(/usr/libexec/java_home 2>/dev/null) && export JAVA_HOME
OFFLINE=--offline; [ -n "$CI" ] && OFFLINE=
exec gradle $OFFLINE -q assembleServeCoachCoreReleaseXCFramework
