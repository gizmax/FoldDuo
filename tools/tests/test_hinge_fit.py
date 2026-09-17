"""Synthetic end-to-end check of tools/hinge_fit.py (stdlib only).

Builds a fake probe JSONL + threadtime logcat for one close + one open transition with
|B| = 45 + 250*exp(-angle/25) uT and the hinge rotating about the device -y gyro axis,
then asserts the fit recovers the axis and a small RMS.
"""
import argparse
import json
import math
import os
import sys
import tempfile
import time
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import hinge_fit  # noqa: E402


def b_of(angle):
    return 45.0 + 250.0 * math.exp(-angle / 25.0)


def build(tmp):
    base_ns = 500_000_000_000
    wall0 = 1_789_000_000_000
    off_ms = wall0 - base_ns / 1e6
    probe, logc = [], []
    probe.append({"type": "meta", "elapsedNs": base_ns, "elapsedWallMs": wall0, "elapsedToWallOffsetMs": off_ms,
                  "tMs": 0, "wallMs": wall0})

    def wall_str(ms):
        t = time.localtime(ms / 1000.0)
        return "%02d-%02d %02d:%02d:%02d.%03d" % (t.tm_mon, t.tm_mday, t.tm_hour, t.tm_min, t.tm_sec, int(ms % 1000))

    # angle profile: 180 hold 1 s, close over 2 s to 0, hold 4 s, open over 2 s, hold 1 s
    def angle_at(t_ms):
        if t_ms < 1000:
            return 180.0
        if t_ms < 3000:
            return 180.0 * (1 - (t_ms - 1000) / 2000.0)
        if t_ms < 7000:
            return 0.0
        if t_ms < 9000:
            return 180.0 * (t_ms - 7000) / 2000.0
        return 180.0

    dt = 5.0  # ms, 200 Hz
    t = 0.0
    prev = angle_at(0)
    step_prev = 180
    while t <= 10000:
        ns = base_ns + int(t * 1e6)
        wall = wall0 + t
        a = angle_at(t)
        w = math.radians(a - prev) / (dt / 1000.0)  # rad/s about the hinge
        prev = a
        # hinge is -y in device frame; add small bias on x
        probe.append({"type": "gyro", "sensorNs": ns, "wallMs": wall, "v": [0.001, -w, 0.0]})
        probe.append({"type": "accel", "sensorNs": ns, "wallMs": wall, "v": [0.0, 0.0, 9.81]})
        if int(t) % 10 == 0:
            B = b_of(a)
            probe.append({"type": "mag_u", "sensorNs": ns, "wallMs": wall, "v": [B * 0.6, B * 0.8, 0.0, 1.0, 2.0, 3.0]})
            probe.append({"type": "mag", "sensorNs": ns, "wallMs": wall, "v": [B * 0.6, B * 0.8, 0.0]})
        step = 0 if a < 45 else 90 if a < 135 else 180
        if step != step_prev:
            probe.append({"type": "hinge", "deg": float(step), "acc": 3, "sensorNs": ns, "wallMs": wall})
            logc.append("%s  1963  3959 I sensors-hal: handle_sns_client_event:50, hinge_angle ts=%d ns value %3d/%3d/0"
                        % (wall_str(wall + 3), ns, step, round(a)))
            step_prev = step
        if int(t) % 100 == 0 and (1000 <= t <= 3000 or 7000 <= t <= 9000):
            # HAL accel A = the IMU half (it rotates, matching the probe's accel only at 180),
            # accel B = the other half lying still; the probe accel is kept simple.
            logc.append("%s  1963  3959 I sensors-hal: handle_sns_client_event:197, [0]lid_angle_fusion ts=%d ns value "
                        "[%d/%3d/3] [%3d/3] [ -1/-1] [0/0/0] ([0/0/1/1] [%.3f/0.000/%.3f] [0.000/0.000/9.810] [5])"
                        % (wall_str(wall + 3), ns, step // 90, round(a), round(a),
                           9.81 * math.sin(math.radians(180 - a)), 9.81 * math.cos(math.radians(180 - a))))
        t += dt
    pp = os.path.join(tmp, "probe.jsonl")
    lp = os.path.join(tmp, "probe.logcat")
    with open(pp, "w") as f:
        for r in probe:
            f.write(json.dumps(r) + "\n")
    with open(lp, "w") as f:
        f.write("\n".join(logc) + "\n")
    return pp, lp


class NumericsTest(unittest.TestCase):
    def test_pav_increasing(self):
        self.assertEqual(hinge_fit.pav([1, 3, 2, 4]), [1, 2.5, 2.5, 4])

    def test_pav_decreasing(self):
        self.assertEqual(hinge_fit.pav([4, 2, 3, 1], increasing=False), [4, 2.5, 2.5, 1])

    def test_interp_clamps(self):
        self.assertEqual(hinge_fit.interp(-1, [0, 10], [0, 100]), 0)
        self.assertEqual(hinge_fit.interp(5, [0, 10], [0, 100]), 50)
        self.assertEqual(hinge_fit.interp(99, [0, 10], [0, 100]), 100)

    def test_mag_model_inverts_decreasing_curve(self):
        pairs = [(a, b_of(a)) for a in range(0, 180, 3)]
        m = hinge_fit.MagModel(pairs)
        self.assertFalse(m.increasing)
        self.assertTrue(m.ok)
        self.assertLess(abs(m.angle(b_of(30)) - 30), 6)

    def test_mag_model_endpoints_and_offset(self):
        pairs = [(a, b_of(a)) for a in range(3, 178, 3)]
        m = hinge_fit.MagModel(pairs, endpoints={0: b_of(0), 180: b_of(180)})
        self.assertEqual(m.angles[0], 0.0)
        self.assertEqual(m.angles[-1], 180.0)
        self.assertAlmostEqual(m.b_at(0), b_of(0))
        self.assertAlmostEqual(m.angle(b_of(60) + 5.0, offset=5.0), m.angle(b_of(60)))
        self.assertAlmostEqual(m.offset_for(b_of(60) + 5.0, m.angle(b_of(60))), 5.0, places=6)
        self.assertGreater(m.slope_at(10), m.slope_at(150))

    def test_transitions_split_monotone_runs(self):
        # 180 -> 0 -> 180 -> 0 as one dense cluster (no 2.5 s gaps) must give three transitions
        series, t = [], 0.0
        for leg, (a0, a1) in enumerate(((180, 0), (0, 180), (180, 0))):
            for i in range(0, 19):
                series.append((t, a0 + (a1 - a0) * i / 18.0 + (2.0 if i % 2 else -2.0), "lid_hal", {"tsNs": 1}))
                t += 100.0
        trs = hinge_fit.transitions(series)
        self.assertEqual([tr["kind"] for tr in trs], ["close", "open", "close"])
        self.assertTrue(all(tr["span"][1] - tr["span"][0] >= 170 for tr in trs))


class EndToEndTest(unittest.TestCase):
    def test_synthetic_transitions(self):
        with tempfile.TemporaryDirectory() as tmp:
            probe, logc = build(tmp)
            args = argparse.Namespace(probe=probe, truth=None, logcat=logc, year=time.localtime().tm_year, align="ts", plots="")
            text, res = hinge_fit.run(args)
        self.assertIn("transitions (monotone runs >= 60 deg): 2", text)
        self.assertIn("#0  close", text)
        self.assertIn("#1  open", text)
        self.assertEqual([r["axis"] for r in res["gyro"]["per_transition"]], [1, 1])
        self.assertEqual([r["sign"] for r in res["gyro"]["per_transition"]], [-1, -1])
        self.assertEqual(res["gyro"]["imu_moving"], 2)
        for r in res["gyro"]["per_transition"]:
            self.assertLess(r["rms"], 3.0, text)
        # the synthetic field decreases with the angle: both |B| and bx invert it
        self.assertLess(res["mag"]["|B|"]["rho"], -0.95)
        self.assertLess(res["mag"]["bx"]["rms_in"], 8.0, text)
        self.assertEqual(res["mag"]["best_axis"], "bx")
        # rest endpoints come from the two 1 s rests (closed 0 deg, open 180 deg)
        self.assertEqual([r[0] for r in res["rests"]], ["closed", "open"])
        self.assertAlmostEqual(res["endpoints"]["bx"][0], 0.6 * b_of(0), delta=0.5)
        self.assertAlmostEqual(res["endpoints"]["bx"][180], 0.6 * b_of(180), delta=0.5)
        pooled = res["loo"]["pooled"]
        self.assertEqual(sorted(res["loo"].keys() - {"pooled", "cols"}), [0, 1])
        self.assertLess(pooled["raw bx"], 8.0, text)
        self.assertLess(pooled["raw |B|"], 8.0, text)
        self.assertLess(pooled["anch-rest bx"], 8.0, text)
        self.assertLess(pooled["anch-truth bx"], 8.0, text)
        self.assertLess(pooled["gyro"], 3.0, text)
        self.assertLess(pooled["combined bx"], 8.0, text)
        # nominal step anchors are deliberately poor here (the synthetic 90 step fires at raw 135/45,
        # so the "nominal" learned from the other transition is 90 deg off): bounded, not good
        self.assertLess(pooled["anch-nom bx"], 70.0, text)
        self.assertGreater(pooled["anch-nom bx"], pooled["raw bx"], text)
        self.assertIn("Earth-field robustness", text)
        self.assertIn("resolution", text)


if __name__ == "__main__":
    unittest.main()
