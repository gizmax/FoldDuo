#!/bin/sh
# Install the debug APK on the Fold and re-enable the shade accessibility service,
# which Android switches off on every sideload reinstall.
S="${1:?adb serial, e.g. <serial> or <lan-ip>:<port>}"
APK="${2:-app/build/outputs/apk/debug/app-debug.apk}"
SVC="cz.pflanzer.foldduo/cz.pflanzer.foldduo.SystemShadeAccessibilityService"
adb -s "$S" install -r -t "$APK" | tail -1
cur=$(adb -s "$S" shell settings get secure enabled_accessibility_services | tr -d '\r')
case "$cur" in
  *"$SVC"*) ;;
  ""|null) adb -s "$S" shell settings put secure enabled_accessibility_services "$SVC" ;;
  *) adb -s "$S" shell settings put secure enabled_accessibility_services "$cur:$SVC" ;;
esac
adb -s "$S" shell settings put secure accessibility_enabled 1
echo "accessibility: $(adb -s "$S" shell settings get secure enabled_accessibility_services)"
