package cz.pflanzer.foldduo.pose

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Replays `20260915-174608.jsonl` (Fold 8 on a table, 13 open/close transitions, mag_u at
 * 100 Hz) against the vendor HAL truth (`hinge-20260915-174330.truth.jsonl`, lid-angle
 * samples + raw angle at each step event; CLOCK_BOOTTIME ns on both sides) through
 * [HingeAngleEstimator], plus synthetic checks of the rest re-anchoring and the resolution.
 */
class HingeAngleEstimatorTest {
    private class Mag(val t: Long, val x: Float, val y: Float, val z: Float)
    private class Step(val t: Long, val deg: Float)
    private class Truth(val t: Long, val deg: Float)
    private class Transition(val samples: List<Truth>, val open: Boolean) {
        val t0 get() = samples.first().t
        val t1 get() = samples.last().t
        val durMs get() = (t1 - t0) / 1_000_000
    }

    private val mags: List<Mag>
    private val steps: List<Step>
    private val truth: List<Truth>
    private val transitions: List<Transition>

    init {
        val m = ArrayList<Mag>(); val s = ArrayList<Step>()
        File("testdata", "20260915-174608.jsonl").forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            val o = JSONObject(line)
            when (o.getString("type")) {
                "mag_u" -> { val v = o.getJSONArray("v"); m.add(Mag(o.getLong("sensorNs"), v.getDouble(0).toFloat(), v.getDouble(1).toFloat(), v.getDouble(2).toFloat())) }
                "hinge" -> s.add(Step(o.getLong("sensorNs"), o.getDouble("deg").toFloat()))
            }
        }
        mags = m.sortedBy { it.t }
        val w0 = mags.first().t - 1_000_000_000L; val w1 = mags.last().t + 1_000_000_000L
        steps = s.filter { it.t in w0..w1 }.sortedBy { it.t }
        val tr = ArrayList<Truth>()
        File("testdata", "hinge-20260915-174330.truth.jsonl").forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            val o = JSONObject(line)
            if (!o.has("tsNs")) return@forEachLine
            val t = o.getLong("tsNs")
            if (t !in w0..w1) return@forEachLine
            when (o.getString("type")) {
                "hinge_raw" -> tr.add(Truth(t, o.getDouble("raw").toFloat()))
                "lid" -> if (o.optString("src") == "hal") tr.add(Truth(t, o.getDouble("angle").toFloat()))
            }
        }
        truth = tr.sortedBy { it.t }
        transitions = segment(truth)
    }

    /** Monotone runs of the truth angle spanning >= 60 deg (same rule as tools/hinge_fit.py). */
    private fun segment(series: List<Truth>, gapNs: Long = 2_500_000_000L, minSpan: Float = 60f, hyst: Float = 15f): List<Transition> {
        val runs = ArrayList<List<Truth>>()
        var cur = ArrayList<Truth>(); var ext = 0f; var extI = 0; var dir = 0
        for (s in series) {
            if (cur.isNotEmpty() && s.t - cur.last().t > gapNs) { runs.add(cur); cur = ArrayList(); dir = 0 }
            if (cur.isEmpty()) { cur.add(s); ext = s.deg; extI = 0; continue }
            val a = s.deg
            if (dir == 0) {
                if (abs(a - cur[0].deg) >= hyst) { dir = if (a > cur[0].deg) 1 else -1; ext = a; extI = cur.size }
                cur.add(s); continue
            }
            if ((a - ext) * dir >= 0) { ext = a; extI = cur.size; cur.add(s) }
            else if ((ext - a) * dir > hyst) {
                runs.add(ArrayList(cur.subList(0, extI + 1)))
                val tail = ArrayList(cur.subList(extI, cur.size)); tail.add(s)
                cur = tail; dir = -dir; ext = a; extI = cur.size - 1
            } else cur.add(s)
        }
        if (cur.isNotEmpty()) runs.add(cur)
        return runs.filter { r -> r.size >= 3 && r.maxOf { it.deg } - r.minOf { it.deg } >= minSpan }
            .map { r -> Transition(r, r.last().deg > r.first().deg) }
    }

    private fun bxAt(t: Long): Float? {
        var lo = 0; var hi = mags.size
        while (lo < hi) { val mid = (lo + hi) / 2; if (mags[mid].t < t) lo = mid + 1 else hi = mid }
        if (lo == 0 || lo >= mags.size) return null
        val a = mags[lo - 1]; val b = mags[lo]
        if (b.t - a.t > 50_000_000L) return null
        val f = (t - a.t).toFloat() / (b.t - a.t)
        return a.x + (b.x - a.x) * f
    }

    /** Rests after a 0/180 step (>= 1 s with bx sigma < 1 uT): (confirmedNs, endNs, angle, median bx). */
    private data class Rest(val t0: Long, val t1: Long, val angle: Float, val bx: Float)

    private fun rests(): List<Rest> {
        val out = ArrayList<Rest>()
        for ((i, st) in steps.withIndex()) {
            val angle = when { st.deg <= 45f -> 0f; st.deg >= 135f -> 180f; else -> continue }
            val next = if (i + 1 < steps.size) steps[i + 1].t else mags.last().t
            var t = st.t + 300_000_000L
            while (t + 1_000_000_000L <= next) {
                val win = mags.filter { it.t in t until t + 1_000_000_000L }.map { it.x }
                if (win.size >= 20 && sigma(win) < 1f) {
                    out.add(Rest(t + 1_000_000_000L, next, angle, HingeCalibration.median(mags.filter { it.t in t until next }.map { it.x })))
                    break
                }
                t += 250_000_000L
            }
        }
        return out
    }

    private fun sigma(xs: List<Float>): Float {
        val m = xs.average(); return sqrt(xs.sumOf { (it - m) * (it - m) } / xs.size).toFloat()
    }

    private fun fitExcluding(k: Int?): HingeCalibration {
        val pairs = ArrayList<Pair<Float, Float>>()
        for ((j, tr) in transitions.withIndex()) {
            if (j == k) continue
            for (s in tr.samples) bxAt(s.t)?.let { pairs.add(s.deg to it) }
        }
        val rs = rests().filter { r -> k == null || r.t0 !in transitions[k].t0..transitions[k].t1 }
        val closed = rs.filter { it.angle == 0f }.map { it.bx }.takeIf { it.isNotEmpty() }?.let { HingeCalibration.median(it) }
        val open = rs.filter { it.angle == 180f }.map { it.bx }.takeIf { it.isNotEmpty() }?.let { HingeCalibration.median(it) }
        return HingeCalibration.fit(pairs, closed, open)
    }

    /** Streams the whole recording (mag + step events in timestamp order); returns (t, angle) after each mag sample. */
    private fun replay(cal: HingeCalibration): List<Pair<Long, Float>> {
        val est = HingeAngleEstimator(cal)
        val out = ArrayList<Pair<Long, Float>>(mags.size)
        var si = 0
        for (m in mags) {
            while (si < steps.size && steps[si].t <= m.t) { est.onHingeStep(steps[si].t, steps[si].deg); si++ }
            out.add(m.t to est.onMagUncalibrated(m.t, m.x, m.y, m.z).angleDeg)
        }
        return out
    }

    private fun at(series: List<Pair<Long, Float>>, t: Long): Float? {
        var lo = 0; var hi = series.size
        while (lo < hi) { val mid = (lo + hi) / 2; if (series[mid].first < t) lo = mid + 1 else hi = mid }
        if (lo == 0 || lo >= series.size) return null
        val (ta, va) = series[lo - 1]; val (tb, vb) = series[lo]
        if (tb - ta > 200_000_000L) return null
        return va + (vb - va) * (t - ta).toFloat() / (tb - ta)
    }

    private fun rms(e: List<Float>) = sqrt(e.sumOf { (it * it).toDouble() } / e.size).toFloat()

    @Test fun fixture_hasThirteenTransitionsAndRests() {
        assertEquals(13, transitions.size)
        val rs = rests()
        assertTrue("rests ${rs.size}", rs.size >= 8)
        // rest values are the self-calibration signal: closed ~-197..-199, open ~-260..-261 uT
        for (r in rs) {
            if (r.angle == 0f) assertTrue("closed rest ${r.bx}", r.bx in -200f..-196f) else assertTrue("open rest ${r.bx}", r.bx in -262f..-259f)
        }
    }

    @Test fun fixture_leaveOneTransitionOut_rmsWithin15deg() {
        val pooled = ArrayList<Float>()
        val per = ArrayList<Float>()
        val sb = StringBuilder("LOO (HingeAngleEstimator, bx, rest anchors):\n")
        for ((k, tr) in transitions.withIndex()) {
            val series = replay(fitExcluding(k))
            val errs = tr.samples.mapNotNull { s -> at(series, s.t)?.let { it - s.deg } }
            assertTrue("no estimates for transition $k", errs.size >= tr.samples.size - 1)
            val r = rms(errs); per.add(r); pooled.addAll(errs)
            sb.append(String.format("  #%-2d %-5s %5.2f s  n=%2d  RMS %5.1f deg\n", k, if (tr.open) "open" else "close", tr.durMs / 1000.0, errs.size, r))
        }
        val all = rms(pooled)
        sb.append(String.format("  pooled RMS %.1f deg over %d samples; median per transition %.1f deg", all, pooled.size, per.sorted()[per.size / 2]))
        println(sb)
        assertTrue("pooled LOO RMS $all", all <= 15f)
        for ((k, tr) in transitions.withIndex()) if (tr.durMs >= 1000) assertTrue("slow transition $k RMS ${per[k]}", per[k] <= 20f)
    }

    @Test fun fixture_defaultTable_tracksMonotonicallyWithinTransition() {
        val series = replay(HingeCalibration.FOLD8_BX)
        var worstRho = 1f; var worstReversal = 0f
        for (tr in transitions) {
            // full slow folds only: #8 is a 70 deg partial close with 7 samples where the truth stream itself is not monotone
            if (tr.durMs < 1000 || tr.samples.maxOf { it.deg } - tr.samples.minOf { it.deg } < 120f) continue
            val pts = tr.samples.mapNotNull { s -> at(series, s.t)?.let { s.deg to it } }
            val rho = HingeCalibration.spearman(pts.map { it.first }, pts.map { it.second })
            worstRho = minOf(worstRho, rho)
            val dir = if (tr.open) 1f else -1f
            for (i in 1 until pts.size) worstReversal = maxOf(worstReversal, (pts[i - 1].second - pts[i].second) * dir)
        }
        println("default table: worst spearman(est, truth) $worstRho, worst reversal against the fold direction $worstReversal deg")
        assertTrue("spearman $worstRho", worstRho >= 0.95f)
        assertTrue("reversal $worstReversal", worstReversal <= 15f)
        // in-sample with the shipped constants the whole recording stays well inside the LOO bound
        val errs = transitions.flatMap { tr -> tr.samples.mapNotNull { s -> at(series, s.t)?.let { it - s.deg } } }
        assertTrue("in-sample RMS ${rms(errs)}", rms(errs) <= 12f)
    }

    @Test fun reanchor_atRest_shiftsOffsetSoTableRunsThroughRestValue() {
        val cal = HingeCalibration.FOLD8_BX
        val est = HingeAngleEstimator(cal)
        val shift = 6f // uT of extra additive field (e.g. the phone was turned in the Earth field)
        var t = 0L
        fun feed(angle: Float, ms: Int, noise: Float = 0f) {
            repeat(ms / 10) { i -> t += 10_000_000L; est.onMagUncalibrated(t, cal.valueAt(angle) + shift + (if (i % 2 == 0) noise else -noise), -30f, -40f) }
        }
        feed(90f, 200)
        val before = est.angleDeg
        assertTrue("shifted reading must be off before anchoring: $before", abs(before - 90f) > 15f)
        assertEquals(0f, est.offsetUt, 0f)
        assertEquals(0, est.anchors)
        // a 180 step followed by 1.3 s of still reading anchors; the table now passes through the rest value at 180
        est.onHingeStep(t, 180f)
        feed(180f, 1300)
        assertEquals(1, est.anchors)
        assertEquals(shift, est.offsetUt, 0.05f)
        assertEquals(180f, est.angleDeg, 0.5f)
        assertTrue("confidence after anchor ${est.confidence}", est.confidence >= 0.99f)
        feed(90f, 200)
        assertEquals(90f, est.angleDeg, 1f)
        // the anchor is applied once per rest, not on every sample
        feed(180f, 500)
        assertEquals(1, est.anchors)
        // a noisy "rest" (sigma above 1 uT) never anchors
        est.onHingeStep(t, 0f)
        feed(0f, 1500, noise = 3f)
        assertEquals(1, est.anchors)
        // a 90 step cancels the pending rest candidate
        est.onHingeStep(t, 0f); est.onHingeStep(t + 1, 90f)
        feed(0f, 1500)
        assertEquals(1, est.anchors)
        // a quiet closed rest anchors at 0
        est.onHingeStep(t, 0f)
        feed(0f, 1500)
        assertEquals(2, est.anchors)
        assertEquals(0f, est.angleDeg, 0.5f)
    }

    @Test fun gyro_isOptional_andBlendsWhenPresent() {
        val cal = HingeCalibration.FOLD8_BX
        val est = HingeAngleEstimator(cal)
        var t = 0L
        repeat(20) { t += 10_000_000L; est.onMagUncalibrated(t, cal.valueAt(120f), 0f, 0f) }
        assertEquals(120f, est.angleDeg, 0.5f)
        // gyro says the IMU half is opening at 100 deg/s while the magnetometer still reads 120: the estimate leads the magnetometer
        repeat(10) { t += 10_000_000L; est.onGyroHingeRate(t - 5_000_000L, Math.toRadians(100.0).toFloat()); est.onMagUncalibrated(t, cal.valueAt(120f), 0f, 0f) }
        assertTrue("gyro-led estimate ${est.angleDeg}", est.angleDeg > 121f && est.angleDeg < 135f)
        // once the gyro stops (no samples for > 100 ms) the output is the magnetometer again
        repeat(20) { t += 10_000_000L; est.onMagUncalibrated(t, cal.valueAt(120f), 0f, 0f) }
        assertEquals(120f, est.angleDeg, 0.5f)
    }

    @Test fun resolution_restNoiseInDegrees() {
        val cal = HingeCalibration.FOLD8_BX
        // table slope: steep near closed, flat in the middle
        assertTrue("slope@10 ${cal.slopeAt(10f)}", cal.slopeAt(10f) in 0.5f..1f)
        assertTrue("slope@90 ${cal.slopeAt(90f)}", cal.slopeAt(90f) in 0.1f..0.3f)
        // noise measured on the fixture's rests -> degrees through the table slope
        val series = replay(cal)
        var worstClosed = 0f; var worstOpen = 0f; var sigmaUt = 0f
        for (r in rests()) {
            val win = mags.filter { it.t in r.t0 - 1_000_000_000L until r.t0 }.map { it.x }
            sigmaUt = maxOf(sigmaUt, sigma(win))
            val angles = series.filter { it.first in r.t0 - 1_000_000_000L until r.t0 }.map { it.second }
            if (r.angle == 0f) worstClosed = maxOf(worstClosed, sigma(angles)) else worstOpen = maxOf(worstOpen, sigma(angles))
        }
        println("rest noise: worst bx sigma $sigmaUt uT -> estimator sigma closed $worstClosed deg, open $worstOpen deg (table slope @10 ${cal.slopeAt(10f)}, @170 ${cal.slopeAt(170f)} uT/deg)")
        assertTrue("bx sigma $sigmaUt", sigmaUt < 1f)
        assertTrue("closed sigma $worstClosed", worstClosed <= 2f)
        assertTrue("open sigma $worstOpen", worstOpen <= 4f)
    }

    @Test fun confidence_dropsOutsideTableAndBeforeAnchor() {
        val cal = HingeCalibration.FOLD8_BX
        val est = HingeAngleEstimator(cal)
        assertEquals(0f, est.confidence, 0f)
        est.onMagUncalibrated(1_000_000L, cal.valueAt(90f), 0f, 0f)
        assertEquals(0.6f, est.confidence, 0.01f) // in range, not yet anchored
        // bx is decreasing with the angle: below minValue is beyond fully open, above maxValue beyond closed
        est.onMagUncalibrated(2_000_000L, cal.minValue - 20f, 0f, 0f)
        assertTrue("outside range ${est.confidence}", est.confidence <= 0.2f)
        assertEquals(180f, est.angleDeg, 0f) // clamped at the table end
        est.onMagUncalibrated(3_000_000L, cal.maxValue + 20f, 0f, 0f)
        assertTrue("outside range ${est.confidence}", est.confidence <= 0.2f)
        assertEquals(0f, est.angleDeg, 0f)
        est.onMagUncalibrated(4_000_000L, cal.valueAt(45f), 0f, 0f)
        assertEquals(0.6f, est.confidence, 0.01f)
    }

    @Test fun restCallback_reportsThreeAxisMediansAndRescaledTableKeepsTheShape() {
        val cal = HingeCalibration.FOLD8_BX
        val est = HingeAngleEstimator(cal)
        val rests = mutableListOf<HingeAngleEstimator.Rest>()
        est.onRest = { rests += it }
        var t = 0L
        fun feed(angle: Float, ms: Int, shift: Float = 0f) {
            repeat(ms / 10) { t += 10_000_000L; est.onMagUncalibrated(t, cal.valueAt(angle) + shift, -30f + shift, -40f) }
        }
        // closed rest on this device reads 3 uT above the shipped table, open 5 uT below
        est.onHingeStep(t, 0f); feed(0f, 1500, shift = 3f)
        assertEquals(1, rests.size)
        assertEquals(0f, rests[0].angleDeg, 0f)
        assertEquals(cal.valueAt(0f) + 3f, rests[0].x, 0.01f)
        assertEquals(-27f, rests[0].y, 0.01f)
        assertEquals(-40f, rests[0].z, 0.01f)
        assertEquals(rests[0].x, rests[0].feature, 0f)
        assertTrue(rests[0].timestampNs in 1_300_000_000L..t) // the rest settles 300 ms after the step and needs 1 s still
        est.onHingeStep(t, 180f); feed(180f, 1500, shift = -5f)
        assertEquals(2, rests.size)
        assertEquals(180f, rests[1].angleDeg, 0f)
        // the per-device table: endpoints at the rests, the shape between them the default's
        val device = cal.rescaledTo(rests[0].feature, rests[1].feature)
        assertEquals(cal.valueAt(0f) + 3f, device.valueAt(0f), 0.01f)
        assertEquals(cal.valueAt(180f) - 5f, device.valueAt(180f), 0.01f)
        val gain = (device.valueAt(180f) - device.valueAt(0f)) / (cal.valueAt(180f) - cal.valueAt(0f))
        for (a in 10..170 step 20) assertEquals(device.valueAt(0f) + (cal.valueAt(a.toFloat()) - cal.valueAt(0f)) * gain, device.valueAt(a.toFloat()), 0.01f)
        for (a in 0..180 step 10) assertEquals(a.toFloat(), device.angleOf(device.valueAt(a.toFloat())), 0.01f)
        // swapping the table re-anchors on the last rest: the estimate at 180 stays 180 with ~0 offset
        est.calibration = device
        assertEquals(0f, est.offsetUt, 0.01f)
        feed(180f, 100, shift = -5f)
        assertEquals(180f, est.angleDeg, 0.5f)
        feed(90f, 100, shift = device.valueAt(90f) - cal.valueAt(90f)) // what this device reads at 90 through its own table
        assertEquals(90f, est.angleDeg, 1f)
        // degenerate pairs are refused
        assertTrue(cal.rescaledTo(Float.NaN, -260f) === cal)
        assertTrue(cal.rescaledTo(-197f, -197f) === cal)
        assertTrue(cal.rescaledTo(-260f, -197f) === cal) // flipped
        // a public anchor sets the offset directly and counts
        est.anchor(0f, cal.valueAt(0f) + 10f, t)
        assertEquals(3, est.anchors)
        assertEquals(10f - (device.valueAt(0f) - cal.valueAt(0f)), est.offsetUt, 0.01f)
        assertEquals(0.5f, HingeAngleEstimator.CONFIDENT, 0f)
    }

    @Test fun microRest_firesOnAnyStillnessWithoutTouchingTheTableOffset() {
        val cal = HingeCalibration.FOLD8_BX
        val est = HingeAngleEstimator(cal)
        val micro = mutableListOf<HingeAngleEstimator.Rest>()
        est.onMicroRest = { micro += it }
        var t = 0L
        fun feed(angle: Float, ms: Int) {
            repeat(ms / 10) { t += 10_000_000L; est.onMagUncalibrated(t, cal.valueAt(angle), -30f, -40f) }
        }
        // No 0/180 step at all: a pause mid-transition at ~90 deg. Never anchors ([onRest] unused).
        feed(90f, 500)
        assertEquals(1, micro.size)
        assertEquals(90f, micro[0].angleDeg, 0.5f)
        assertEquals(cal.valueAt(90f), micro[0].x, 0.01f)
        assertEquals(-30f, micro[0].y, 0.01f)
        assertEquals(-40f, micro[0].z, 0.01f)
        assertEquals(0, est.anchors)
        assertEquals(0f, est.offsetUt, 0f)
        // Debounced: continuing to hold still does not fire again immediately.
        feed(90f, 400)
        assertEquals(1, micro.size)
        // ... but does again once the debounce window has passed.
        feed(90f, 700)
        assertEquals(2, micro.size)
        assertEquals(0, est.anchors) // still never anchors the table
        // Motion resets the stillness window: no micro-rest fires while the angle is changing fast.
        for (a in 90..170 step 4) { t += 10_000_000L; est.onMagUncalibrated(t, cal.valueAt(a.toFloat()), -30f, -40f) }
        assertEquals(2, micro.size)
    }

    @Test fun calibration_fitIsMonotoneAndInvertible() {
        val pairs = (0 until 180 step 3).map { a -> a.toFloat() to (-200f - 60f * a / 180f + if (a % 2 == 0) 1.5f else -1.5f) }
        val cal = HingeCalibration.fit(pairs, closedRest = -197f, openRest = -261f)
        assertTrue(!cal.increasing)
        assertEquals(-197f, cal.valueAt(0f), 0f)
        assertEquals(-261f, cal.valueAt(180f), 0f)
        for (a in 10..170 step 10) assertEquals(a.toFloat(), cal.angleOf(cal.valueAt(a.toFloat())), 0.01f)
        assertEquals(30f, cal.angleOf(-210f), 6f)
    }
}
