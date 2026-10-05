#!/usr/bin/env bash
set -euo pipefail
mkdir -p delivery/player-fixture
if ! command -v ffmpeg >/dev/null; then sudo apt-get update -qq && sudo apt-get install -y -qq ffmpeg; fi
ffmpeg -hide_banner -loglevel error -f lavfi -i 'testsrc2=size=640x360:rate=25' -f lavfi -i 'sine=frequency=440:sample_rate=44100' -t 40 -c:v libx264 -preset ultrafast -pix_fmt yuv420p -b:v 900k -g 100 -c:a aac -b:a 96k -f hls -hls_time 4 -hls_list_size 0 -hls_playlist_type vod delivery/player-fixture/stream.m3u8
python3 -m http.server 8765 --bind 0.0.0.0 --directory delivery/player-fixture >delivery/fixture-server.log 2>&1 &
server_pid=$!
trap 'kill "$server_pid" 2>/dev/null || true' EXIT
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
adb pull /sdcard/Android/data/com.commercialoutcomes.uktelevision.preview/files/playback-test.png delivery/playback-test.png
grep -E 'OK \([0-9]+ test' delivery/instrumentation.log
if grep -q 'FATAL EXCEPTION' delivery/android-crashes.log; then exit 1; fi
