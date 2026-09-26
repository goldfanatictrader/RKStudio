#!/usr/bin/env bash
# RK Studio - setup toolchain lokal (sekali saja per PC).
# Install: JDK 17 + Android cmdline-tools + SDK 34 + Gradle 8.7. Tanpa Android Studio.
set -euo pipefail

JAVA_HOME_CANDIDATE="/usr/lib/jvm/java-17-openjdk-amd64"
ANDROID_HOME_DEFAULT="$HOME/Android/Sdk"
GRADLE_VERSION="8.7"
CMDTOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"

echo "=== [1/5] Install JDK 17 + unzip + curl ==="
sudo apt update
sudo apt install -y openjdk-17-jdk-headless unzip curl

echo "=== [2/5] Siapkan Android SDK di $ANDROID_HOME_DEFAULT ==="
export JAVA_HOME="$JAVA_HOME_CANDIDATE"
export ANDROID_HOME="$ANDROID_HOME_DEFAULT"
export ANDROID_SDK_ROOT="$ANDROID_HOME_DEFAULT"
export PATH="$JAVA_HOME/bin:$PATH"
mkdir -p "$ANDROID_HOME/cmdline-tools"
if [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
  curl -L -o /tmp/rk-cmdtools.zip "$CMDTOOLS_URL"
  rm -rf "$ANDROID_HOME/cmdline-tools/latest"
  unzip -q /tmp/rk-cmdtools.zip -d "$ANDROID_HOME/cmdline-tools"
  mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  rm -f /tmp/rk-cmdtools.zip
fi
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

echo "=== [3/5] Accept licenses + install packages ==="
yes | sdkmanager --licenses > /dev/null
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"

echo "=== [4/5] Install Gradle $GRADLE_VERSION (tanpa Android Studio) ==="
if [ ! -x "$HOME/gradle-$GRADLE_VERSION/bin/gradle" ]; then
  curl -L -o /tmp/rk-gradle.zip "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
  rm -rf "$HOME/gradle-$GRADLE_VERSION"
  unzip -q /tmp/rk-gradle.zip -d "$HOME"
  rm -f /tmp/rk-gradle.zip
fi

echo "=== [5/5] Verifikasi ==="
java -version
"$HOME/gradle-$GRADLE_VERSION/bin/gradle" --version | head -n 8
sdkmanager --list_installed | tail -n 8

echo ""
echo "Setup selesai. Tambahkan ke ~/.bashrc bila perlu:"
echo "  export JAVA_HOME=$JAVA_HOME_CANDIDATE"
echo "  export ANDROID_HOME=$ANDROID_HOME_DEFAULT"
echo "  export ANDROID_SDK_ROOT=$ANDROID_HOME_DEFAULT"
echo "Lalu jalankan: ./build-apk.sh"
