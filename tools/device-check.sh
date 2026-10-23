#!/usr/bin/env bash
set -euo pipefail
mkdir -p build/device-evidence
collect() {
  adb logcat -d > build/device-evidence/logcat.txt
  adb shell dumpsys accessibility > build/device-evidence/accessibility.txt
  adb exec-out screencap -p > build/device-evidence/final-screen.png
}
trap collect EXIT
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant com.pivotry.blockauto android.permission.POST_NOTIFICATIONS
adb logcat -c
gradle :app:connectedDebugAndroidTest --stacktrace
