#!/usr/bin/env bash
# CI 构建脚本：不依赖任何 setup-* action，只用 runner 自带的 JDK17 / Android SDK。
set -x

if [ -n "$JAVA_HOME_17_X64" ]; then
  export JAVA_HOME="$JAVA_HOME_17_X64"
  export PATH="$JAVA_HOME/bin:$PATH"
fi
java -version

SDK="${ANDROID_HOME:-/usr/local/lib/android/sdk}"
if [ ! -d "$SDK" ]; then SDK="$ANDROID_SDK_ROOT"; fi
if [ ! -d "$SDK" ]; then SDK="/usr/local/lib/android/sdk"; fi
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
echo "SDK=$SDK"
ls "$SDK" || true
ls "$SDK/platforms" || true
ls "$SDK/build-tools" || true
echo "sdk.dir=$SDK" > local.properties

GRADLE_BIN="$(command -v gradle || true)"
if [ -z "$GRADLE_BIN" ]; then
  curl -sSL -o /tmp/gradle.zip https://services.gradle.org/distributions/gradle-8.9-bin.zip
  mkdir -p /opt/gradledist
  unzip -q -o /tmp/gradle.zip -d /opt/gradledist
  export PATH="/opt/gradledist/gradle-8.9/bin:$PATH"
fi
gradle -v

gradle :app:assembleDebug --no-daemon --stacktrace

ls -l app/build/outputs/apk/debug/ || true
echo "BUILD_OK"
