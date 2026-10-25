#!/usr/bin/env bash
# Сборка Clawd.apk без Android Studio и Gradle: нужны только Java 17+, git, curl, zip.
# Инструменты скачиваются в tools/ при первом запуске.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
TOOLS="$ROOT/tools"
BUILD="$ROOT/build"
APP="$ROOT/app"
KEYSTORE="${CLAWD_KEYSTORE:-$ROOT/keystore/clawd.jks}"
KEY_ALIAS="${CLAWD_KEY_ALIAS:-clawd}"
KEY_PASS="${CLAWD_KEY_PASS:-clawdclawd}"

mkdir -p "$TOOLS"

fetch() { # url файл
  [ -s "$TOOLS/$2" ] || curl -fsSL --retry 4 -o "$TOOLS/$2" "$1"
}

# android.jar (API 34) — из открытого архива платформ, частичным клоном
if [ ! -s "$TOOLS/android.jar" ]; then
  tmp="$(mktemp -d)"
  git clone -q --depth 1 --filter=blob:none --sparse https://github.com/Sable/android-platforms "$tmp/p"
  git -C "$tmp/p" sparse-checkout set android-34
  cp "$tmp/p/android-34/android.jar" "$TOOLS/android.jar"
  rm -rf "$tmp"
fi
fetch https://github.com/iBotPeaches/Apktool/releases/download/v2.12.1/apktool_2.12.1.jar apktool.jar
fetch https://repo1.maven.org/maven2/com/jakewharton/android/repackaged/dalvik-dx/11.0.0_r3/dalvik-dx-11.0.0_r3.jar dx.jar
fetch https://github.com/patrickfav/uber-apk-signer/releases/download/v1.3.0/uber-apk-signer-1.3.0.jar uber-apk-signer.jar
if [ ! -x "$TOOLS/aapt2" ]; then
  unzip -p "$TOOLS/apktool.jar" prebuilt/linux/aapt2_64 > "$TOOLS/aapt2"
  chmod +x "$TOOLS/aapt2"
fi

rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/classes"

echo "› ресурсы"
"$TOOLS/aapt2" compile --dir "$APP/res" -o "$BUILD/res.zip"
"$TOOLS/aapt2" link -o "$BUILD/unsigned.apk" -I "$TOOLS/android.jar" \
  --manifest "$APP/AndroidManifest.xml" --java "$BUILD/gen" "$BUILD/res.zip"

echo "› java"
find "$APP/src" "$BUILD/gen" -name '*.java' > "$BUILD/sources.txt"
javac -nowarn -Xlint:-options -source 8 -target 8 -encoding UTF-8 \
  -bootclasspath "$TOOLS/android.jar" -d "$BUILD/classes" @"$BUILD/sources.txt"

echo "› dex"
java -cp "$TOOLS/dx.jar" com.android.dx.command.Main --dex --min-sdk-version=26 \
  --output="$BUILD/classes.dex" "$BUILD/classes"
(cd "$BUILD" && zip -q -j unsigned.apk classes.dex)

echo "› подпись"
if [ ! -s "$KEYSTORE" ]; then
  mkdir -p "$(dirname "$KEYSTORE")"
  keytool -genkeypair -keystore "$KEYSTORE" -alias "$KEY_ALIAS" -storepass "$KEY_PASS" -keypass "$KEY_PASS" \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Clawd" >/dev/null 2>&1
  echo "  создан новый ключ: $KEYSTORE (не теряй — без него обновление не встанет поверх)"
fi
java -jar "$TOOLS/uber-apk-signer.jar" -a "$BUILD/unsigned.apk" -o "$BUILD/signed" \
  --ks "$KEYSTORE" --ksAlias "$KEY_ALIAS" --ksPass "$KEY_PASS" --ksKeyPass "$KEY_PASS" >/dev/null
mv "$BUILD"/signed/*.apk "$BUILD/Clawd.apk"
echo "✓ $BUILD/Clawd.apk"
