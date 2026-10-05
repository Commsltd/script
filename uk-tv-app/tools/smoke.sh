#!/usr/bin/env bash
set -euo pipefail
mkdir -p delivery/player-fixture
if ! command -v ffmpeg >/dev/null; then sudo apt-get update -qq && sudo apt-get install -y -qq ffmpeg; fi
ffmpeg -hide_banner -loglevel error -f lavfi -i 'testsrc2=size=640x360:rate=25' -f lavfi -i 'sine=frequency=440:sample_rate=44100' -t 40 -c:v libx264 -preset ultrafast -pix_fmt yuv420p -b:v 900k -g 100 -c:a aac -b:a 96k -f hls -hls_time 4 -hls_list_size 0 -hls_playlist_type vod delivery/player-fixture/stream.m3u8
python3 -m http.server 8765 --bind 0.0.0.0 --directory delivery/player-fixture >delivery/fixture-server.log 2>&1 &
server_pid=$!
trap 'kill "$server_pid" 2>/dev/null || true' EXIT
# Keep the TV hardware profile's native landscape geometry. The phone-profile
# rotation override used previously distorted the surface and screenshots.
adb shell wm size reset
adb shell wm density reset
# This changes only the disposable test emulator. Prevent Android's one-off
# fullscreen tutorial from stealing remote keys from the app under test.
adb shell settings put secure immersive_mode_confirmations confirmed
adb shell settings put global device_provisioned 1
adb shell settings put secure user_setup_complete 1
adb shell input keyevent KEYCODE_WAKEUP
adb shell input keyevent KEYCODE_HOME
adb install -r delivery/UK-Television-0.2.0-preview.apk
adb install -r delivery/uk-tv-tests.apk
adb logcat -c
set +e
adb shell am instrument -w -r com.commercialoutcomes.uktelevision.preview.test/androidx.test.runner.AndroidJUnitRunner | tee delivery/instrumentation.log
instrument_status=${PIPESTATUS[0]}
adb logcat -d -s AndroidRuntime:E > delivery/android-crashes.log
for name in guide options playback-test; do
  adb pull "/sdcard/Android/data/com.commercialoutcomes.uktelevision.preview/files/$name.png" "delivery/$name.png"
done
adb shell uiautomator dump /sdcard/final-ui.xml
adb pull /sdcard/final-ui.xml delivery/final-ui.xml
adb exec-out screencap -p > delivery/final-screen.png
set -e
[ "$instrument_status" -eq 0 ]
grep -E 'OK \([0-9]+ test' delivery/instrumentation.log
if grep -q 'FATAL EXCEPTION' delivery/android-crashes.log; then exit 1; fi
