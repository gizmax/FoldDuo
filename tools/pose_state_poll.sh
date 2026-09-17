#!/bin/sh
# Phase 0 helper: poll the system fold state from the host while PoseProbeActivity records.
# DeviceStateManager is not app-readable, so this is the ground-truth label source.
# Usage: tools/pose_state_poll.sh <adb-serial> [out.jsonl]   (Ctrl-C to stop)
# Output lines: {"type":"sysState","wallMs":<device clock ms>,"id":1,"name":"TENT"}
# `cmd device_state print-state` prints only the identifier; names per `print-states` on Fold 8:
#   0 CLOSED, 1 TENT, 2 HALF_OPENED, 3 OPENED, 4 CONCURRENT_INNER_DEFAULT, 5 CONCURRENT_OUTER_DEFAULT
S="${1:?adb serial, e.g. <lan-ip>:33065}"
OUT="${2:-pose/testdata/sysstate-$(date +%Y%m%d-%H%M%S).jsonl}"
mkdir -p "$(dirname "$OUT")"
echo "polling device_state on $S -> $OUT (Ctrl-C to stop)"
adb -s "$S" shell 'last=""; while true; do
  id=$(cmd device_state print-state 2>/dev/null | tr -d "\r\n ");
  now=$(date +%s%3N);
  if [ "$id" != "$last" ]; then
    case "$id" in 0) n=CLOSED;; 1) n=TENT;; 2) n=HALF_OPENED;; 3) n=OPENED;; 4) n=CONCURRENT_INNER_DEFAULT;; 5) n=CONCURRENT_OUTER_DEFAULT;; *) n=UNKNOWN;; esac;
    echo "{\"type\":\"sysState\",\"wallMs\":$now,\"id\":${id:-null},\"name\":\"$n\"}";
    last="$id";
  fi;
  sleep 0.05;
done' | tee -a "$OUT"
