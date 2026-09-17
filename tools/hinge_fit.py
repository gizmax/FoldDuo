#!/usr/bin/env python3
"""Fit magnetometer / gyro hinge-angle estimators on one PoseProbe JSONL against logcat truth.

Inputs
  probe   PoseProbeActivity JSONL with the high-rate rows (mag_u/mag/gyro/accel, hinge, meta)
  truth   either `--truth truth.jsonl` (output of tools/hinge_truth.py) or `--logcat file`
          (parsed on the fly)

Alignment: probe `sensorNs` and HAL `tsNs` are both CLOCK_BOOTTIME ns on the device, so
they merge directly; rows without `tsNs` (submit/fdsp lines) get one from wallMs via the
meta row's `elapsedToWallOffsetMs` (fallback: median offset of the probe's own rows).
`--align wall` forces wallMs for everything. Only truth samples inside the probe's
high-rate window are used (a truth file may cover several recordings).

Transitions are monotone runs of the truth angle (open or close) spanning >= 60 deg, split
where the angle reverses by more than 15 deg or the stream pauses > 2.5 s. This is the
unit of the leave-one-transition-out (LOO) evaluation: every model is fitted on the other
transitions and scored on the held-out one; the RMS is over the held-out truth samples
(the estimate is interpolated to the truth timestamp).

Models
  (a) magnetometer, raw: piecewise-linear monotone table angle -> feature (10 deg bins,
      pool-adjacent-violators) for the best single axis and for |B|, inverted for the
      estimate. No Earth-field / "magnet valid" threshold: on the Fold 8 the field grows
      as the hinge opens (bx -207 -> -255 uT, |B| 210 -> 258 uT), so the whole 0..180
      range is one monotone curve and the constant part (hard iron + the Earth field on
      the table) is absorbed by the table.
  (b) magnetometer with offset re-anchoring: at each hinge step event the table is shifted
      (in uT) so that it passes through the anchor angle at that instant. Anchors: the
      truth raw angle logged with the step (upper bound), the nominal step angle (median
      raw angle per step and direction, learned from the training transitions), and the
      nominal angle for the 0/180 steps only (the 90 step fires over a wide raw range).
  (c) gyro dead reckoning between step anchors on the best device axis (only informative
      when the IMU half moves; on the table with the other half moving it measures ~0).
  (d) combined: complementary filter, gyro rate about the hinge axis integrated between
      magnetometer samples, magnetometer (nominal anchors) pulling with tau = 30 ms.

Also reported: magnetometer noise at rest (median 1 s-window sigma) -> degrees via the
table slope, and an analytic Earth-field worst case (50 uT, 90 deg device rotation).

Plots go to --plots DIR (default pose/testdata/plots) if matplotlib imports, else skipped.
"""
import argparse
import bisect
import json
import math
import os
import statistics
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import hinge_truth  # noqa: E402

MS = 1e6  # ns per ms
FEATURES = ("|B|", "bx", "by", "bz")
EARTH_UT = 50.0


# ---- small numerics ---------------------------------------------------------------------

def norm(v):
    return math.sqrt(sum(x * x for x in v[:3]))


def rms(errs):
    return math.sqrt(sum(e * e for e in errs) / len(errs)) if errs else float("nan")


def fmt(x, w=7, p=1):
    if x is None or (isinstance(x, float) and x != x):
        return "%*s" % (w, "-")
    return "%*.*f" % (w, p, x)


def nearest(sorted_ts, ts, max_d):
    """Index of the element of sorted_ts nearest to ts within max_d, else None (bisect)."""
    i = bisect.bisect_left(sorted_ts, ts)
    best = None
    for j in (i - 1, i):
        if 0 <= j < len(sorted_ts):
            d = abs(sorted_ts[j] - ts)
            if d <= max_d and (best is None or d < best[0]):
                best = (d, j)
    return best[1] if best else None


def interp(x, xs, ys):
    """Linear interpolation on increasing xs, clamped at the ends."""
    if x <= xs[0]:
        return ys[0]
    if x >= xs[-1]:
        return ys[-1]
    i = bisect.bisect_right(xs, x)
    x0, x1, y0, y1 = xs[i - 1], xs[i], ys[i - 1], ys[i]
    return y0 if x1 == x0 else y0 + (y1 - y0) * (x - x0) / (x1 - x0)


def series_at(est, t, max_gap_ms=200.0):
    """Value of a (t, v) series at t: linear between the bracketing samples (both within
    max_gap_ms), else the nearest one within max_gap_ms, else None."""
    if not est:
        return None
    ts = [e[0] for e in est] if not isinstance(est, tuple) else est[0]
    vs = [e[1] for e in est] if not isinstance(est, tuple) else est[1]
    i = bisect.bisect_left(ts, t)
    prev = i - 1 if i - 1 >= 0 else None
    nxt = i if i < len(ts) else None
    if prev is not None and nxt is not None and t - ts[prev] <= max_gap_ms and ts[nxt] - t <= max_gap_ms:
        return interp(t, [ts[prev], ts[nxt]], [vs[prev], vs[nxt]])
    cands = [(abs(ts[j] - t), j) for j in (prev, nxt) if j is not None and abs(ts[j] - t) <= max_gap_ms]
    return vs[min(cands)[1]] if cands else None


def pav(ys, weights=None, increasing=True):
    """Pool-adjacent-violators isotonic regression; returns the fitted list."""
    w = weights or [1.0] * len(ys)
    blocks = [[y, wi, 1] for y, wi in zip(ys, w)]  # mean, weight, count
    i = 0
    while i < len(blocks) - 1:
        a, b = blocks[i], blocks[i + 1]
        bad = a[0] > b[0] if increasing else a[0] < b[0]
        if bad:
            tw = a[1] + b[1]
            blocks[i] = [(a[0] * a[1] + b[0] * b[1]) / tw, tw, a[2] + b[2]]
            del blocks[i + 1]
            i = max(i - 1, 0)
        else:
            i += 1
    out = []
    for mean, _, count in blocks:
        out.extend([mean] * count)
    return out


# ---- loading ----------------------------------------------------------------------------

def load_probe(path):
    by = {}
    with open(path) as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            r = json.loads(line)
            by.setdefault(r.get("type"), []).append(r)
    for rows in by.values():
        rows.sort(key=lambda r: r.get("sensorNs", r.get("wallMs", 0) * MS))
    return by


def wall_offset_ms(by):
    """wallMs - sensorNs/1e6 for the device; meta row preferred."""
    for m in reversed(by.get("meta", [])):
        if "elapsedToWallOffsetMs" in m:
            return float(m["elapsedToWallOffsetMs"]), "meta"
    offs = [r["wallMs"] - r["sensorNs"] / MS for t in ("hinge", "gravity", "gyro") for r in by.get(t, []) if "sensorNs" in r]
    if offs:
        return statistics.median(offs), "probe-rows"
    return None, "none"


def truth_series(rows, offset_ms, align):
    """List of (tMs, deg, src, row) on the probe's elapsed-ms time base; also latency info."""
    out, lat = [], []
    for r in rows:
        if r["type"] == "hinge_raw":
            deg, src = r["raw"], "hinge_raw"
        elif r["type"] == "lid":
            deg, src = r["angle"], "lid_" + r["src"]
        else:
            continue
        t_ts = r["tsNs"] / MS if r.get("tsNs") is not None else None
        t_wall = (r["wallMs"] - offset_ms) if (r.get("wallMs") is not None and offset_ms is not None) else None
        if t_ts is not None and t_wall is not None:
            lat.append(t_wall - t_ts)
        t = t_wall if (align == "wall" or t_ts is None) else t_ts
        if t is None:
            continue
        out.append((t, float(deg), src, r))
    out.sort(key=lambda x: x[0])
    return out, lat


def probe_window(by, pad_ms=1000.0):
    """(t0, t1) in elapsed ms covered by the probe's high-rate rows (mag_u, else gyro/accel, else hinge)."""
    for t in ("mag_u", "mag", "gyro", "accel", "gravity", "hinge"):
        rows = [r for r in by.get(t, []) if "sensorNs" in r]
        if rows:
            return rows[0]["sensorNs"] / MS - pad_ms, rows[-1]["sensorNs"] / MS + pad_ms
    return -math.inf, math.inf


def transitions(series, gap_ms=2500.0, min_span=60.0, hyst=15.0, pad_ms=300.0):
    """Monotone runs of the truth angle: each run ends when the angle reverses by more than
    `hyst` deg from the run's extreme or the stream pauses > gap_ms. Runs spanning >= min_span
    deg are kept as transitions: {"t0","t1","samples","kind","span"}; kind is open/close."""
    runs = []
    cur, ext, ext_i, direction = [], None, 0, 0
    for s in series:
        if cur and s[0] - cur[-1][0] > gap_ms:
            runs.append(cur)
            cur, ext, ext_i, direction = [], None, 0, 0
        if not cur:
            cur, ext, ext_i, direction = [s], s[1], 0, 0
            continue
        a = s[1]
        if direction == 0:
            if abs(a - cur[0][1]) >= hyst:
                direction = 1 if a > cur[0][1] else -1
                ext, ext_i = a, len(cur)
            cur.append(s)
            continue
        if (a - ext) * direction >= 0:
            ext, ext_i = a, len(cur)
            cur.append(s)
        elif (ext - a) * direction > hyst:
            # reversal: close the run at its extreme, start a new one from there
            runs.append(cur[: ext_i + 1])
            tail = cur[ext_i:] + [s]
            cur, direction = tail, -direction
            ext, ext_i = a, len(cur) - 1
        else:
            cur.append(s)
    if cur:
        runs.append(cur)
    out = []
    for c in runs:
        degs = [x[1] for x in c]
        if len(c) >= 3 and max(degs) - min(degs) >= min_span:
            d = degs[-1] - degs[0]
            kind = "open" if d > 0 else "close"
            out.append({"t0": c[0][0] - pad_ms, "t1": c[-1][0] + pad_ms, "samples": c, "kind": kind,
                        "span": (min(degs), max(degs)), "dur_ms": c[-1][0] - c[0][0]})
    return out


# ---- magnetometer -----------------------------------------------------------------------

class MagModel:
    """Piecewise-linear feature(angle) table from 10-degree bins, made monotone with PAV,
    invertible (angle(feature)) and shiftable (offset in uT)."""

    def __init__(self, pairs, bin_deg=10.0, increasing=None, endpoints=None):
        # pairs: (angle, feature); endpoints: {0: feature_at_closed_rest, 180: feature_at_open_rest}
        bins = {}
        for a, b in pairs:
            bins.setdefault(int(min(max(a, 0), 179.999) // bin_deg), []).append(b)
        keys = sorted(bins)
        self.angles = [k * bin_deg + bin_deg / 2 for k in keys]
        meds = [statistics.median(bins[k]) for k in keys]
        self.rho = hinge_truth.spearman([p[0] for p in pairs], [p[1] for p in pairs]) if len(pairs) >= 3 else float("nan")
        if increasing is None:
            increasing = (self.rho == self.rho) and self.rho > 0
        self.increasing = increasing
        self.raw_meds = meds
        self.b = pav(meds, [len(bins[k]) for k in keys], increasing=increasing)
        for a, v in sorted((endpoints or {}).items()):
            if v is None or v != v or not self.angles:
                continue
            if a <= self.angles[0]:
                self.angles.insert(0, float(a))
                self.b.insert(0, (min if increasing else max)(v, self.b[0]))
            elif a >= self.angles[-1]:
                self.angles.append(float(a))
                self.b.append((max if increasing else min)(v, self.b[-1]))
        self.endpoints = dict(endpoints or {})
        # inverse knots need strictly increasing feature: collapse flat runs to their mid angle
        knots = {}
        for a, b in zip(self.angles, self.b):
            knots.setdefault(b, []).append(a)
        bs = sorted(knots)
        self.inv_b = bs
        self.inv_a = [statistics.mean(knots[b]) for b in bs]
        self.ok = len(bs) >= 2
        self.n = len(pairs)

    def angle(self, feature, offset=0.0):
        if not self.ok:
            return float("nan")
        return interp(feature - offset, self.inv_b, self.inv_a)

    def b_at(self, angle):
        return interp(angle, self.angles, self.b) if self.angles else float("nan")

    def slope_at(self, angle):
        """|d feature / d angle| in uT/deg at `angle` (secant over the enclosing 20 deg)."""
        if not self.ok:
            return float("nan")
        a0, a1 = max(angle - 10.0, self.angles[0]), min(angle + 10.0, self.angles[-1])
        return abs(self.b_at(a1) - self.b_at(a0)) / (a1 - a0) if a1 > a0 else float("nan")

    def offset_for(self, feature, anchor_deg):
        """Offset (uT) so that angle(feature, offset) == anchor_deg."""
        return feature - self.b_at(anchor_deg)

    @property
    def range(self):
        return (min(self.b), max(self.b)) if self.b else (float("nan"), float("nan"))


def feature_streams(by):
    """{feature: [(tMs, value)]} from mag_u rows, sorted by time."""
    magu = by.get("mag_u", [])
    ts = [r["sensorNs"] / MS for r in magu]
    out = {}
    for f in FEATURES:
        if f == "|B|":
            vals = [norm(r["v"]) for r in magu]
        else:
            i = "xyz".index(f[1])
            vals = [r["v"][i] for r in magu]
        out[f] = (ts, vals)
    return out


def truth_features(streams, samples, max_gap_ms=50.0):
    """[(tMs, deg, {feature: value})] for the truth samples that have mag_u within reach."""
    out = []
    for t, deg, src, _ in samples:
        fv = {}
        for f, (ts, vals) in streams.items():
            v = series_at((ts, vals), t, max_gap_ms)
            if v is not None:
                fv[f] = v
        if len(fv) == len(streams):
            out.append((t, deg, fv))
    return out


# ---- anchors -----------------------------------------------------------------------------

def step_events(by, series, window_ms=300.0):
    """All hinge step events of the probe: [(tMs, stepDeg, truthRawDeg|None, dirTag)] sorted.
    dirTag: 'closed' (0), 'open' (180), 'half_opening' (90 after 0), 'half_closing' (90 after 180)."""
    hr = [s for s in series if s[2] == "hinge_raw"]
    hr_t = [s[0] for s in hr]
    out, prev = [], None
    for h in sorted(by.get("hinge", []), key=lambda r: r["sensorNs"]):
        t = h["sensorNs"] / MS
        step = float(h["deg"])
        i = nearest(hr_t, t, window_ms)
        raw = hr[i][1] if i is not None else None
        if step <= 45:
            tag = "closed"
        elif step >= 135:
            tag = "open"
        else:
            tag = "half_closing" if prev is not None and prev >= 135 else "half_opening"
        out.append((t, step, raw, tag))
        prev = step
    return out


def nominal_anchors(events, exclude=(), defaults=None):
    """Median truth raw angle per dirTag over the events not in `exclude` (by time)."""
    nom = dict(defaults or {"closed": 15.0, "half_opening": 40.0, "half_closing": 140.0, "open": 172.0})
    groups = {}
    for t, step, raw, tag in events:
        if raw is not None and t not in exclude:
            groups.setdefault(tag, []).append(raw)
    for tag, vals in groups.items():
        nom[tag] = statistics.median(vals)
    return nom, {k: len(v) for k, v in groups.items()}


# ---- estimators (streaming over the whole recording) ----------------------------------------

def mag_estimate(model, stream, events, anchor_mode, nominal, anchor_steps=("closed", "open", "half_opening", "half_closing")):
    """[(tMs, angle)] at every mag sample. anchor_mode: None (raw), 'truth', 'nominal'.
    The anchor uses the mag sample nearest to the step timestamp (the event carries the
    HAL timestamp, not the delivery time), so a late-delivered step still anchors correctly."""
    ts, vals = stream
    est, offset = [], 0.0
    ei = 0
    for i, t in enumerate(ts):
        while anchor_mode and ei < len(events) and events[ei][0] <= t:
            et, step, raw, tag = events[ei]
            ei += 1
            if tag not in anchor_steps:
                continue
            anchor = raw if anchor_mode == "truth" else nominal.get(tag)
            if anchor is None:
                continue
            v = series_at((ts, vals), et, 60.0)
            offset = model.offset_for(vals[i] if v is None else v, anchor)
        est.append((t, model.angle(vals[i], offset)))
    return est


def gyro_bias(by, still_rad=0.03):
    g = by.get("gyro", [])
    still = [r["v"] for r in g if all(abs(x) < still_rad for x in r["v"][:3])]
    if len(still) < 10:
        return [0.0, 0.0, 0.0], 0
    return [statistics.median(v[i] for v in still) for i in range(3)], len(still)


def integrate_axis(gyro, bias, axis, sign, t0, t1, start_deg, anchors, use_truth_anchor):
    """Dead-reckoned angle at each gyro sample in [t0,t1]; anchors (t, step, raw, tag) reset it."""
    est, cur, prev_t = [], start_deg, None
    ai = 0
    for r in gyro:
        t = r["sensorNs"] / MS
        if t < t0:
            continue
        if t > t1:
            break
        while ai < len(anchors) and anchors[ai][0] <= t:
            a = anchors[ai]
            cur = a[2] if (use_truth_anchor and a[2] is not None) else a[1]
            ai += 1
        if prev_t is not None:
            w = (r["v"][axis] - bias[axis]) * sign
            cur += math.degrees(w) * (t - prev_t) / 1000.0
        prev_t = t
        est.append((t, cur))
    return est


def combined_estimate(gyro, bias, axis, sign, mag_est, tau_ms=30.0):
    """Complementary filter: gyro rate about the hinge axis integrated between mag samples,
    the magnetometer estimate pulling the state with time constant tau_ms. [(tMs, angle)]."""
    if not mag_est:
        return []
    out, cur = [], mag_est[0][1]
    gi, prev_t = 0, None
    g_ts = [r["sensorNs"] / MS for r in gyro]
    for t, a_mag in mag_est:
        # integrate gyro samples up to t
        while gi < len(g_ts) and g_ts[gi] <= t:
            if prev_t is not None:
                cur += math.degrees((gyro[gi]["v"][axis] - bias[axis]) * sign) * (g_ts[gi] - prev_t) / 1000.0
            prev_t = g_ts[gi]
            gi += 1
        dt = (t - out[-1][0]) if out else 10.0
        k = 1.0 - math.exp(-dt / tau_ms)
        cur += k * (a_mag - cur)
        out.append((t, cur))
    return out


def eval_est(est, samples):
    """(RMS, errors) of estimate vs truth samples, estimate interpolated to the truth time."""
    if not est:
        return float("nan"), []
    errs = []
    for t, deg, *_ in samples:
        v = series_at(est, t)
        if v is not None and v == v:
            errs.append(v - deg)
    return rms(errs), errs


def probe_vs_hal_accel(by, tr):
    """Which HAL accel vector (A or B) follows the probe accelerometer in this transition."""
    acc = by.get("accel", [])
    ta = [r["sensorNs"] / MS for r in acc]
    dA, dB, swingA, swingB, prevA, prevB = [], [], 0.0, 0.0, None, None
    for t, deg, src, r in tr["samples"]:
        if src != "lid_hal" or "accelA" not in r:
            continue
        i = nearest(ta, t, 60.0)
        if i is not None:
            pv = acc[i]["v"]
            dA.append(norm([a - b for a, b in zip(pv, r["accelA"])]))
            dB.append(norm([a - b for a, b in zip(pv, r["accelB"])]))
        if prevA is not None:
            swingA += norm([a - b for a, b in zip(r["accelA"], prevA)])
            swingB += norm([a - b for a, b in zip(r["accelB"], prevB)])
        prevA, prevB = r["accelA"], r["accelB"]
    if not dA:
        return None
    imu = "A" if statistics.mean(dA) <= statistics.mean(dB) else "B"
    return {"imu_half": imu, "distA": statistics.mean(dA), "distB": statistics.mean(dB),
            "swingA": swingA, "swingB": swingB,
            "imu_share": (swingA if imu == "A" else swingB) / (swingA + swingB) if swingA + swingB > 0 else float("nan")}


# ---- rest windows -----------------------------------------------------------------------------

def rest_windows(streams, events, min_ms=1000.0, sigma_ut=1.0, feature="bx"):
    """Rests after a 0/180 step: [(tStart, tEnd, tag, {feature: median})] where the feature's
    sigma over the first min_ms after settling stays below sigma_ut until the next step event.
    tStart is when the rest is confirmed (settle + min_ms), i.e. when an on-device estimator could
    anchor; the medians are over the confirmed part only."""
    ts, vals = streams[feature]
    out = []
    for i, (t, step, raw, tag) in enumerate(events):
        if tag not in ("closed", "open"):
            continue
        t_next = events[i + 1][0] if i + 1 < len(events) else ts[-1]
        t_cur = t + 300.0  # settle
        while t_cur + min_ms <= t_next:
            a, b = bisect.bisect_left(ts, t_cur), bisect.bisect_left(ts, t_cur + min_ms)
            if b - a >= 20 and statistics.pstdev(vals[a:b]) < sigma_ut:
                c = bisect.bisect_left(ts, t_next)
                out.append((t_cur + min_ms, t_next, tag, {f: statistics.median(sv[a:c]) for f, (_, sv) in streams.items()}))
                break
            t_cur += 250.0
    return out


def rest_endpoints(rests, feature, exclude_range=None):
    """{0: median closed-rest feature, 180: median open-rest feature} over the rests whose
    confirmation time is outside exclude_range (a held-out transition)."""
    ep = {}
    for angle, tag in ((0, "closed"), (180, "open")):
        v = [r[3][feature] for r in rests if r[2] == tag and not (exclude_range and exclude_range[0] <= r[0] <= exclude_range[1])]
        if v:
            ep[angle] = statistics.median(v)
    return ep


def mag_estimate_rest(model, stream, rests):
    """Like mag_estimate but anchored only at confirmed rests: at each rest's confirmation time the
    offset is set so the table passes through 0 (closed) / 180 (open) at the rest's median feature."""
    ts, vals = stream
    est, offset, ri = [], 0.0, 0
    for i, t in enumerate(ts):
        while ri < len(rests) and rests[ri][0] <= t:
            t_c, _, tag, med = rests[ri]
            ri += 1
            a = 0.0 if tag == "closed" else 180.0
            v = series_at((ts, vals), t_c, 60.0)
            offset = model.offset_for(vals[i] if v is None else v, a)
        est.append((t, model.angle(vals[i], offset)))
    return est


# ---- resolution ----------------------------------------------------------------------------

def rest_noise(by, streams, events, win_ms=1000.0, still_rad=0.05, step_clear_ms=1000.0):
    """Median sigma (uT) per feature over 1 s windows where the gyro is still and no step
    event is within step_clear_ms; returns ({feature: sigma}, n_windows)."""
    gyro = by.get("gyro", [])
    bias, _ = gyro_bias(by)
    g_ts = [r["sensorNs"] / MS for r in gyro]
    ev_ts = [e[0] for e in events]
    ts0 = streams["bx"][0]
    if not ts0:
        return {}, 0
    sig = {f: [] for f in streams}
    t = ts0[0]
    n = 0
    while t + win_ms <= ts0[-1]:
        a, b = bisect.bisect_left(ts0, t), bisect.bisect_left(ts0, t + win_ms)
        ga, gb = bisect.bisect_left(g_ts, t), bisect.bisect_left(g_ts, t + win_ms)
        still = (gb - ga) >= 10 and all(abs(gyro[i]["v"][k] - bias[k]) < still_rad for i in range(ga, gb) for k in range(3))
        clear = nearest(ev_ts, t + win_ms / 2, win_ms / 2 + step_clear_ms) is None if ev_ts else True
        if b - a >= 20 and still and clear:
            for f, (_, vals) in streams.items():
                sig[f].append(statistics.pstdev(vals[a:b]))
            n += 1
        t += win_ms
    return {f: (statistics.median(v) if v else float("nan")) for f, v in sig.items()}, n


# ---- main ---------------------------------------------------------------------------------

def run(args):
    out = []
    p = out.append
    by = load_probe(args.probe)
    truth_rows = hinge_truth.parse_file(args.logcat, args.year) if args.logcat else [json.loads(l) for l in open(args.truth) if l.strip()]
    offset, offsrc = wall_offset_ms(by)
    series_all, lat = truth_series(truth_rows, offset, args.align)
    w0, w1 = probe_window(by)
    series = [s for s in series_all if w0 <= s[0] <= w1]
    # the fits use only samples with a HAL timestamp (hinge_raw, lid_hal); submit/fdsp are wall-timed duplicates
    series_ts = [s for s in series if s[3].get("tsNs") is not None] if args.align != "wall" else series
    p("probe: %s  rows: %s" % (args.probe, ", ".join("%s=%d" % (k, len(v)) for k, v in sorted(by.items()))))
    p("truth: %d samples in the probe window (of %d in the file); %s" % (
        len(series), len(series_all), ", ".join("%s=%d" % (s, sum(1 for x in series if x[2] == s)) for s in sorted(set(x[2] for x in series)))))
    p("time base: elapsed-ms; wall offset %s (%s); align=%s; logcat print latency vs HAL ts: median %s ms over %d rows" % (
        fmt(offset, 1, 1) if offset is not None else "-", offsrc, args.align,
        fmt(statistics.median(lat), 1, 0) if lat else "-", len(lat)))
    if lat and abs(statistics.median(lat)) > 2000 and args.align != "wall":
        p("WARNING: HAL ts and wall-derived time disagree by > 2 s; HAL ts may not be CLOCK_BOOTTIME here. Re-run with --align wall.")
    t_ref = w0 + 1000.0
    hinge_rows = [h for h in by.get("hinge", []) if w0 <= h["sensorNs"] / MS <= w1]
    if hinge_rows:
        p("probe hinge steps: " + " ".join("%.0f@%.1fs" % (h["deg"], (h["sensorNs"] / MS - t_ref) / 1000.0) for h in hinge_rows))
    trs = transitions(series_ts)
    p("transitions (monotone runs >= 60 deg): %d" % len(trs))
    result = {"transitions": [], "mag": {}, "gyro": {}, "combined": {}, "loo": {}, "resolution": {}, "nominal": {}}
    if not trs:
        p("no fold transition in truth inside the probe window (need >= 3 truth samples spanning >= 60 deg)")
        return "\n".join(out), result
    for k, tr in enumerate(trs):
        p("  #%-2d %-5s %7.2fs..%7.2fs  %5.2f s  %3d truth samples  %3.0f..%3.0f deg" % (
            k, tr["kind"], (tr["samples"][0][0] - t_ref) / 1000, (tr["samples"][-1][0] - t_ref) / 1000, tr["dur_ms"] / 1000,
            len(tr["samples"]), tr["span"][0], tr["span"][1]))
        result["transitions"].append({"kind": tr["kind"], "dur_ms": tr["dur_ms"], "n": len(tr["samples"])})

    # step events / nominal anchors ---------------------------------------------------------
    events = [e for e in step_events(by, series_ts) if w0 <= e[0] <= w1]
    nominal_all, nom_n = nominal_anchors(events)
    p("\nhinge step events in window: %d; measured step anchors (median truth raw at the step): %s" % (
        len(events), ", ".join("%s %.0f deg (n=%d)" % (k, nominal_all[k], nom_n.get(k, 0)) for k in ("closed", "half_opening", "half_closing", "open"))))
    result["nominal"] = nominal_all

    # (a) magnetometer tables ------------------------------------------------------------------
    p("\n== (a) magnetometer features (uncalibrated, uT) vs truth angle ==")
    streams = feature_streams(by)
    rests = rest_windows(streams, events)
    p("  rests confirmed (0/180 step + >= 1 s still, bx sigma < 1 uT): %d; %s" % (len(rests), "  ".join(
        "%s@%.1fs bx %.1f |B| %.1f" % (r[2], (r[0] - t_ref) / 1000, r[3]["bx"], r[3]["|B|"]) for r in rests)))
    endpoints_all = {f: rest_endpoints(rests, f) for f in FEATURES}
    p("  rest endpoints (median): " + "  ".join("%s 0deg %s / 180deg %s" % (f, fmt(endpoints_all[f].get(0), 6), fmt(endpoints_all[f].get(180), 6)) for f in ("bx", "|B|")))
    result["rests"] = [(r[2], r[3]["bx"], r[3]["|B|"]) for r in rests]
    result["endpoints"] = endpoints_all
    per_tr = [truth_features(streams, tr["samples"]) for tr in trs]
    all_tf = [x for tf in per_tr for x in tf]
    if len(all_tf) < 3:
        p("  not enough truth samples with mag_u within 50 ms")
        return "\n".join(out), result
    p("  per feature: spearman(angle, feature) pooled over %d samples, table range, in-sample RMS" % len(all_tf))
    models = {}
    for f in FEATURES:
        pairs = [(deg, fv[f]) for _, deg, fv in all_tf]
        m = MagModel(pairs, endpoints=endpoints_all[f])
        models[f] = m
        e = [m.angle(fv[f]) - deg for _, deg, fv in all_tf]
        p("    %-4s rho %s  %s..%s uT  (%s)  in-sample RMS %s deg" % (
            f, fmt(m.rho, 6, 3), fmt(m.range[0], 6), fmt(m.range[1], 6), "increasing" if m.increasing else "decreasing", fmt(rms(e), 5)))
        result["mag"][f] = {"rho": m.rho, "rms_in": rms(e), "range": m.range}
    axes = [f for f in FEATURES if f != "|B|"]
    best_axis = max(axes, key=lambda f: abs(models[f].rho) if models[f].rho == models[f].rho else -1)
    chosen = [best_axis, "|B|"]
    result["mag"]["best_axis"] = best_axis
    p("  best single axis: %s (|rho| %.3f); |B| rho %.3f" % (best_axis, abs(models[best_axis].rho), models["|B|"].rho))
    for f in chosen:
        m = models[f]
        p("  pooled table %s: bin-center angle -> %s median (PAV-monotone; 0/180 = rest endpoints)" % (f, f))
        p("      " + " ".join("%6.0f" % a for a in m.angles))
        p("      " + " ".join("%6.1f" % b for b in m.b))
        p("      slope uT/deg at 10/45/90/135/170: " + " ".join("%.2f" % m.slope_at(a) for a in (10, 45, 90, 135, 170)))
    p("  per transition spearman: " + "  ".join("#%d %s/%s" % (k, fmt(hinge_truth.spearman([d for _, d, _ in tf], [fv[best_axis] for _, _, fv in tf]), 6, 3),
                                                              fmt(hinge_truth.spearman([d for _, d, _ in tf], [fv["|B|"] for _, _, fv in tf]), 6, 3))
                                                 for k, tf in enumerate(per_tr) if len(tf) >= 3) + "   (%s/|B|)" % best_axis)

    # (b) resolution ---------------------------------------------------------------------------
    sig, nwin = rest_noise(by, streams, events)
    p("\n== resolution: magnetometer noise at rest (median sigma over %d still 1 s windows) ==" % nwin)
    for f in chosen:
        m = models[f]
        s = sig.get(f, float("nan"))
        p("  %-4s sigma %s uT -> %s deg @10, %s deg @90, %s deg @170 (sigma / table slope)" % (
            f, fmt(s, 5, 2), fmt(s / m.slope_at(10), 5), fmt(s / m.slope_at(90), 5), fmt(s / m.slope_at(170), 5)))
        result["resolution"][f] = {"sigma": s, "deg10": s / m.slope_at(10), "deg90": s / m.slope_at(90), "deg170": s / m.slope_at(170)}

    # (c) gyro axis --------------------------------------------------------------------------
    p("\n== (c) gyro dead-reckoning between step anchors (truth-raw anchors) ==")
    gyro = by.get("gyro", [])
    bias, nstill = gyro_bias(by)
    p("gyro rows %d; bias (rad/s) %s from %d still samples" % (len(gyro), " ".join("%.4f" % b for b in bias), nstill))
    axis_names = ["x", "y", "z"]
    best_votes = {}
    gyro_res = []
    for k, tr in enumerate(trs):
        if not gyro:
            break
        anchors = [e for e in events if tr["t0"] <= e[0] <= tr["t1"]]
        start = tr["samples"][0][1]
        table = []
        for axis in range(3):
            for sign in (1, -1):
                est = integrate_axis(gyro, bias, axis, sign, tr["t0"], tr["t1"], start, anchors, True)
                r, _ = eval_est(est, tr["samples"])
                table.append((r, axis, sign, est))
        table.sort(key=lambda x: (x[0] != x[0], x[0]))
        r, axis, sign, est = table[0]
        truth_swing = tr["span"][1] - tr["span"][0]
        free = integrate_axis(gyro, bias, axis, sign, tr["t0"], tr["t1"], start, [], True)
        free_swing = (max(e[1] for e in free) - min(e[1] for e in free)) if free else float("nan")
        share = free_swing / truth_swing if truth_swing else float("nan")
        moved = "IMU half moved" if share >= 0.7 else "both halves moved" if share >= 0.3 else "other half moved"
        halves = probe_vs_hal_accel(by, tr)
        p("  #%-2d %-5s anchors=%d  best axis %s%s RMS %s deg | %s" % (
            k, tr["kind"], len(anchors), "+" if sign > 0 else "-", axis_names[axis], fmt(r, 5),
            "  ".join("%s%s %s" % ("+" if s > 0 else "-", axis_names[a], fmt(rr, 5)) for rr, a, s, _ in table[1:])))
        p("      gyro swing %.0f deg / truth swing %.0f deg = %.2f -> %s%s" % (
            free_swing, truth_swing, share, moved,
            ("; HAL accel %s follows the probe (dist %.2f vs %.2f), IMU-half share of accel motion %.2f" % (
                halves["imu_half"], halves["distA"], halves["distB"], halves["imu_share"])) if halves else ""))
        if r == r:
            best_votes[(axis, sign)] = best_votes.get((axis, sign), 0) + 1
        gyro_res.append({"tr": k, "axis": axis, "sign": sign, "rms": r, "share": share, "moved": moved, "anchors": len(anchors)})
    if best_votes:
        (gaxis, gsign), _ = max(best_votes.items(), key=lambda kv: kv[1])
        n_imu = sum(1 for g in gyro_res if g["share"] >= 0.7)
        p("  overall best axis: %s%s (wins %d/%d); IMU half was the (mainly) moving half in %d/%d transitions" % (
            "+" if gsign > 0 else "-", axis_names[gaxis], best_votes[(gaxis, gsign)], len(gyro_res), n_imu, len(gyro_res)))
        result["gyro"] = {"axis": gaxis, "sign": gsign, "per_transition": gyro_res, "imu_moving": n_imu}
    else:
        gaxis, gsign = 1, 1
        p("  no gyro rows in the probe file")

    # (d) leave-one-transition-out --------------------------------------------------------------
    variants = [("raw", None, None), ("anch-truth", "truth", None), ("anch-nom", "nominal", None),
                ("anch-nom0/180", "nominal", ("closed", "open")), ("anch-nom180", "nominal", ("open",)),
                ("anch-nomFlat", "nominal", ("open", "half_closing")), ("anch-rest", "rest", None)]
    cols = ["%s %s" % (v[0], f) for f in chosen for v in variants] + ["gyro", "combined %s" % best_axis]
    p("\n== (d) leave-one-transition-out RMS (deg): model fitted on the other transitions, scored on the held-out one ==")
    p("  raw = table only; anch-truth = offset re-anchored at every step to the truth raw angle (upper bound);")
    p("  anch-nom = re-anchored to the nominal step angle (medians above, learned on the training transitions);")
    p("  anch-nom0/180 = nominal anchors at the 0/180 steps only; anch-nom180 = at the 180 step only; anch-nomFlat = at the")
    p("  180 step and the closing 90 step (the flat end of the table, where an anchor error costs the fewest uT);")
    p("  anch-rest = anchored only at confirmed rests (0/180 step + 1 s still) to the table's rest endpoints;")
    p("  gyro = %s%s between truth anchors;" % ("+" if gsign > 0 else "-", axis_names[gaxis]))
    p("  combined = gyro %s%s + magnetometer %s anch-rest, complementary filter tau 30 ms" % ("+" if gsign > 0 else "-", axis_names[gaxis], best_axis))
    pooled = {c: [] for c in cols}
    per_rows = []
    for k, tr in enumerate(trs):
        train = [x for j, tf in enumerate(per_tr) if j != k for x in tf]
        if len(train) < 3 or not per_tr[k]:
            continue
        tr_events = set(e[0] for e in events if tr["t0"] <= e[0] <= tr["t1"])
        nominal, _ = nominal_anchors(events, exclude=tr_events, defaults=nominal_all)
        row = {}
        for f in chosen:
            m = MagModel([(deg, fv[f]) for _, deg, fv in train], endpoints=rest_endpoints(rests, f, (tr["t0"], tr["t1"])))
            if not m.ok:
                continue
            for name, mode, steps in variants:
                if mode == "rest":
                    est = mag_estimate_rest(m, streams[f], rests)
                else:
                    est = mag_estimate(m, streams[f], events, mode, nominal, steps or ("closed", "open", "half_opening", "half_closing"))
                r, errs = eval_est(est, tr["samples"])
                row["%s %s" % (name, f)] = r
                pooled["%s %s" % (name, f)].extend(errs)
                if name == "anch-rest" and f == best_axis:
                    mag_nom_est = est
        if gyro:
            anchors = [e for e in events if tr["t0"] <= e[0] <= tr["t1"]]
            g_est = integrate_axis(gyro, bias, gaxis, gsign, tr["t0"], tr["t1"], tr["samples"][0][1], anchors, True)
            r, errs = eval_est(g_est, tr["samples"])
            row["gyro"] = r
            pooled["gyro"].extend(errs)
            c_est = combined_estimate(gyro, bias, gaxis, gsign, mag_nom_est)
            r, errs = eval_est(c_est, tr["samples"])
            row["combined %s" % best_axis] = r
            pooled["combined %s" % best_axis].extend(errs)
        per_rows.append((k, row))
        result["loo"][k] = row
    if per_rows:
        def med(rows, c):
            v = [r[c] for _, r in rows if c in r and r[c] == r[c]]
            return statistics.median(v) if v else None
        slow = [(k, r) for k, r in per_rows if trs[k]["dur_ms"] >= 1000]
        groups = [(f, ["%s %s" % (v[0], f) for v in variants] + (["gyro", "combined %s" % best_axis] if f == best_axis else [])) for f in chosen]
        for f, gcols in groups:
            p("  -- %s --" % f)
            p("  %-3s %-5s %5s %4s | " % ("#", "kind", "dur", "n") + " ".join("%13s" % c.replace(" " + f, "") for c in gcols))
            for k, row in per_rows:
                tr = trs[k]
                p("  %-3d %-5s %5.2f %4d | " % (k, tr["kind"], tr["dur_ms"] / 1000, len(tr["samples"])) + " ".join("%13s" % fmt(row.get(c), 5) for c in gcols))
            p("  %-3s %-5s %5s %4s | " % ("all", "", "", len(pooled[gcols[0]])) + " ".join("%13s" % fmt(rms(pooled[c]), 5) for c in gcols) + "   (pooled RMS)")
            p("  %-3s %-5s %5s %4s | " % ("med", "", "", "") + " ".join("%13s" % fmt(med(per_rows, c), 5) for c in gcols) + "   (median of per-transition RMS)")
            if slow:
                p("  %-3s %-5s %5s %4s | " % ("slow", "", "", len(slow)) + " ".join("%13s" % fmt(med(slow, c), 5) for c in gcols) + "   (median over transitions >= 1 s)")
        result["loo"]["pooled"] = {c: rms(pooled[c]) for c in cols}
        result["loo"]["cols"] = cols

    # Earth-field worst case ----------------------------------------------------------------------
    m = models[best_axis]
    p("\n== Earth-field robustness (analytic, |E| = %.0f uT, no new data) ==" % EARTH_UT)
    p("  The table absorbs whatever constant field the device sat in while calibrating; a rotation of the whole phone")
    p("  changes the Earth component along the feature. Rotating 90 deg about device x leaves bx unchanged (0 uT);")
    p("  about y or z the change is up to |E|*sqrt(2) = %.0f uT (e.g. E lying in the x-z plane at 45 deg)." % (EARTH_UT * math.sqrt(2)))
    p("  |B| behaves the same because the magnet field is ~along -x (|B| ~ -bx): any tilt of |E| by phi shifts it by ~|E|*sin(phi).")
    for phi in (10, 30, 90):
        d = EARTH_UT * (math.sqrt(2) if phi == 90 else math.sin(math.radians(phi)))
        p("  rotation %3d deg about y/z: up to %5.1f uT -> %4.0f deg @10, %4.0f deg @90, %4.0f deg @170 (%s slope)" % (
            phi, d, d / m.slope_at(10), d / m.slope_at(90), d / m.slope_at(170), best_axis))
    result["earth"] = {"worst_ut": EARTH_UT * math.sqrt(2), "deg10": EARTH_UT * math.sqrt(2) / m.slope_at(10),
                       "deg170": EARTH_UT * math.sqrt(2) / m.slope_at(170)}
    p("  -> hand-held use needs compensation: learn E_world = R(q) * (B_meas - M(step)) while a 0/180 step is stable > 1 s")
    p("     (q from GAME_ROTATION_VECTOR - TYPE_ROTATION_VECTOR uses this very magnetometer and is dominated by the magnet;")
    p("     M(step) = the per-axis table value at the step angle), then subtract R(q)^T * E_world from every sample. Residual")
    p("     ~ |E| * attitude error: 2 deg -> %.1f uT -> %.0f-%.0f deg; 5 deg -> %.1f uT -> %.0f-%.0f deg (re-learn at every rest)." % (
        EARTH_UT * math.sin(math.radians(2)), EARTH_UT * math.sin(math.radians(2)) / m.slope_at(10), EARTH_UT * math.sin(math.radians(2)) / m.slope_at(170),
        EARTH_UT * math.sin(math.radians(5)), EARTH_UT * math.sin(math.radians(5)) / m.slope_at(10), EARTH_UT * math.sin(math.radians(5)) / m.slope_at(170)))

    # plots ----------------------------------------------------------------------------------
    if args.plots and per_rows:
        try:
            import matplotlib
            matplotlib.use("Agg")
            import matplotlib.pyplot as plt
        except Exception as e:  # noqa: BLE001
            p("\nplots skipped (matplotlib not importable: %s)" % e.__class__.__name__)
        else:
            os.makedirs(args.plots, exist_ok=True)
            stem = os.path.splitext(os.path.basename(args.probe))[0]
            m = models[best_axis]
            est = mag_estimate_rest(m, streams[best_axis], rests)
            fig, ax = plt.subplots(2, 1, figsize=(11, 8))
            ax[0].plot([(s[0] - t_ref) / 1000 for s in series_ts], [s[1] for s in series_ts], "k.", label="truth")
            ax[0].plot([(e[0] - t_ref) / 1000 for e in est], [e[1] for e in est], "r-", lw=0.8, label="%s anch-rest (in-sample table)" % best_axis)
            ax[0].set_xlabel("s"); ax[0].set_ylabel("deg"); ax[0].legend(); ax[0].set_title(stem)
            ax[1].plot([d for _, d, _ in all_tf], [fv[best_axis] for _, _, fv in all_tf], "b.", ms=3, label=best_axis)
            ax[1].plot(m.angles, m.b, "g-", label="table")
            ax[1].set_xlabel("truth deg"); ax[1].set_ylabel("uT"); ax[1].legend()
            path = os.path.join(args.plots, "%s-fit.png" % stem)
            fig.tight_layout(); fig.savefig(path); plt.close(fig)
            p("\nplot: %s" % path)
    return "\n".join(out), result


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("probe", help="PoseProbe JSONL")
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--truth", help="truth JSONL from hinge_truth.py")
    g.add_argument("--logcat", help="raw logcat to parse")
    ap.add_argument("--year", type=int, default=None)
    ap.add_argument("--align", choices=["ts", "wall"], default="ts")
    ap.add_argument("--plots", default="pose/testdata/plots", help="PNG dir ('' to disable)")
    args = ap.parse_args(argv)
    text, result = run(args)
    print(text)
    return result


if __name__ == "__main__":
    main()
