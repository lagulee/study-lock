#!/usr/bin/env bash
# 가상 기기에서 폰·탭 크기, 큰 글씨, 다크 모드, 가로 화면으로 모든 화면 스크린샷을 찍는다(CI 전용).
set -u
PKG=com.lagulee.studylock
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 0

run() {
  echo "=== $1"
  adb shell am instrument -w -e variant "$1" -e class $PKG.ScreenshotTest $PKG.test/androidx.test.runner.AndroidJUnitRunner
}

# 갤럭시 S22 크기 (가로는 화면 크기를 바꿔서 흉내 낸다: 가상 기기 회전 설정이 먹지 않았음)
adb shell wm size 1080x2340; adb shell wm density 450
run phone
adb shell settings put system font_scale 1.3
run phonebig
adb shell settings put system font_scale 1.0
adb shell cmd uimode night yes
run phonedark
adb shell cmd uimode night no
# 폰 가로
adb shell wm size 2340x1080
run phoneland

# 갤럭시 탭 S10+ 크기(세로, 가로)
adb shell wm size 1752x2800; adb shell wm density 340
run tab
adb shell wm size 2800x1752
run tabland

rm -rf shots && mkdir shots
adb exec-out run-as $PKG tar c files/shots | tar x -C shots --strip-components=2
ls -la shots | head -100
