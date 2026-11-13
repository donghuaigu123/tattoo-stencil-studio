#!/usr/bin/env bash
# 用 Android SDK 的命令行工具直接构建签名 APK，无需 Gradle / Android Studio。
# 依赖：JDK 17+、Android SDK（build-tools 34、platform android-34）、zip、keytool。
# 用法：ANDROID_SDK=/path/to/sdk ./build.sh
set -euo pipefail

ANDROID="$(cd "$(dirname "$0")" && pwd)"
REPO="$(dirname "$ANDROID")"
SDK="${ANDROID_SDK:-${ANDROID_HOME:-$HOME/Android/Sdk}}"

find_tool() {
  local f="$1"
  local p
  p="$(find "$SDK" -type f -name "$f" 2>/dev/null | head -n 1)"
  if [ -z "$p" ]; then echo "缺少工具：$f（请检查 ANDROID_SDK=$SDK）" >&2; exit 1; fi
  printf '%s' "$p"
}

AAPT2="$(find_tool aapt2)"
D8="$(find_tool d8)"
APKSIGNER="$(find_tool apksigner)"
ZIPALIGN="$(find_tool zipalign)"
ANDROID_JAR="$(find_tool android.jar)"
KEYPASS="${KEYSTORE_PASS:-stencil123}"

WORK="$ANDROID/build"
rm -rf "$WORK"
mkdir -p "$WORK/classes" "$WORK/dex" "$WORK/gen" "$WORK/staging/assets"
cp "$REPO/index.html" "$WORK/staging/assets/index.html"

echo "[1/7] 编译资源"
"$AAPT2" compile --dir "$ANDROID/res" -o "$WORK/res.zip"

echo "[2/7] 链接 Manifest，生成 APK 骨架"
"$AAPT2" link -o "$WORK/base.apk" -I "$ANDROID_JAR" \
  --manifest "$ANDROID/AndroidManifest.xml" \
  -R "$WORK/res.zip" --java "$WORK/gen" \
  --min-sdk-version 24 --target-sdk-version 34 \
  --version-code 1 --version-name 1.0.0

echo "[3/7] 编译 Java"
javac --release 17 -encoding UTF-8 -cp "$ANDROID_JAR" -d "$WORK/classes" \
  "$ANDROID/src/com/stencilstudio/app/MainActivity.java"

echo "[4/7] 转成 classes.dex"
mapfile -t CLASSES < <(find "$WORK/classes" -name '*.class')
"$D8" --lib "$ANDROID_JAR" --min-api 24 --output "$WORK/dex" "${CLASSES[@]}"

echo "[5/7] 打包 assets 与 dex"
cp "$WORK/base.apk" "$WORK/unsigned.apk"
cp "$WORK/dex/classes.dex" "$WORK/staging/classes.dex"
(cd "$WORK/staging" && zip -q -r "$WORK/unsigned.apk" assets classes.dex)
"$ZIPALIGN" -f 4 "$WORK/unsigned.apk" "$WORK/aligned.apk"

echo "[6/7] 生成自签名密钥（仅用于本地安装）"
if [ ! -f "$ANDROID/stencil.keystore" ]; then
  keytool -genkeypair -keystore "$ANDROID/stencil.keystore" -alias stencil \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$KEYPASS" -keypass "$KEYPASS" \
    -dname "CN=Stencil Studio, OU=OpenSource, O=Stencil Studio, L=NA, S=NA, C=CN" \
    >/dev/null 2>&1
fi

echo "[7/7] 签名并校验"
"$APKSIGNER" sign --ks "$ANDROID/stencil.keystore" \
  --ks-pass "pass:$KEYPASS" --key-pass "pass:$KEYPASS" \
  --v1-signing-enabled true --v2-signing-enabled true \
  --out "$REPO/StencilStudio.apk" "$WORK/aligned.apk"
"$APKSIGNER" verify --verbose "$REPO/StencilStudio.apk"

echo "完成：$REPO/StencilStudio.apk"
