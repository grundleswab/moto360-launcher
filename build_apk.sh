#!/usr/bin/env bash
set -euo pipefail

PROJ="$1"
PLATFORM="$2"
OUT="$3"

CONFIG_FILE="${MOTO360_CONFIG:-$HOME/.config/moto360-launcher/env}"
[ -r "$CONFIG_FILE" ] && . "$CONFIG_FILE"

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/.local/lib/android-sdk}}"
BT="$SDK/build-tools/${ANDROID_BUILD_TOOLS:-30.0.3}"
ANDROID_JAR="$SDK/platforms/android-$PLATFORM/android.jar"
JDK="${JAVA_HOME:-$HOME/.local/lib/jvm/jdk-17}"
export PATH="$JDK/bin:$PATH"
AARS="${AARS_DIR:-$HOME/.local/share/moto360-launcher/aars}"
if [ -n "${AAR_LIST:-}" ]; then
    AARS_TO_USE="$AAR_LIST"
elif [ "$PLATFORM" -ge 24 ]; then
    # Modern phone build (Tasks/API split out of the wearable AAR).
    AARS_TO_USE="play-services-wearable-18.2.0 play-services-base-18.2.0 play-services-basement-18.2.0 play-services-tasks-18.2.0 androidx-core-1.3.2 androidx-collection-1.1.0"
else
    # Wear 1.x build.
    AARS_TO_USE="play-services-wearable-8.4.0 play-services-base-8.4.0 play-services-basement-8.4.0 support-compat-24.2.1 support-media-compat-24.2.1 support-core-utils-24.2.1 support-core-ui-24.2.1 support-fragment-24.2.1"
fi
KEYSTORE="${MOTO360_KEYSTORE:-$HOME/.local/share/moto360-launcher/debug.keystore}"

LIBJARS=""
for aar in $AARS_TO_USE; do
    LIBJARS="${LIBJARS:+$LIBJARS:}$AARS/$aar/classes.jar"
done
CP="$ANDROID_JAR:$LIBJARS"

cd "$PROJ"
rm -rf build/*
mkdir -p build/gen build/classes build/dex

"$BT/aapt2" compile --dir res -o build/compiled.flata
"$BT/aapt2" link \
    -o build/unsigned.apk \
    -I "$ANDROID_JAR" \
    --manifest AndroidManifest.xml \
    --java build/gen \
    build/compiled.flata

find src build/gen -name '*.java' > build/sources.txt
"$JDK/bin/javac" \
    -source 8 -target 8 \
    -bootclasspath "$ANDROID_JAR" \
    -classpath "$CP" \
    -d build/classes \
    @build/sources.txt

find build/classes -name '*.class' > build/classes.txt
D8_CP=""
D8_INPUTS=()
for jar in $(printf '%s' "$LIBJARS" | tr ':' ' '); do
    D8_CP="$D8_CP --classpath $jar"
    D8_INPUTS+=("$jar")
done
# shellcheck disable=SC2086
"$BT/d8" --lib "$ANDROID_JAR" $D8_CP --output build/dex "${D8_INPUTS[@]}" @build/classes.txt

(cd build/dex && zip -u -q ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk

if [ ! -f "$KEYSTORE" ]; then
    mkdir -p "$(dirname "$KEYSTORE")"
    "$JDK/bin/keytool" -genkeypair \
        -keystore "$KEYSTORE" \
        -storepass android -keypass android \
        -alias androiddebugkey \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Android Debug,O=Android,C=US"
fi

"$BT/apksigner" sign \
    --ks "$KEYSTORE" \
    --ks-pass pass:android \
    --key-pass pass:android \
    --out "$OUT" \
    build/aligned.apk

echo "built $OUT"
