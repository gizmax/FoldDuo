"""Run: python3 -m unittest discover -s tools/tests   (stdlib only)."""
import os
import sys
import time
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import hinge_truth  # noqa: E402

THREADTIME_HINGE = ("09-15 17:02:57.730  1963  3959 I sensors-hal: handle_sns_client_event:50, "
                    "hinge_angle ts=266973764999935 ns value  90/148/0")
THREADTIME_LID = ("09-15 17:03:06.288  1963  3959 I sensors-hal: handle_sns_client_event:197, [0]lid_angle_fusion "
                  "ts=266982322585716 ns value [1/ 17/3] [ 17/3] [ -1/-1] [    0/    0/    0] "
                  "([0/0/1/1] [6.595/7.346/1.804] [-6.059/7.954/-2.240] [11])")
THREADTIME_FDSP = "09-15 17:02:07.744  2503  2503 I FlexibleDeviceStateProvider: lid_angle_fusion  Wakeup : [0.0, 5.0, 5.0]"
THREADTIME_SUBMIT = ("09-15 17:02:07.874  1963  3959 I sensors-hal: handle_sns_client_event:223, "
                     "[0]lid_angle_fusion submit_sensors_hal_event 1/ 11/3  11/3")
TIME_HINGE = "09-15 17:02:57.730 I/sensors-hal( 1963): handle_sns_client_event:50, hinge_angle ts=266973764999935 ns value  90/148/0"
EPOCH_HINGE = ("         1789484577.730 I/sensors-hal( 1963): handle_sns_client_event:50, "
               "hinge_angle ts=266973764999935 ns value  90/148/0")
EPOCH_LID = ("         1789484575.608 I/sensors-hal( 1963): handle_sns_client_event:197, [0]lid_angle_fusion "
             "ts=266971642579050 ns value [3/179/3] [179/3] [ -1/-1] [    0/    0/    0] "
             "([0/0/1/1] [0.512/0.053/9.720] [0.445/-0.043/9.772] [33])")
UNRELATED = "09-15 17:01:55.450  1963  1963 I sensors-hal: batch:383, android.sensor.hinge_angle/362, period=1000000, max_latency=0 request completed"


class ParseLineTest(unittest.TestCase):
    def test_hinge_threadtime(self):
        r = hinge_truth.parse_line(THREADTIME_HINGE, year=2026)
        self.assertEqual(r["type"], "hinge_raw")
        self.assertEqual((r["step"], r["raw"], r["f3"]), (90, 148, 0))
        self.assertEqual(r["tsNs"], 266973764999935)
        expected = int(time.mktime((2026, 9, 15, 17, 2, 57, 0, 0, -1))) * 1000 + 730
        self.assertEqual(r["wallMs"], expected)

    def test_hinge_time_format_same_as_threadtime(self):
        a = hinge_truth.parse_line(THREADTIME_HINGE, year=2026)
        b = hinge_truth.parse_line(TIME_HINGE, year=2026)
        self.assertEqual(a, b)

    def test_hinge_epoch_format(self):
        r = hinge_truth.parse_line(EPOCH_HINGE)
        self.assertEqual(r["wallMs"], 1789484577730)
        self.assertEqual(r["raw"], 148)

    def test_lid_hal(self):
        r = hinge_truth.parse_line(THREADTIME_LID, year=2026)
        self.assertEqual(r["type"], "lid")
        self.assertEqual(r["src"], "hal")
        self.assertEqual(r["tsNs"], 266982322585716)
        self.assertEqual((r["state"], r["angle"], r["f3"]), (1, 17, 3))
        self.assertEqual(r["fields"][0], [1, 17, 3])
        self.assertEqual(r["fields"][1], [17, 3])
        self.assertEqual(r["fields"][2], [-1, -1])
        self.assertEqual(r["accelA"], [6.595, 7.346, 1.804])
        self.assertEqual(r["accelB"], [-6.059, 7.954, -2.240])
        self.assertEqual(r["fields"][-1], [11])

    def test_lid_epoch(self):
        r = hinge_truth.parse_line(EPOCH_LID)
        self.assertEqual((r["state"], r["angle"]), (3, 179))
        self.assertEqual(r["wallMs"], 1789484575608)

    def test_lid_submit_without_ts(self):
        r = hinge_truth.parse_line(THREADTIME_SUBMIT, year=2026)
        self.assertEqual(r["src"], "submit")
        self.assertNotIn("tsNs", r)
        self.assertEqual((r["state"], r["angle"], r["f3"]), (1, 11, 3))

    def test_fdsp(self):
        r = hinge_truth.parse_line(THREADTIME_FDSP, year=2026)
        self.assertEqual(r["src"], "fdsp")
        self.assertEqual(r["v"], [0.0, 5.0, 5.0])
        self.assertEqual(r["angle"], 5.0)
        self.assertNotIn("tsNs", r)

    def test_unrelated_lines_ignored(self):
        self.assertIsNone(hinge_truth.parse_line(UNRELATED, year=2026))
        self.assertIsNone(hinge_truth.parse_line("--------- beginning of main", year=2026))
        self.assertIsNone(hinge_truth.parse_line("", year=2026))


class FieldReportTest(unittest.TestCase):
    def _lid(self, ts, state, angle, f3):
        return ("09-15 17:00:00.000  1963  3959 I sensors-hal: handle_sns_client_event:197, [0]lid_angle_fusion "
                "ts=%d ns value [%d/%3d/%d] [%3d/%d] [ -1/-1] [0/0/0] ([0/0/1/1] [0.1/0.2/9.8] [0.1/0.2/9.8] [5])"
                % (ts, state, angle, f3, angle, f3))

    def _hinge(self, ts, step, raw):
        return ("09-15 17:00:00.000  1963  3959 I sensors-hal: handle_sns_client_event:50, "
                "hinge_angle ts=%d ns value %3d/%3d/0" % (ts, step, raw))

    def test_angle_field_is_the_monotone_one(self):
        base = 1_000_000_000_000
        lines = []
        # angle rises 10..170 while f3 wobbles and state is a coarse bucket
        for i, ang in enumerate(range(10, 180, 20)):
            ts = base + i * 200_000_000
            lines.append(self._lid(ts, min(3, ang // 60), ang, (i * 7) % 3 + 1))
            lines.append(self._hinge(ts + 50_000_000, 0 if ang < 45 else 90 if ang < 135 else 180, ang + 2))
        rows = hinge_truth.parse_lines(lines, year=2026)
        rep = hinge_truth.field_report(rows)
        self.assertEqual(rep["best"], 1)
        self.assertGreater(rep[1]["rho"], 0.99)
        self.assertEqual(rep["pairs"], 9)
        self.assertFalse(rep[0]["degrees_like"])  # coarse 0..3 bucket, monotone but not degrees
        self.assertTrue(rep[1]["degrees_like"])
        text = hinge_truth.format_report(rep)
        self.assertIn("field[1]", text)
        self.assertIn("looks like degrees", text)
        self.assertIn("field[0]", text)
        self.assertIn("not degrees", text)

    def test_lid_fields_interpolated_at_hinge_ts(self):
        base = 1_000_000_000_000
        lines = [self._lid(base, 2, 100, 3), self._lid(base + 100_000_000, 2, 120, 3),
                 self._hinge(base + 25_000_000, 90, 105)]
        rows = hinge_truth.parse_lines(lines, year=2026)
        rep = hinge_truth.field_report(rows)
        self.assertEqual(rep["pairs"], 1)
        raw, f = rep["pairs_list"][0]
        self.assertEqual(raw, 105)
        self.assertAlmostEqual(f[1], 105.0)

    def test_report_with_no_pairs(self):
        rows = hinge_truth.parse_lines([THREADTIME_HINGE], year=2026)
        rep = hinge_truth.field_report(rows)
        self.assertIsNone(rep["best"])
        self.assertIn("not enough", hinge_truth.format_report(rep))


class ParseFileTest(unittest.TestCase):
    def test_mixed_formats_in_one_file(self):
        import tempfile
        with tempfile.NamedTemporaryFile("w", suffix=".logcat", delete=False) as f:
            f.write("\n".join([UNRELATED, THREADTIME_HINGE, THREADTIME_LID, THREADTIME_FDSP, THREADTIME_SUBMIT, EPOCH_LID]) + "\n")
            path = f.name
        try:
            rows = hinge_truth.parse_file(path, year=2026)
        finally:
            os.unlink(path)
        self.assertEqual([r["type"] for r in rows], ["hinge_raw", "lid", "lid", "lid", "lid"])
        self.assertEqual([r.get("src") for r in rows], [None, "hal", "fdsp", "submit", "hal"])


if __name__ == "__main__":
    unittest.main()
