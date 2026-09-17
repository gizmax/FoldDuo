# Pose fixtures (Galaxy Z Fold 8, SM-F971B, Android 17 / One UI 9.0)

Recorded with the debug `PoseProbeActivity` (device side, `<stamp>.jsonl`) and
`tools/pose_state_poll.sh` (host side, `sysstate-<stamp>.jsonl`). Merge on `wallMs`
(both are the device clock).

| File | Content |
|---|---|
| `20260913-165318.jsonl` + `sysstate-20260913-165317.jsonl` | 145 s: ~90 s flat on table closed, then 3 open/close cycles (two slow, one fast). No manual labels. |
| `20260913-175448.jsonl` + `sysstate-20260913-170029.jsonl` | 141 s: closed on table → tent on table (label `Tent`, 40 s) → open flat screen up (`Table`) → closed cover down (`Flip`, label tapped before flipping) → open held upright like a book (`Stand`). |
| `20260915-174608.jsonl` + `hinge-20260915-174330.truth.jsonl` (+ `hinge-20260915-174330.logcat`, source of the truth) | 61 s hinge spike: phone flat on a table, the half **without** the IMU moved; 13 open/close transitions (8 slow 2–6 s, 5 fast 0.3–1.3 s, one partial 105→175°). High-rate rows: `mag_u`/`mag` 100 Hz, `gyro`/`accel` ~470 Hz. Truth: 24 `hinge_raw` + 169 `lid_hal` samples inside the recording. |
| `20260915-174330.jsonl` | Earlier partial recording of the same session (the Back gesture killed the probe mid-fold; the logcat/truth file covers it too). |

## Hinge-angle spike (2026-09-15): magnetometer / gyro vs logcat ground truth

`tools/hinge_spike.sh <serial>` records `hinge-<stamp>.logcat` (live `adb logcat -v threadtime`)
+ `hinge-<stamp>.jsonl` (probe with the FASTEST-rate `mag_u`/`mag`/`gyro`/`accel` rows), then writes
`hinge-<stamp>.truth.jsonl` (`tools/hinge_truth.py`) and `hinge-<stamp>.fit.txt` (`tools/hinge_fit.py`;
PNGs in `plots/` when matplotlib is installed). Ground truth = vendor HAL logcat lines: `hinge_angle
ts=<ns> value <step>/<raw>/<?>` at each step event and `lid_angle_fusion ts=<ns> value [state/angle/f3] …
([…] [accel A] [accel B] […])` during transitions; `ts` is CLOCK_BOOTTIME so it merges with the probe's
`sensorNs` directly (the `meta` row carries the elapsed↔wall offset). Parser tests:
`python3 -m unittest discover -s tools/tests`. `hinge-20260915-logcat-sample.logcat` is a filtered real
buffer (5 fold transitions, no probe file) for the parser; the smoke recording of the same afternoon is
not kept (no fold in it).

## Findings from the first session (2026-09-13)

- Public `TYPE_HINGE_ANGLE` on this firmware is **quantized to 0 / 90 / 180°** at HAL level
  (`dumpsys sensorservice` shows the same three values). 12 events in 145 s.
- Continuous sources exist but are `com.samsung.permission.SSENSOR` = `signature|privileged`:
  `Folding Angle` (65686), `lid_angle_fusion` (65695), second IMU `lsm6dsv_1 *-Sub` (65687–65690).
- Opening (slow): sysState CLOSED→**TENT** (transient, mid-swing) → hinge 90 → `FoldingFeature FLAT VERTICAL`
  appears on the *cover* window at x=624 → **panel swap** cover→inner (display 0 physical size changes) →
  sysState OPENED → hinge 180 ~2.5 s later. Fast open: swap first, hinge 90/180 after.
- Closing: hinge 90 → hinge 0 → FoldingFeature gone → sysState CLOSED → panel swap to cover, all within ~100 ms.
- The **panel swap** is the crisp, reliable event. Hinge events lag by up to seconds.
- On the inner window `FoldingFeature` = `FLAT VERTICAL [1224,0][1224,1848] sep=false`. Never `HALF_OPENED` in this session.

## Findings from the second session (poses held)

- **Tent is a held system state**: sysState TENT stayed for ~40 s while standing on the table; hinge step 90 arrived 0.6 s after. So Samsung's TENT is both the transient mid-swing state and the held tent.
- `FoldingFeature` is `FLAT` even in tent (on the cover window `FLAT HORIZONTAL [0,624][1972,624]`); it never reports `HALF_OPENED`. Use it only for bounds/orientation.
- Gravity signatures (m/s², mean over 3 s after each label; device axes):

  | Pose | panel | hinge step | gx | gy | gz |
  |---|---|---|---|---|---|
  | Tent (cover up, hinge on table, cover window landscape 876×555) | cover | 90 | **-9.2** | 0.1 | -3.4 |
  | Table (open flat, screen up) | inner | 180 | 0.6 | 0.0 | **9.8** |
  | Flip (closed, cover facing table) | cover | 0 | 1.4 | 0.9 | **-6.8** |
  | Stand (open, held upright like a book, tilted back) | inner | 180 | 0.1 | **7.9** | 5.8 |
  | Closed on table, cover up (session 1) | cover | 0 | 0.0 | 1.4 | 9.7 |
- Tent → open flat: OPENED and hinge 180 within 0.4 s; the panel swap is again the crisp event.

## Findings from the hinge spike (2026-09-15): the continuous angle is in the magnetometer

`tools/hinge_fit.py pose/testdata/20260915-174608.jsonl --truth pose/testdata/hinge-20260915-174330.truth.jsonl`
(prints the full report; JVM replay in `pose/src/test/.../HingeAngleEstimatorTest.kt`).

- **Uncalibrated magnetometer x axis (`bx`) is a monotone function of the hinge angle over the whole 0–180°**:
  spearman −0.989 pooled (−0.98…−1.00 per transition), `|B|` +0.988. The closure magnets are in the half without
  the IMU; their field at the sensor *grows* as the hinge opens: `bx` −197 µT at rest closed → −260 µT at rest open
  (`|B|` 205 → 262 µT), the vendor hard-iron estimate in `mag_u[3..5]` is zero. The old assumption in `hinge_fit.py`
  (field drops to the Earth field when open → threshold) was inverted on this device and used the magnet model on
  0 samples; removed. `by`/`bz` carry much less (rho 0.65 / 0.93, in-sample RMS 48° / 22°).
- **Table** (10° bin medians, PAV-monotone, 0/180 = rest values), `bx` in µT: 0°:−197.4, 5°:−203.0, 15°:−208.3,
  25°:−216.1, 35°:−223.6, 45°:−229.0, 55°:−230.7, 65°:−235.5, 75°:−238.2, 85°:−240.0, 95°:−242.2, 105°:−244.2,
  115°:−245.2, 125°:−247.0, 135°:−248.0, 145°:−249.8, 155°:−251.9, 165°:−254.5, 175°:−255.7, 180°:−260.4.
  Slope 0.74 µT/° at 10°, 0.36 at 45°, 0.20 at 90°, 0.14 at 135°, 0.36 at 170° — steep near closed, flat in the middle.
- **Leave-one-transition-out RMS** (fit on the other 12 transitions, scored on the held-out one, 203 truth samples):

  | variant | `bx` pooled | `bx` median/transition | `|B|` pooled |
  |---|---|---|---|
  | raw table | **8.6°** | 7.1° | 9.2° |
  | table + offset re-anchored at every step to the truth raw angle (upper bound) | 13.6° | 9.7° | 13.7° |
  | … at every step to the nominal step angle (0→17°, 90↑→40°, 90↓→140°, 180→173°, medians of the truth raw) | 17.2° | 10.3° | 17.1° |
  | … nominal, 0/180 steps only | 16.3° | 12.4° | 15.7° |
  | … nominal, 180 step only | 12.7° | 11.2° | 12.8° |
  | … nominal, 180 + closing-90 steps | 17.8° | 9.9° | 19.0° |
  | **table + offset re-anchored at rests** (0/180 step, then ≥ 1 s with σ(bx) < 1 µT → table endpoint) | **9.6°** | 8.0° | 10.1° |
  | gyro dead reckoning (+y, truth anchors) | 62.0° | 63.8° | – |
  | combined: gyro +y + `bx` rest-anchored, complementary τ = 30 ms | 10.4° | 6.8° | – |

  Per transition (raw `bx`): slow folds 3–13°, the 0.29 s snap-close 19.5° (truth is 9 samples at 80 ms during a
  490°/s move, so timing alone is worth ~10°). The Kotlin estimator (`HingeAngleEstimator`, rest anchors) replays
  to 9.3° pooled / 8.8° median.
- **Why step-event anchoring hurts**: the `hinge_angle` event's raw angle lags the lid stream and the magnetometer
  at the same HAL timestamp by 5–15° in slow folds and 60° in the fast one, and an anchor error at the steep
  closed end (0.7 µT/°) costs 3–5 µT = 20–30° at 90° where the table is flat. Anchoring must happen at rest, where
  the values repeat to ±0.6 µT (closed −197.2…−198.4, open −260.2…−260.9 across 9 rests) — which is also the
  per-device self-calibration signal (two endpoints; the shape between them comes from the default table).
- **Gyro is useless for this gesture**: the IMU half stayed still (gyro swing 2–28° of 170°, HAL accel A = probe
  accel, IMU-half share of accel motion 0.08–0.43). It only helps when the IMU half moves (hand-held).
- **Resolution**: σ(bx) at rest 0.31 µT (median of 13 still 1 s windows; the worst rest 0.9 µT) → 0.4° at 10°,
  1.5° at 90°, 0.9° at 170°. The estimator's output σ over a rest is 0.4°. Noise is not the limit; the table's
  device/geometry drift is.
- **Earth field (analytic, |E| = 50 µT, not compensated)**: the table absorbed the Earth field of that table and
  orientation. Rotating the phone about device x leaves `bx` unchanged; about y or z it changes by up to
  |E|·sin φ (71 µT at φ = 90°): φ = 10° → 8.7 µT → 12° error at 10°, 43° at 90°, 24° at 170°; φ = 90° → 96° / 346° /
  194° — the estimate is meaningless once the phone turns mid-fold. Plan: learn `E_world = R(q)·(B − M(step))`
  while a 0/180 step is stable > 1 s (q from `GAME_ROTATION_VECTOR` — `TYPE_ROTATION_VECTOR` uses this very
  magnetometer and is dominated by the 250 µT magnet; `M(step)` = the per-axis table vector at the rest angle),
  subtract `R(q)ᵀ·E_world` from every sample. Expected residual ≈ |E|·(attitude error): 2° → 1.7 µT → 2–5°,
  5° → 4.4 µT → 6–12°, plus the game-rotation yaw drift (~1°/min with this gyro bias) → re-learn at every rest.
  **Implemented 2026-09-15 as `pose/.../EarthField.kt` (`EarthFieldCompensator`) with one change**: the one-rest
  formula needs an Earth-free `M(step)`, which a single orientation cannot give (with `M` learned at the first rest
  the formula collapses to the estimator's own rest offset). `E_world` is instead solved from *pairs* of rests at
  the same step, `B_i − B_j = (R_iᵀ − R_jᵀ)·E`, least squares over the last 8 rests (< 5 min, yaw drift) with a small
  ridge, so with every rest at one orientation `E = 0` and the table absorbs that orientation as before. Unit-tested
  on synthetic rotations (`EarthFieldCompensatorTest`); still not validated on a real rotation recording. The rests
  also self-calibrate the table's endpoints per device (`HingeRestStore`, SharedPreferences `hinge_rests`), and the
  angle drives the Continuum morph (`MorphController.angleTilt`, UnfoldMorph.kt "angle mode").
- **Probe fix**: the Back gesture (edge swipe during the fold) finished the activity mid-recording
  (`wm_finish_activity … app-request`); `PoseProbeActivity` now swallows Back while recording (toast "Recording — use STOP").

