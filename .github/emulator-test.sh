#!/usr/bin/env bash
# Runs ConnectTest on the emulator. `am instrument` exits 0 even when tests fail, so its output decides.
set -u
app=io.github.m4hdisa.bridge
adb install -r apks/debug/app-x86_64-debug.apk
adb install -r apks/androidTest/debug/app-debug-androidTest.apk
# Nobody can tap "OK" on the VPN and notification prompts.
adb shell appops set "$app" ACTIVATE_VPN allow
adb shell pm grant "$app" android.permission.POST_NOTIFICATIONS
adb logcat -c
adb shell am instrument -w -r -e uuid "$TEST_UUID" -e port 10808 "$app.test/androidx.test.runner.AndroidJUnitRunner" | tee instrument.txt
adb logcat -d > logcat.txt
grep -E "Bridge|sing-box|AndroidRuntime" logcat.txt | tail -n 80
if grep -q "FAILURES!!!\|INSTRUMENTATION_FAILED\|Process crashed" instrument.txt || ! grep -q "^OK (" instrument.txt; then
  echo "The emulator tests failed"
  exit 1
fi
