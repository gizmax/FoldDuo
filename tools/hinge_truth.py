#!/usr/bin/env python3
"""Parse a saved logcat into hinge-angle ground-truth rows (JSONL, one object per line).

Galaxy Z Fold 8 (SM-F971B, One UI 9): the public TYPE_HINGE_ANGLE only delivers 0/90/180,
but the vendor sensor HAL logs the raw angle to logcat on every step event, and the
`lid_angle_fusion` (com.samsung.sensor.folding_state, SSENSOR-protected) samples during
fold transitions. Readable from the host only, so this is host-side truth.

Recognised lines (any of `-v threadtime`, `-v time`, or either with `-v epoch`):

  sensors-hal: handle_sns_client_event:50, hinge_angle ts=<ns> value  90/148/0
      -> {"type":"hinge_raw","tsNs":…,"wallMs":…,"step":90,"raw":148,"f3":0}
  sensors-hal: handle_sns_client_event:197, [0]lid_angle_fusion ts=<ns> ns value [1/ 17/3] [ 17/3]
        [ -1/-1] [ 0/ 0/ 0] ([0/0/1/1] [ax/ay/az] [ax/ay/az] [n])
      -> {"type":"lid","src":"hal","tsNs":…,"wallMs":…,"state":1,"angle":17,"f3":3,
          "fields":[[1,17,3],[17,3],[-1,-1],[0,0,0],[0,0,1,1],[…],[…],[n]],
          "accelA":[…],"accelB":[…]}
  sensors-hal: … lid_angle_fusion submit_sensors_hal_event 1/ 11/3  11/3      (no ts)
      -> {"type":"lid","src":"submit","wallMs":…,"state":1,"angle":11,"f3":3}
  FlexibleDeviceStateProvider: lid_angle_fusion  Wakeup : [0.0, 5.0, 5.0]           (no ts)
      -> {"type":"lid","src":"fdsp","wallMs":…,"state":0,"angle":5,"f3":5,"v":[0,5,5]}

`tsNs` is the HAL sensor timestamp (CLOCK_BOOTTIME == SystemClock.elapsedRealtimeNanos on
the device; verified against /proc/uptime), so it aligns directly with the probe's
`sensorNs`. `wallMs` is the logcat print time converted to epoch ms (device local time
assumed equal to the host's zone for the MM-DD formats; `-v epoch` needs no assumption).

Which lid field is the angle: `--report` correlates each field of the first bracket with
the raw angle of hinge_angle events logged within 500 ms (Spearman rank correlation).
On the 2026-09-15 buffer field index 1 is the angle (rho ~ 1.0, range 0..180) and the
state index 0 is a coarse 0..3 bucket; f3 is uncorrelated.

Usage: tools/hinge_truth.py <logcat> [-o truth.jsonl] [--year 2026] [--report]
"""
import argparse
import json
import re
import sys
import time

RE_PREFIX_MD = re.compile(r"^\s*(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2})\.(\d{3})")
RE_PREFIX_EPOCH = re.compile(r"^\s*(\d{9,})\.(\d{3})")
RE_HINGE = re.compile(r"hinge_angle ts=(\d+) ns value\s+(-?\d+)/\s*(-?\d+)/\s*(-?\d+)")
RE_LID_HAL = re.compile(r"lid_angle_fusion ts=(\d+) ns value\s*(.*)$")
RE_LID_SUBMIT = re.compile(r"lid_angle_fusion submit_sensors_hal_event\s+(-?\d+)/\s*(-?\d+)/\s*(-?\d+)")
RE_LID_FDSP = re.compile(r"FlexibleDeviceStateProvider: lid_angle_fusion\s+Wakeup\s*:\s*\[([^\]]*)\]")
RE_BRACKET = re.compile(r"\[([^\]]*)\]")


def _num(s):
    s = s.strip()
    try:
        return int(s)
    except ValueError:
        return float(s)


def parse_wall_ms(line, year):
    """Epoch ms of the logcat print time, or None when the prefix is not recognised."""
    m = RE_PREFIX_EPOCH.match(line)
    if m:
        return int(m.group(1)) * 1000 + int(m.group(2))
    m = RE_PREFIX_MD.match(line)
    if m:
        mo, d, h, mi, s, ms = (int(g) for g in m.groups())
        t = time.mktime((year, mo, d, h, mi, s, 0, 0, -1))
        return int(t) * 1000 + ms
    return None


def parse_brackets(payload):
    return [[_num(x) for x in b.split("/") if x.strip() != ""] for b in RE_BRACKET.findall(payload)]


def parse_line(line, year=None):
    """Returns a truth row dict for a recognised line, else None."""
    if "hinge_angle" not in line and "lid_angle_fusion" not in line:
        return None
    year = year or time.localtime().tm_year
    wall = parse_wall_ms(line, year)
    m = RE_HINGE.search(line)
    if m:
        return {"type": "hinge_raw", "tsNs": int(m.group(1)), "wallMs": wall,
                "step": int(m.group(2)), "raw": int(m.group(3)), "f3": int(m.group(4))}
    m = RE_LID_HAL.search(line)
    if m:
        fields = parse_brackets(m.group(2))
        if not fields or len(fields[0]) < 2:
            return None
        row = {"type": "lid", "src": "hal", "tsNs": int(m.group(1)), "wallMs": wall,
               "state": fields[0][0], "angle": fields[0][1],
               "f3": fields[0][2] if len(fields[0]) > 2 else None, "fields": fields}
        vec3 = [f for f in fields if len(f) == 3 and any(isinstance(x, float) for x in f)]
        if len(vec3) >= 2:
            row["accelA"], row["accelB"] = vec3[0], vec3[1]
        return row
    m = RE_LID_SUBMIT.search(line)
    if m:
        return {"type": "lid", "src": "submit", "wallMs": wall,
                "state": int(m.group(1)), "angle": int(m.group(2)), "f3": int(m.group(3))}
    m = RE_LID_FDSP.search(line)
    if m:
        v = [_num(x) for x in m.group(1).split(",") if x.strip()]
        if len(v) < 2:
            return None
        return {"type": "lid", "src": "fdsp", "wallMs": wall, "state": int(v[0]), "angle": v[1],
                "f3": v[2] if len(v) > 2 else None, "v": v}
    return None


def parse_lines(lines, year=None):
    rows = []
    for line in lines:
        row = parse_line(line.rstrip("\n"), year)
        if row is not None:
            rows.append(row)
    return rows


def parse_file(path, year=None):
    with open(path, encoding="utf-8", errors="replace") as f:
        return parse_lines(f, year)


# ---- field report -------------------------------------------------------------------

def _ranks(xs):
    order = sorted(range(len(xs)), key=lambda i: xs[i])
    ranks = [0.0] * len(xs)
    i = 0
    while i < len(order):
        j = i
        while j + 1 < len(order) and xs[order[j + 1]] == xs[order[i]]:
            j += 1
        r = (i + j) / 2.0 + 1.0
        for k in range(i, j + 1):
            ranks[order[k]] = r
        i = j + 1
    return ranks


def pearson(x, y):
    n = len(x)
    if n < 2:
        return float("nan")
    mx, my = sum(x) / n, sum(y) / n
    sxy = sum((a - mx) * (b - my) for a, b in zip(x, y))
    sxx = sum((a - mx) ** 2 for a in x)
    syy = sum((b - my) ** 2 for b in y)
    if sxx == 0 or syy == 0:
        return float("nan")
    return sxy / (sxx * syy) ** 0.5


def spearman(x, y):
    return pearson(_ranks(x), _ranks(y))


def lid_fields_at(lids, ts_ns, window_ns):
    """First-bracket fields of the lid stream at ts_ns: linear interpolation between the two
    bracketing HAL samples when both are within window_ns, else the nearest one, else None.
    (During a fast fold the angle moves ~25 deg per 80 ms sample, so nearest-sample matching
    alone mis-ranks the fields with few pairs.)"""
    import bisect
    ts = [l["tsNs"] for l in lids]
    i = bisect.bisect_left(ts, ts_ns)
    prev = lids[i - 1] if i - 1 >= 0 else None
    nxt = lids[i] if i < len(lids) else None
    dp = ts_ns - prev["tsNs"] if prev else None
    dn = nxt["tsNs"] - ts_ns if nxt else None
    if prev and nxt and dp <= window_ns and dn <= window_ns and dp + dn > 0:
        f = dp / float(dp + dn)
        a, b = prev["fields"][0], nxt["fields"][0]
        return [x + (y - x) * f for x, y in zip(a, b)]
    cands = [(dp, prev), (dn, nxt)]
    cands = [(d, l) for d, l in cands if l is not None and d <= window_ns]
    if not cands:
        return None
    return list(min(cands, key=lambda c: c[0])[1]["fields"][0])


def field_report(rows, window_ns=500_000_000, degrees_min_range=90):
    """Correlates each field of the lid first bracket with hinge_raw.raw at the hinge ts.

    Returns dict {field_index: {"rho","n","min","max","degrees_like"}} plus "best" (the
    field with the highest Spearman among those whose range spans >= degrees_min_range, so a
    coarse state bucket cannot win on a handful of pairs) and "pairs_list" [(raw, fields)].
    """
    lids = sorted((r for r in rows if r["type"] == "lid" and r["src"] == "hal"), key=lambda r: r["tsNs"])
    hinges = [r for r in rows if r["type"] == "hinge_raw"]
    pairs = []  # (raw, interpolated first-bracket fields)
    for h in hinges:
        if not lids:
            break
        f = lid_fields_at(lids, h["tsNs"], window_ns)
        if f is not None:
            pairs.append((h["raw"], f))
    report = {"pairs": len(pairs), "lid_hal": len(lids), "hinge_raw": len(hinges), "pairs_list": pairs}
    nfields = max((len(p[1]) for p in pairs), default=0)
    best_idx, best_rho = None, -2.0
    for i in range(nfields):
        xs = [p[0] for p in pairs if len(p[1]) > i]
        ys = [p[1][i] for p in pairs if len(p[1]) > i]
        rho = spearman(xs, ys) if len(xs) >= 3 else float("nan")
        allv = [l["fields"][0][i] for l in lids if len(l["fields"][0]) > i]
        lo, hi = (min(allv), max(allv)) if allv else (None, None)
        deg_like = allv and (hi - lo) >= degrees_min_range
        report[i] = {"rho": rho, "n": len(xs), "min": lo, "max": hi, "degrees_like": bool(deg_like)}
        if deg_like and rho == rho and rho > best_rho:
            best_idx, best_rho = i, rho
    report["best"] = best_idx
    return report


def format_report(rep):
    out = ["truth: %d hinge_raw, %d lid(hal) rows; %d hinge<->lid pairs (lid interpolated at the hinge ts, window 500 ms)" %
           (rep["hinge_raw"], rep["lid_hal"], rep["pairs"])]
    for raw, f in rep.get("pairs_list", []):
        out.append("    hinge raw %3d  <->  lid [%s]" % (raw, ", ".join("%.1f" % x for x in f)))
    for i in sorted(k for k in rep if isinstance(k, int)):
        r = rep[i]
        tag = ""
        if i == rep["best"]:
            tag = "   <- looks like degrees" + ("" if r["rho"] == r["rho"] and r["rho"] > 0.8 else " (weak rho; few pairs?)")
        elif not r["degrees_like"]:
            tag = "   (range < 90, not degrees)"
        out.append("  lid field[%d]: spearman(raw)=%s  n=%d  range=%s..%s%s" % (
            i, "nan" if r["rho"] != r["rho"] else "%.3f" % r["rho"], r["n"], r["min"], r["max"], tag))
    if rep["best"] is None:
        out.append("  (not enough overlapping hinge/lid samples to rank fields)")
    elif rep["pairs"] < 10:
        out.append("  (only %d pairs: rho is indicative; record more transitions)" % rep["pairs"])
    return "\n".join(out)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("logcat")
    ap.add_argument("-o", "--out", help="write JSONL here (default: stdout)")
    ap.add_argument("--year", type=int, default=None, help="year for MM-DD logcat prefixes (default: now)")
    ap.add_argument("--report", action="store_true", help="print the lid field/angle report to stderr")
    args = ap.parse_args(argv)
    rows = parse_file(args.logcat, args.year)
    rows.sort(key=lambda r: (r.get("wallMs") or 0, r.get("tsNs") or 0))
    out = open(args.out, "w") if args.out else sys.stdout
    try:
        for r in rows:
            out.write(json.dumps(r) + "\n")
    finally:
        if args.out:
            out.close()
    n_h = sum(1 for r in rows if r["type"] == "hinge_raw")
    n_l = sum(1 for r in rows if r["type"] == "lid")
    sys.stderr.write("hinge_truth: %d rows (%d hinge_raw, %d lid) from %s%s\n" % (
        len(rows), n_h, n_l, args.logcat, " -> " + args.out if args.out else ""))
    if args.report or not args.out:
        sys.stderr.write(format_report(field_report(rows)) + "\n")
    return rows


if __name__ == "__main__":
    main()
