#!/usr/bin/env bash
set -euo pipefail

echo "Workspace: $(pwd)"
echo "GITHUB_WORKSPACE: ${GITHUB_WORKSPACE:-<unset>}"
echo "Searching for downloaded APK..."
find "${GITHUB_WORKSPACE:-.}" -maxdepth 5 -type f -name '*.apk' -print || true
APK="$(find "${GITHUB_WORKSPACE:-.}" -maxdepth 5 -type f -name '*.apk' -print -quit)"
if [[ -z "$APK" || ! -s "$APK" ]]; then
  echo "Validated APK was not found in the emulator job workspace."
  ls -la "${GITHUB_WORKSPACE:-.}" || true
  ls -la "${GITHUB_WORKSPACE:-.}/dist" || true
  exit 1
fi

BASELINE_APK="$RUNNER_TEMP/app-release.apk"
NEW_APP_ID="com.amarhisab"
ACTIVITY="com.amarhisab.MainActivity"

echo "Downloading the latest published baseline APK (v1.6) from this repository..."
: "${GH_TOKEN:?GitHub Actions token is required to download the baseline release asset}"
if ! timeout 180s env GH_PROMPT_DISABLED=1 gh release download v1.6 --repo "$GITHUB_REPOSITORY" --pattern "app-release.apk" --dir "$RUNNER_TEMP"; then
  echo "ERROR: baseline APK download failed or exceeded the 180-second limit."
  exit 1
fi
test -s "$BASELINE_APK"
echo "Baseline APK downloaded successfully: $(stat -c%s "$BASELINE_APK") bytes"

# Inspect both APK manifests before attempting an in-place update.
AAPT="$(find "${ANDROID_HOME:-$ANDROID_SDK_ROOT}/build-tools" -type f -name aapt 2>/dev/null | sort -V | tail -n1)"
if [[ -z "$AAPT" ]]; then echo "Android build-tools aapt was not found."; exit 1; fi
BASELINE_APP_ID="$("$AAPT" dump badging "$BASELINE_APK" | sed -n "1s/.*package: name=\x27\([^\x27]*\)\x27.*/\1/p")"
NEW_APK_APP_ID="$("$AAPT" dump badging "$APK" | sed -n "1s/.*package: name=\x27\([^\x27]*\)\x27.*/\1/p")"
if [[ -z "$BASELINE_APP_ID" || -z "$NEW_APK_APP_ID" ]]; then
  echo "ERROR: Could not read package IDs from APK manifests."
  exit 1
fi
echo "Baseline package: $BASELINE_APP_ID"
echo "New APK package: $NEW_APK_APP_ID"
if [[ "$NEW_APK_APP_ID" != "$NEW_APP_ID" ]]; then echo "Unexpected new APK package ID."; exit 1; fi
BASELINE_MATCH=1
if [[ "$BASELINE_APP_ID" != "$NEW_APK_APP_ID" ]]; then
  echo "NOTICE: baseline package differs; data-preserving upgrade cannot be tested with this legacy APK."
  BASELINE_MATCH=0
fi
APP_ID="$NEW_APK_APP_ID"

echo "Waiting for Android emulator core services..."
READY=0
for i in $(seq 1 120); do
  if adb -s emulator-5554 get-state 2>/dev/null | grep -q "^device$" \
    && [[ "$(adb -s emulator-5554 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]] \
    && [[ "$(adb -s emulator-5554 shell getprop init.svc.bootanim 2>/dev/null | tr -d '\r')" == "stopped" ]] \
    && adb -s emulator-5554 shell service check mount 2>/dev/null | grep -q "found" \
    && adb -s emulator-5554 shell service check package 2>/dev/null | grep -q "found" \
    && adb -s emulator-5554 shell service check activity 2>/dev/null | grep -q "found" \
    && adb -s emulator-5554 shell sm list-volumes all >/dev/null 2>&1 \
    && adb -s emulator-5554 shell cmd package list packages >/dev/null 2>&1; then
    READY=1
    break
  fi
  sleep 2
done
if [[ "$READY" != "1" ]]; then
  echo "Emulator core services/storage/package manager did not become ready"
  adb devices -l || true
  adb -s emulator-5554 shell getprop sys.boot_completed || true
  adb -s emulator-5554 shell service list || true
  exit 1
fi

if [[ "$BASELINE_MATCH" == "1" ]]; then
  echo "Installing the previously published APK..."
  adb -s emulator-5554 install "$BASELINE_APK"
  echo "Writing a harmless SharedPreferences sentinel into the old installation..."
  PREFS_XML="$(printf '%s' '<map><string name="upgrade_probe">preserved-across-update</string></map>' | base64 -w0)"
  adb -s emulator-5554 shell "run-as $APP_ID sh -c 'mkdir -p shared_prefs && echo $PREFS_XML | base64 -d > shared_prefs/hisab.xml'"
  adb -s emulator-5554 shell "run-as $APP_ID grep -q preserved-across-update shared_prefs/hisab.xml"
  echo "Installing the new APK as an in-place update (no uninstall/data clear)..."
  adb -s emulator-5554 install -r "$APK"
  echo "Verifying app-private data survived the update..."
  adb -s emulator-5554 shell "run-as $APP_ID grep -q preserved-across-update shared_prefs/hisab.xml"
else
  echo "Installing the new APK as a fresh app (legacy baseline has a different package ID)..."
  adb -s emulator-5554 install "$APK"
fi

adb -s emulator-5554 logcat -c
adb -s emulator-5554 shell am force-stop "$APP_ID"
echo "Launching updated app without ActivityManager short -W wait timeout"
adb -s emulator-5554 shell am start -n "$APP_ID/$ACTIVITY"

LAUNCHED=0
for i in $(seq 1 45); do
  PID="$(adb -s emulator-5554 shell pidof "$APP_ID" 2>/dev/null | tr -d '\r' || true)"
  RESUMED="$(adb -s emulator-5554 shell dumpsys activity activities 2>/dev/null | grep -E 'mResumedActivity|ResumedActivity' | grep -F "$APP_ID/$ACTIVITY" || true)"
  if [[ -n "$PID" && -n "$RESUMED" ]]; then
    LAUNCHED=1
    break
  fi
  if adb -s emulator-5554 logcat -d -t 5000 | grep -E 'FATAL EXCEPTION|Process: com\\.amarhisab\\.app'; then
    echo "App process crashed during launch." >&2
    exit 1
  fi
  sleep 2
done

if [[ "$LAUNCHED" != "1" ]]; then
  echo "Updated app did not reach the resumed/foreground state within 180 seconds."
  adb -s emulator-5554 shell pidof "$APP_ID" || true
  adb -s emulator-5554 shell dumpsys activity activities || true
  adb -s emulator-5554 logcat -d -t 10000 | tail -n 700 || true
  exit 1
fi

adb -s emulator-5554 shell dumpsys activity activities > "$RUNNER_TEMP/amar-hisab-activities.txt"
if [[ "$BASELINE_MATCH" == "1" ]]; then
  echo "In-place update preserved app-private data; MainActivity reached foreground. PID: $PID"
else
  echo "Fresh install and MainActivity launch succeeded. Data-preserving upgrade remains unverified because the legacy baseline package differs."
fi
{
  echo "### Emulator validation"
  if [[ "$BASELINE_MATCH" == "1" ]]; then
    echo "In-place update succeeded; SharedPreferences sentinel survived without uninstall or data clear."
  else
    echo "Fresh installation and foreground launch succeeded. Upgrade/data-retention test skipped because legacy baseline package differs."
  fi
  echo
  echo "Updated app process ID: $PID"
  echo
  echo "$RESUMED"
} >> "$GITHUB_STEP_SUMMARY"
