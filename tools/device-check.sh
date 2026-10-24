#!/usr/bin/env bash
set -euo pipefail
mkdir -p build/device-evidence
collect() {
  adb logcat -d > build/device-evidence/logcat.txt || true
  adb shell dumpsys accessibility > build/device-evidence/accessibility.txt || true
  adb exec-out screencap -p > build/device-evidence/final-screen.png || true
  adb pull /data/local/tmp/blockblast-failure.png build/device-evidence/failure.png || true
}
trap collect EXIT
adb shell wm size 720x1560
adb shell wm density 320
adb shell input keyevent KEYCODE_WAKEUP
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant com.pivotry.blockauto android.permission.POST_NOTIFICATIONS
adb shell settings put secure immersive_mode_confirmations confirmed
adb logcat -c
gradle :app:connectedDebugAndroidTest --stacktrace
