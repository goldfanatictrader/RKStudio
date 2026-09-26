#!/usr/bin/env bash
# RK Studio - build APK debug lokal (100% offline saat runtime, tanpa REST API).
# Prasyarat: jalankan ./setup-android-env.sh sekali saja.
set -euo pipefail
cd "$(dirname "$0")"

JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
GRADLE_BIN="$HOME/gradle-8.7/bin/gradle"
if [ -x "./gradlew" ]; then
  GRADLE_BIN="./gradlew"
fi

export JAVA_HOME ANDROID_HOME
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

command -v java > /dev/null || { echo "JDK 17 belum ada. Jalankan ./setup-android-env.sh dulu."; exit 1; }
[ -d "$ANDROID_HOME/platforms/android-34" ] || { echo "SDK android-34 belum ada. Jalankan ./setup-android-env.sh dulu."; exit 1; }

echo "sdk.dir=$ANDROID_HOME" > local.properties
echo "Pakai: $GRADLE_BIN"
echo "JAVA: $(java -version 2>&1 | head -n 1)"

$GRADLE_BIN assembleDebug --max-workers=1

APK=$(ls -t app/build/outputs/apk/debug/*.apk 2>/dev/null | head -n 1 || true)
if [ -z "${APK:-}" ]; then
  echo "Build gagal: APK tidak ditemukan."; exit 1
fi
cp -f "$APK" ./RK-Studio-debug.apk
ls -lh ./RK-Studio-debug.apk "$APK"
echo ""
echo "Selesai: ./RK-Studio-debug.apk"
echo "Install ke HP: adb install -r ./RK-Studio-debug.apk"
