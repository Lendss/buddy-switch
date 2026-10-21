#!/data/data/com.termux/files/usr/bin/bash
# Builds a signed release APK on-device with aapt2 + javac + d8 + apksigner.
# Output: build/buddy-release.apk
set -euo pipefail
cd "$(dirname "$0")"

SDK_JAR=${SDK_JAR:-$HOME/android-sdk/android-36/android.jar}
# The android.jar aapt2 links resources against. Termux's aapt2 2.19 can't read API 36's resources: point this at
# an older platform that it can read and that still has every attribute the manifest uses (API 33 works).
AAPT_JAR=${AAPT_JAR:-$( [ -f "$HOME/android-sdk/android-33/android.jar" ] && echo "$HOME/android-sdk/android-33/android.jar" || echo "$SDK_JAR")}
KEYSTORE=${KEYSTORE:-$HOME/.buddy-sw-keys/release.jks}
# The Java packages (and R) stay app.buddy.assistant, like upstream; the installed app is APP_ID, so this fork
# installs next to upstream Buddy.
PACKAGE=app.buddy.assistant
APP_ID=${APP_ID:-app.buddy.sw}
# d8 from the PATH, or from an r8.jar (R8_JAR, by default ~/android-sdk/r8.jar) when there is no d8 command.
if command -v d8 >/dev/null 2>&1; then D8=(d8)
elif [ -n "${R8_JAR:=$HOME/android-sdk/r8.jar}" ] && [ -f "$R8_JAR" ]; then D8=(java -Xmx512m -cp "$R8_JAR" com.android.tools.r8.D8)
else echo "No d8: install it or set R8_JAR to an r8.jar" >&2; exit 1; fi
INFO=app/src/main/java/app/buddy/assistant/BuildInfo.java
VERSION_NAME=$(sed -n 's/.*VERSION = "\(.*\)";/\1/p' "$INFO")
VERSION_CODE=$(sed -n 's/.*VERSION_CODE = \([0-9]*\);/\1/p' "$INFO")
SRC=app/src/main
FLAVOR=${FLAVOR:-github} # github or play (see app/build.gradle.kts)
OUT=build

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "1/6 Compiling resources"
aapt2 compile --dir "$SRC/res" -o "$OUT/res.zip"

echo "2/6 Linking resources and manifest"
# The manifest has no package attribute (Gradle uses `namespace`), so add it for aapt2.
sed "s|<manifest |<manifest package=\"$PACKAGE\" |" "$SRC/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"
# The play flavor drops REQUEST_INSTALL_PACKAGES (Gradle does this with app/src/play/AndroidManifest.xml).
[ "$FLAVOR" = play ] && sed -i '/REQUEST_INSTALL_PACKAGES/d' "$OUT/AndroidManifest.xml"
aapt2 link -I "$AAPT_JAR" --manifest "$OUT/AndroidManifest.xml" --rename-manifest-package "$APP_ID" \
  --min-sdk-version 30 --target-sdk-version 36 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  -A "$SRC/assets" --java "$OUT/gen" -o "$OUT/base.apk" "$OUT/res.zip"

echo "3/6 Compiling Java"
javac --release 8 -classpath "$SDK_JAR" -Xlint:-options -encoding UTF-8 \
  -d "$OUT/classes" $(find "$SRC/java" "app/src/$FLAVOR/java" "$OUT/gen" -name '*.java')

echo "4/6 Dexing"
"${D8[@]}" --release --min-api 30 --lib "$SDK_JAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

echo "5/6 Packaging and aligning"
cp "$OUT/base.apk" "$OUT/unaligned.apk"
python3 - "$OUT/unaligned.apk" "$OUT/dex/classes.dex" <<'EOF'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1], "a", zipfile.ZIP_DEFLATED) as z:
    z.write(sys.argv[2], "classes.dex")
EOF
python3 tools/zipalign.py "$OUT/unaligned.apk" "$OUT/aligned.apk"

echo "6/6 Signing"
if [ ! -f "$KEYSTORE" ]; then
  echo "Creating release keystore at $KEYSTORE (keep it safe; updates need the same key)"
  mkdir -p "$(dirname "$KEYSTORE")"
  chmod 700 "$(dirname "$KEYSTORE")"
  PASS=$(head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24)
  printf '%s' "$PASS" > "$(dirname "$KEYSTORE")/password"
  chmod 600 "$(dirname "$KEYSTORE")/password"
  keytool -genkeypair -keystore "$KEYSTORE" -alias buddy -keyalg RSA -keysize 4096 -validity 10000 \
    -storepass "$PASS" -keypass "$PASS" -dname "CN=Buddy SW, O=Personal" >/dev/null 2>&1
fi
apksigner sign --ks "$KEYSTORE" --ks-key-alias buddy \
  --ks-pass "file:$(dirname "$KEYSTORE")/password" \
  --out "$OUT/buddy-release.apk" "$OUT/aligned.apk"
apksigner verify "$OUT/buddy-release.apk"

ls -l "$OUT/buddy-release.apk"
echo "Built $OUT/buddy-release.apk (version $VERSION_NAME, code $VERSION_CODE)"
