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

APP_ID="com.amarhisab.app"
ACTIVITY="com.amarhisab.MainActivity"
BASELINE_APK="$RUNNER_TEMP/Amar-Hisab.apk"

echo "Downloading the last published baseline APK (v2.0.6) using the workflow token..."
: "${GH_TOKEN:?GitHub Actions token is required to download the private release asset}"
if ! timeout 180s env GH_PROMPT_DISABLED=1 gh release download v2.0.6 --repo "$GITHUB_REPOSITORY" --pattern "Amar-Hisab.apk" --dir "$RUNNER_TEMP"; then
  echo "ERROR: baseline APK download failed or exceeded the 180-second limit."
  exit 1
fi
test -s "$BASELINE_APK"
echo "Baseline APK downloaded successfully: $(stat -c%s "$BASELINE_APK") bytes"

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

echo "Installing the previously published APK..."
adb -s emulator-5554 install "$BASELINE_APK"

echo "Writing a harmless SharedPreferences sentinel into the old installation..."
PREFS_XML="$(printf '%s' '<map><string name="upgrade_probe">preserved-across-update</string></map>' | base64 -w0)"
adb -s emulator-5554 shell "run-as $APP_ID sh -c 'mkdir -p shared_prefs && echo $PREFS_XML | base64 -d > shared_prefs/hisab.xml'"
adb -s emulator-5554 shell "run-as $APP_ID grep -q preserved-across-update shared_prefs/hisab.xml"

echo "Installing the newly validated APK as an in-place update (no data clear/uninstall)..."
adb -s emulator-5554 install -r "$APK"

echo "Verifying that app-private data survived the update..."
adb -s emulator-5554 shell "run-as $APP_ID grep -q preserved-across-update shared_prefs/hisab.xml"

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
echo "In-place update preserved app-private data and updated MainActivity reached foreground. PID: $PID"
{
  echo "### Emulator update validation"
  echo "Installed v2.0.6, wrote a private SharedPreferences sentinel, then installed the new APK using adb install -r."
  echo "The sentinel survived the update; no uninstall or app-data clear was performed."
  echo
  echo "Updated app process ID: $PID"
  echo
  echo "$RESUMED"
} >> "$GITHUB_STEP_SUMMARY"
