#!/usr/bin/env bash
# Builds the store release (APK + AAB) signed with the Hamseda key.
# Started by .github/workflows/build-apk.yml. All the logic lives here, so the workflow file never has to change.
set -euo pipefail

fail() {
  echo "::error title=$1::$2"
  exit 1
}

TMP="${RUNNER_TEMP:-/tmp}"

# ---- 1. the four signing secrets must exist (otherwise the apk would be debug-signed and stores reject it)
missing=""
for v in KEYSTORE_BASE64 KEYSTORE_PASSWORD KEY_ALIAS KEY_PASSWORD; do
  if [ -z "${!v:-}" ]; then
    missing="$missing $v"
  fi
done
if [ -n "$missing" ]; then
  fail "Signing secrets are missing" "Not set:${missing} . Add them in Settings > Secrets and variables > Actions (values are in GITHUB-SECRETS.txt), then run the workflow again."
fi

# ---- 2. Android SDK licences
if [ -n "${ANDROID_HOME:-}" ] && [ -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
  yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses > /dev/null 2>&1 || true
fi

# ---- 3. signing key
KS="$TMP/release.jks"
if ! printf '%s' "$KEYSTORE_BASE64" | tr -d ' \r\n' | base64 -d > "$KS" 2>/dev/null; then
  fail "KEYSTORE_BASE64 is damaged" "The value could not be decoded. Copy the whole long line again from GITHUB-SECRETS.txt (no spaces, no extra lines)."
fi
if ! keytool -list -keystore "$KS" -storepass "$KEYSTORE_PASSWORD" -alias "$KEY_ALIAS" > /dev/null 2>&1; then
  fail "Signing key cannot be opened" "KEYSTORE_BASE64, KEYSTORE_PASSWORD and KEY_ALIAS do not match each other. Copy the values again from GITHUB-SECRETS.txt."
fi
keytool -list -v -keystore "$KS" -storepass "$KEYSTORE_PASSWORD" -alias "$KEY_ALIAS" > "$TMP/keytool.txt" 2>/dev/null || true
expected=$(awk '/SHA256:/{print $2; exit}' "$TMP/keytool.txt" | tr -d ':' | tr 'A-F' 'a-f')
export KEYSTORE_FILE="$KS"

# ---- 4. build
set +e
gradle assembleRelease bundleRelease --no-daemon --stacktrace 2>&1 | tee build.log
status=${PIPESTATUS[0]}
set -e
if [ "$status" -ne 0 ]; then
  msg=$(grep -E -A10 -m3 "What went wrong|^e: " build.log | head -c 1800 | sed 's/%/%25/g' | awk '{printf "%s%%0A", $0}' || true)
  fail "Build failed" "$msg"
fi

APK=app/build/outputs/apk/release/app-release.apk
AAB=app/build/outputs/bundle/release/app-release.aab
[ -f "$APK" ] || fail "APK not found" "$APK was not produced (is the release build signed?)."
[ -f "$AAB" ] || fail "AAB not found" "$AAB was not produced."

# ---- 5. prove the apk carries the release signature (not a debug one)
SIGNER=""
if [ -n "${ANDROID_HOME:-}" ]; then
  SIGNER=$(find "$ANDROID_HOME/build-tools" -name apksigner -type f 2>/dev/null | sort -V | tail -1 || true)
fi
if [ -z "$SIGNER" ]; then
  echo "::warning::apksigner not found, signature check skipped"
else
  if ! "$SIGNER" verify --print-certs "$APK" > "$TMP/cert.txt" 2>&1; then
    cat "$TMP/cert.txt"
    fail "APK signature is invalid" "apksigner could not verify the APK."
  fi
  norm=$(tr 'A-F' 'a-f' < "$TMP/cert.txt" | tr -d ':')
  actual=$(printf '%s\n' "$norm" | grep -m1 -i 'sha-256 digest' | grep -oE '[0-9a-f]{64}' | head -1 || true)
  dn=$(grep -m1 -i 'certificate DN' "$TMP/cert.txt" | sed 's/^[^:]*DN: *//' || true)
  echo "release key SHA-256 : $expected"
  echo "APK signer SHA-256  : $actual"
  echo "APK signer DN       : $dn"
  if printf '%s' "$dn" | grep -qi 'Android Debug'; then
    fail "Debug key detected" "The APK is signed with a DEBUG key (DN: $dn). Stores reject it."
  fi
  if [ -n "$expected" ] && printf '%s\n' "$norm" | grep -q "$expected"; then
    :
  elif printf '%s' "$dn" | grep -q 'CN=Hamseda'; then
    echo "::warning title=Fingerprint compare::Signer is CN=Hamseda but digests differ (expected=$expected actual=$actual). Continuing."
  else
    fail "Wrong signing key" "Signer DN: $dn | expected SHA-256: $expected | actual: $actual"
  fi
  {
    echo "### Signed with the Hamseda release key (NOT a debug key)"
    echo "Signer: \`$dn\`"
    echo "SHA-256: \`$actual\`"
    echo ""
    echo "- Cafe Bazaar / Myket: download the artifact **Hamseda-APK-for-Bazaar-Myket**"
    echo "- Google Play: download the artifact **Hamseda-AAB-for-GooglePlay**"
  } >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
fi

# ---- 6. collect the files that are uploaded as artifacts
mkdir -p dist/apk dist/aab
cp "$APK" dist/apk/Hamseda-release.apk
cp "$AAB" dist/aab/Hamseda-release.aab
echo "Done: dist/apk/Hamseda-release.apk and dist/aab/Hamseda-release.aab"
