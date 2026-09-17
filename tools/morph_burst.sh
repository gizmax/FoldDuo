#!/system/bin/sh
# Runs ON the Fold (push to /data/local/tmp): replay the Continuum A unfold morph via the
# debug broadcast and burst-capture the inner display while it plays, then dump frame stats.
# screencap of the 2448x1848 panel takes ~1 s, so stretch the morph (duration ms) to catch it.
# Usage: sh /data/local/tmp/morph_burst.sh [display-id] [frames] [duration-ms]
# The default display-id below is this Fold 8's inner panel uniqueId — it is device-specific,
# not a launcher constant. On any other device (Fold 7 included) look it up first:
#   adb shell dumpsys display | grep -A2 'mBaseDisplayInfo' # or: dumpsys SurfaceFlinger --display-id
# and pass the inner panel's uniqueId (local:...) as $1.
D="${1:-4630947004648141459}"
N="${2:-6}"
MS="${3:-450}"
rm -f /data/local/tmp/m*.png
dumpsys gfxinfo cz.pflanzer.foldduo reset >/dev/null
sleep 0.5
(am broadcast -a cz.pflanzer.foldduo.DEBUG_MORPH --ei duration "$MS" >/dev/null 2>&1 &)
i=1
while [ "$i" -le "$N" ]; do
  screencap -p -d "$D" /data/local/tmp/m$i.png
  echo "shot $i done at $(date +%s%N | cut -c8-13) ms"
  i=$((i+1))
done
sleep 1.5
dumpsys gfxinfo cz.pflanzer.foldduo | sed -n '/Stats since/,/99th/p'
