#!/usr/bin/env bash
set -euo pipefail
adb shell wm size 1920x1080
adb shell wm density 240
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 0
adb install -r delivery/UK-Television-0.1.0-preview.apk
adb install -r delivery/uk-tv-tests.apk
adb logcat -c
adb shell am instrument -w -r com.commercialoutcomes.uktelevision.preview.test/androidx.test.runner.AndroidJUnitRunner | tee delivery/instrumentation.log
adb logcat -d -s AndroidRuntime:E > delivery/android-crashes.log
adb pull /sdcard/Android/data/com.commercialoutcomes.uktelevision.preview/files/guide.png delivery/guide.png
adb pull /sdcard/Android/data/com.commercialoutcomes.uktelevision.preview/files/options.png delivery/options.png
grep -E 'OK \([0-9]+ test' delivery/instrumentation.log
if grep -q 'FATAL EXCEPTION' delivery/android-crashes.log; then exit 1; fi
