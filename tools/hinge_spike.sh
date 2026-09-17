#!/bin/sh
# One-shot magnetometer/gyro hinge-angle spike on the Fold 8:
#   live logcat capture (vendor HAL hinge_angle raw + lid_angle_fusion = ground truth)
#   + PoseProbeActivity recording with the FASTEST-rate mag_u/mag/gyro/accel channels,
#   then pull, parse (tools/hinge_truth.py) and fit (tools/hinge_fit.py).
# Usage: tools/hinge_spike.sh [adb-serial] [stamp]      (fold/unfold a few times, then Enter)
# Output: pose/testdata/hinge-<stamp>.{logcat,truth.jsonl,jsonl,fit.txt} (+ plots/ if matplotlib)
set -u
S="${1:-<serial>}"
STAMP="${2:-$(date +%Y%m%d-%H%M%S)}"
PKG=cz.pflanzer.foldduo
OUT=pose/testdata
LOG="$OUT/hinge-$STAMP.logcat"
mkdir -p "$OUT"
cd "$(dirname "$0")/.." || exit 1

adb -s "$S" get-state >/dev/null 2>&1 || { echo "device $S not reachable"; exit 1; }
adb -s "$S" logcat -c
adb -s "$S" logcat -v threadtime > "$LOG" &
LCPID=$!
sleep 0.5
adb -s "$S" shell am start -n "$PKG/.PoseProbeActivity" --ez record true --ez sensors true >/dev/null
echo "logcat -> $LOG (pid $LCPID); probe recording with high-rate sensors."
echo "Fold and unfold the device a few times (slow and fast, hold a moment at each end)."
printf "Press Enter to stop... "
read -r _
adb -s "$S" shell am start -n "$PKG/.PoseProbeActivity" --ez stop true >/dev/null
sleep 1.5
kill "$LCPID" 2>/dev/null
wait "$LCPID" 2>/dev/null

DEV=$(grep -a 'PoseProbe.*recording to ' "$LOG" | tail -1 | sed -E 's/.*recording to //' | tr -d '\r')
if [ -z "$DEV" ]; then
  DEV="/storage/emulated/0/Android/data/$PKG/files/pose/$(adb -s "$S" shell ls -t /sdcard/Android/data/$PKG/files/pose/ | head -1 | tr -d '\r')"
fi
PROBE="$OUT/hinge-$STAMP.jsonl"
echo "pulling $DEV -> $PROBE"
if ! adb -s "$S" pull "$DEV" "$PROBE" >/dev/null 2>&1; then
  adb -s "$S" exec-out run-as "$PKG" cat "$DEV" > "$PROBE" || { echo "pull failed"; exit 1; }
fi
echo "probe: $(wc -l < "$PROBE") rows; logcat: $(grep -c 'lid_angle_fusion ts=' "$LOG") lid_angle_fusion + $(grep -c 'hinge_angle ts=' "$LOG") hinge_angle HAL lines"

TRUTH="$OUT/hinge-$STAMP.truth.jsonl"
python3 tools/hinge_truth.py "$LOG" -o "$TRUTH" --report
python3 tools/hinge_fit.py "$PROBE" --truth "$TRUTH" --plots "$OUT/plots" | tee "$OUT/hinge-$STAMP.fit.txt"
echo "done: $LOG $TRUTH $PROBE $OUT/hinge-$STAMP.fit.txt"
