package cz.pflanzer.foldduo

import cz.pflanzer.foldduo.continuum.FoldConfig
import cz.pflanzer.foldduo.continuum.FoldShader
import cz.pflanzer.foldduo.pose.FoldPose
import cz.pflanzer.foldduo.pose.HingeAngleEstimator
import cz.pflanzer.foldduo.pose.HingeCalibration
import cz.pflanzer.foldduo.pose.HingeTiltSmoother
import cz.pflanzer.foldduo.pose.Panel
import cz.pflanzer.foldduo.pose.PoseSnapshot
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * Angle mode (UnfoldMorph.kt file comment): the frost driven by the magnetometer hinge angle.
 * Mapping constants for the Fold 8, the controller's mode switching and fallback, and a replay
 * of `pose/testdata/20260915-174608.jsonl` through estimator + smoother + mapping.
 */
class AngleMorphTest {
    private val config = FoldConfig()

    @Test fun `Fold 8 mapping - inner frost is full where the panel lights up and clear at flat`() {
        assertEquals(90f, FoldShader.PANEL_ON_HINGE, 0f)
        assertEquals(172f, FoldShader.FLAT_HINGE, 0f)
        assertEquals(15f, FoldShader.CLOSED_HINGE, 0f)
        assertEquals(FoldShader.PANEL_ON_HINGE, MorphCurve.ANGLE_PANEL_ON, 0f)
        assertEquals(FoldShader.FLAT_HINGE, MorphCurve.ANGLE_FLAT, 0f)
        assertEquals(FoldShader.CLOSED_HINGE, MorphCurve.ANGLE_COVER_CLOSED, 0f)
        // Inner: 45 at 90 (and below), linear to 0 at 172 (and above).
        assertEquals(FoldShader.MAX_TILT, FoldShader.tiltForHinge(90f, config), 0f)
        assertEquals(FoldShader.MAX_TILT, FoldShader.tiltForHinge(40f, config), 0f)
        assertEquals(FoldShader.MAX_TILT / 2f, FoldShader.tiltForHinge(131f, config), 1e-4f)
        assertEquals(0f, FoldShader.tiltForHinge(172f, config), 0f)
        assertEquals(0f, FoldShader.tiltForHinge(180f, config), 0f)
        assertTrue(FoldShader.tiltForHinge(165f, config) > FoldShader.FLAT_EPSILON)
        // The estimator's closed-rest noise (+-1 deg) and a 10 deg Earth-field slip stay under the cover's dead zone.
        assertEquals(0f, FoldShader.coverTiltForHinge(0f, config), 0f)
        assertEquals(0f, FoldShader.coverTiltForHinge(14f, config), 0f)
        assertEquals(FoldShader.MAX_TILT / 2f, FoldShader.coverTiltForHinge(52.5f, config), 1e-4f)
        assertEquals(FoldShader.MAX_TILT, FoldShader.coverTiltForHinge(90f, config), 0f)
        assertEquals(FoldShader.MAX_TILT, FoldShader.coverTiltForHinge(120f, config), 0f)
        // Monotone the whole way, both panels.
        var prev = Float.MAX_VALUE
        for (a in 0..180) { val t = FoldShader.tiltForHinge(a.toFloat(), config); assertTrue("inner at $a", t <= prev); prev = t }
        prev = -1f
        for (a in 0..180) { val t = FoldShader.coverTiltForHinge(a.toFloat(), config); assertTrue("cover at $a", t >= prev); prev = t }
        // Continuous across the swap: both panels read the full frost at 90.
        assertEquals(FoldShader.tiltForHinge(90f, config), FoldShader.coverTiltForHinge(90f, config), 0f)
        // MorphCurve routes by panel; NaN and Unknown are sharp.
        assertEquals(FoldShader.tiltForHinge(120f, config), MorphCurve.angleTilt(Panel.Inner, 120f), 0f)
        assertEquals(FoldShader.coverTiltForHinge(60f, config), MorphCurve.angleTilt(Panel.Cover, 60f), 0f)
        assertEquals(0f, MorphCurve.angleTilt(Panel.Unknown, 120f), 0f)
        assertEquals(0f, MorphCurve.angleTilt(Panel.Inner, Float.NaN), 0f)
        assertEquals(FoldShader.tiltFor(120f, config, innerPanel = true), MorphCurve.angleTilt(Panel.Inner, 120f), 0f)
    }

    @Test fun `layers take the larger of the timed morphs and angle mode`() {
        assertEquals(20f, MorphCurve.leftHalfTilt(1f, 0f, 20f), 0f)
        assertEquals(MorphCurve.tilt(0.5f), MorphCurve.leftHalfTilt(0.5f, 0f, 10f), 0f)
        assertEquals(30f, MorphCurve.leftHalfTilt(0.5f, 0f, 30f), 0f)
        assertEquals(FoldShader.MAX_TILT, MorphCurve.leftHalfTilt(1f, 0f, 90f), 0f) // clamped
        assertEquals(MorphCurve.leftHalfTilt(1f, 0f), MorphCurve.leftHalfTilt(1f, 0f, 0f), 0f)
        assertTrue(MorphCurve.leftHalfMoving(1f, 0f, 0.5f))
        assertFalse(MorphCurve.leftHalfMoving(1f, 0f, 0f))
        assertEquals(12f, MorphCurve.coverTilt(0f, 12f), 0f)
        assertEquals(FoldShader.MAX_TILT, MorphCurve.coverTilt(1f, 12f), 0f)
        assertEquals(0f, MorphCurve.coverTilt(0f, -3f), 0f)
        // Host widgets freeze whenever angle mode has the half frosted at all.
        assertTrue(leftHalfFrozen(running = false, closeFrostActive = false, angleTilt = 0.1f))
        assertFalse(leftHalfFrozen(running = false, closeFrostActive = false, angleTilt = 0f))
    }

    @Test fun `controller follows the angle on the inner panel, smoothed, and freezes host widgets`() {
        var now = 0L
        var snap = PoseSnapshot(pose = FoldPose.Open, panel = Panel.Inner, hingeDeg = 180f, hingeAngleDeg = 120f, hingeAngleConfidence = 1f, hingeAngleSource = "mag")
        val infos = mutableListOf<String>()
        val c = MorphController(now = { now }, snapshot = { snap }, log = {}, info = { infos += it })
        assertFalse(c.angleMode)
        assertEquals(0f, c.angleTilt.value, 0f)
        c.noteSnapshot(snap)
        assertTrue(c.angleMode)
        assertEquals("angle mode on (conf=1.00 angle=120.0 src=mag panel=Inner)", infos.last())
        // eases toward the mapped tilt at tau 60 ms over the 20 ms samples
        repeat(30) { now += 20L; c.noteSnapshot(snap) }
        assertEquals(MorphCurve.angleTilt(Panel.Inner, 120f), c.angleTilt.value, 0.1f)
        assertTrue(c.leftHalfFrozen)
        // opening on: the tilt clears with the angle, monotone
        var prev = c.angleTilt.value
        for (a in 121..180) {
            now += 20L; snap = snap.copy(hingeAngleDeg = a.toFloat()); c.noteSnapshot(snap)
            assertTrue("tilt at $a", c.angleTilt.value <= prev + 1e-4f); prev = c.angleTilt.value
        }
        repeat(20) { now += 20L; c.noteSnapshot(snap) }
        assertEquals(0f, c.angleTilt.value, 1e-3f)
        assertFalse(c.leftHalfFrozen)
        assertTrue(c.angleMode)
        // closing: the tilt rises with the angle decreasing, no play involved. "Zavírání jako
        // Duo": closing uses its own curve (MorphCurve.closingAngleTilt, full by 100 deg — item
        // 2), not the symmetric opening mapping.
        for (a in 179 downTo 100) { now += 20L; snap = snap.copy(hingeAngleDeg = a.toFloat()); c.noteSnapshot(snap) }
        repeat(20) { now += 20L; c.noteSnapshot(snap) }
        assertEquals(MorphCurve.closingAngleTilt(100f), c.angleTilt.value, 0.1f)
        assertEquals(MorphCurve.MAX_TILT, c.angleTilt.value, 0.1f)
        assertTrue(c.leftHalfFrozen)
        assertFalse(c.running); assertFalse(c.closeFrostActive)
        // a > 40 deg jump holds 200 ms, then the value is taken
        val held = c.angleTilt.value
        now += 20L; snap = snap.copy(hingeAngleDeg = 170f); c.noteSnapshot(snap)
        now += 100L; c.noteSnapshot(snap)
        assertEquals(held, c.angleTilt.value, 0.05f)
        repeat(30) { now += 20L; c.noteSnapshot(snap) }
        assertEquals(MorphCurve.angleTilt(Panel.Inner, 170f), c.angleTilt.value, 0.1f)
        // the angle goes NaN (out of range, or the estimator lost its anchor): mode off, tilt eases to 0
        now += 20L; snap = snap.copy(hingeAngleDeg = Float.NaN, hingeAngleConfidence = 0.2f, hingeAngleSource = "step"); c.noteSnapshot(snap)
        assertFalse(c.angleMode)
        assertEquals("angle mode off (conf=0.20 angle=- src=step panel=Inner)", infos.last())
        repeat(30) { now += 20L; c.noteSnapshot(snap) }
        assertEquals(0f, c.angleTilt.value, 1e-3f)
    }

    @Test fun `angle mode suppresses the timed plays it replaces and falls back when the angle is NaN`() {
        var now = 0L
        var snap = PoseSnapshot(pose = FoldPose.InMotion, panel = Panel.Inner, hingeDeg = 90f, msSinceTransition = 100L,
            hingeAngleDeg = 95f, hingeAngleConfidence = 1f, hingeAngleSource = "mag")
        val logs = mutableListOf<String>(); val infos = mutableListOf<String>()
        val c = MorphController(now = { now }, snapshot = { snap }, log = { logs += it }, info = { infos += it })
        c.noteSnapshot(snap)
        // The swap with a valid angle: no timed unfold clear, the hand drives it.
        assertFalse(c.shouldEnter(FoldPose.InMotion))
        assertTrue(infos.last().startsWith("unfold morph: angle mode + timed floor drives the clear, timed play skipped"))
        // A debug unfold replay (a duration) plays on top of angle mode.
        c.requestEnter(3000)
        assertTrue(c.shouldEnter(FoldPose.Open))
        // A hinge closing trigger on the inner panel is dropped in angle mode ...
        c.noteSnapshot(snap.copy(hingeDeg = 180f, hingeAngleDeg = 175f))
        c.noteSnapshot(snap.copy(hingeDeg = 90f, hingeAngleDeg = 150f))
        assertEquals(0, c.closeFrostRequests)
        assertTrue(logs.last().startsWith("close frost: trigger=hinge ignored (angle mode drives the left half)"))
        // ... the debug replay is not (it is explicit).
        c.requestCloseFrost("debug")
        assertEquals(1, c.closeFrostRequests)
        assertEquals("debug", c.takeCloseFrost())
        // Same on the cover: the hinge step off Closed is dropped while the angle drives the cover.
        var cover = PoseSnapshot(pose = FoldPose.Closed, panel = Panel.Cover, hingeDeg = 0f, hingeAngleDeg = 5f, hingeAngleConfidence = 1f, hingeAngleSource = "mag")
        val c2 = MorphController(now = { now }, snapshot = { cover }, log = { logs += it }, info = { infos += it })
        c2.noteSnapshot(cover)
        assertTrue(c2.angleMode)
        cover = cover.copy(hingeDeg = 90f, hingeAngleDeg = 60f); c2.noteSnapshot(cover)
        assertEquals(0, c2.coverFrostRequests)
        assertTrue(logs.last().startsWith("cover frost: trigger=hinge ignored (angle mode drives the cover)"))
        c2.requestCoverFrost("debug")
        assertEquals(1, c2.coverFrostRequests)
        // Fallback: with no valid angle everything behaves as before.
        snap = PoseSnapshot(pose = FoldPose.InMotion, panel = Panel.Inner, hingeDeg = 90f, msSinceTransition = 100L)
        val c3 = MorphController(now = { now }, snapshot = { snap }, log = { logs += it }, info = { infos += it })
        c3.noteSnapshot(snap)
        assertFalse(c3.angleMode)
        assertTrue(c3.shouldEnter(FoldPose.InMotion))
        c3.noteSnapshot(snap.copy(hingeDeg = 180f)); c3.noteSnapshot(snap.copy(hingeDeg = 90f))
        assertEquals(1, c3.closeFrostRequests)
        cover = PoseSnapshot(pose = FoldPose.Closed, panel = Panel.Cover, hingeDeg = 0f)
        val c4 = MorphController(now = { now }, snapshot = { cover }, log = { logs += it }, info = { infos += it })
        c4.noteSnapshot(cover); cover = cover.copy(hingeDeg = 90f); c4.noteSnapshot(cover)
        assertEquals(1, c4.coverFrostRequests)
        // An unknown panel never enters angle mode.
        val c5 = MorphController(now = { now }, snapshot = { snap }, log = {}, info = {})
        c5.noteSnapshot(PoseSnapshot(panel = Panel.Unknown, hingeAngleDeg = 120f, hingeAngleConfidence = 1f))
        assertFalse(c5.angleMode)
        assertNull(null)
    }

    @Test fun `debug angle previews the mapping for 3 s then hands back to the sensor`() {
        var now = 1_000L
        val snap = PoseSnapshot(pose = FoldPose.Open, panel = Panel.Inner, hingeDeg = 180f)
        val infos = mutableListOf<String>()
        val c = MorphController(now = { now }, snapshot = { snap }, log = {}, info = { infos += it })
        c.noteSnapshot(snap)
        assertFalse(c.angleMode)
        assertEquals(3_000L, MorphCurve.ANGLE_DEBUG_HOLD_MS)
        c.debugAngle(120f)
        assertTrue(c.angleMode)
        assertTrue(infos.any { it.startsWith("debug angle: 120.0° for 3000ms -> tilt 45.0°".take(20)) })
        assertTrue(infos.last().endsWith("panel=Inner debug)"))
        repeat(30) { now += 20L; c.noteSnapshot(snap) }
        assertEquals(MorphCurve.angleTilt(Panel.Inner, 120f), c.angleTilt.value, 0.1f)
        assertTrue(c.leftHalfFrozen)
        // 3 s later the real (NaN) angle is back: mode off, the half clears
        now += 3_000L
        c.noteSnapshot(snap)
        assertFalse(c.angleMode)
        repeat(30) { now += 20L; c.noteSnapshot(snap) }
        assertEquals(0f, c.angleTilt.value, 1e-3f)
        assertFalse(c.leftHalfFrozen)
    }

    // ---- fixture replay ----

    private class Mag(val t: Long, val x: Float, val y: Float, val z: Float)
    private class Step(val t: Long, val deg: Float)

    /**
     * The 2026-09-15 recording (13 open/close transitions on a table) through the estimator, the
     * Fold 8 mapping and the smoother, as the controller would drive the inner left half: the
     * tilt must be monotone within every slow transition (no frost coming back mid-open) and
     * reach the ends (full at the swap angle, sharp at flat).
     */
    @Test fun `fixture replay - the inner tilt is monotone within each slow transition`() {
        val mags = ArrayList<Mag>(); val steps = ArrayList<Step>()
        val file = File("../pose/testdata/20260915-174608.jsonl")
        assertTrue("fixture ${file.absolutePath}", file.exists())
        file.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            val o = JSONObject(line)
            when (o.getString("type")) {
                "mag_u" -> { val v = o.getJSONArray("v"); mags += Mag(o.getLong("sensorNs"), v.getDouble(0).toFloat(), v.getDouble(1).toFloat(), v.getDouble(2).toFloat()) }
                "hinge" -> steps += Step(o.getLong("sensorNs"), o.getDouble("deg").toFloat())
            }
        }
        mags.sortBy { it.t }; steps.sortBy { it.t }
        val est = HingeAngleEstimator(HingeCalibration.FOLD8_BX)
        val smoother = HingeTiltSmoother(tauMs = MorphCurve.ANGLE_TAU_MS, glitchDeg = MorphCurve.ANGLE_GLITCH_DEG, holdMs = MorphCurve.ANGLE_GLITCH_HOLD_MS)
        // (tMs, angle, tilt) per sample, the angle NaN while the estimate would not be published
        val out = ArrayList<Triple<Long, Float, Float>>(mags.size)
        var si = 0
        for (m in mags) {
            while (si < steps.size && steps[si].t <= m.t) { est.onHingeStep(steps[si].t, steps[si].deg); si++ }
            val e = est.onMagUncalibrated(m.t, m.x, m.y, m.z)
            val valid = e.confidence >= HingeAngleEstimator.CONFIDENT && est.anchors >= 1
            val angle = if (valid) e.angleDeg else Float.NaN
            val ms = m.t / 1_000_000L
            out += Triple(ms, angle, smoother.update(ms, angle, MorphCurve.angleTilt(Panel.Inner, angle)))
        }
        val firstValid = out.indexOfFirst { !it.second.isNaN() }
        assertTrue("estimate becomes valid after the first rest", firstValid in 1 until out.size)
        assertTrue("valid within 3 s: ${out[firstValid].first - out[0].first} ms", out[firstValid].first - out[0].first < 3_000L)
        assertEquals("no invalid samples after the first anchor", 0, out.drop(firstValid).count { it.second.isNaN() })
        assertEquals(0, smoother.glitches)
        // Transitions: runs of the estimate monotone to within a 15 deg hysteresis (the rule of
        // HingeAngleEstimatorTest / tools/hinge_fit.py) spanning >= 120 deg and >= 1 s: the full slow folds.
        val runs = ArrayList<List<Triple<Long, Float, Float>>>()
        var cur = ArrayList<Triple<Long, Float, Float>>(); var ext = 0f; var extI = 0; var dir = 0
        for (s in out.drop(firstValid)) {
            if (cur.isEmpty()) { cur += s; ext = s.second; extI = 0; continue }
            val a = s.second
            if (dir == 0) {
                if (abs(a - cur[0].second) >= 15f) { dir = if (a > cur[0].second) 1 else -1; ext = a; extI = cur.size }
                cur += s; continue
            }
            if ((a - ext) * dir >= 0) { ext = a; extI = cur.size; cur += s }
            else if ((ext - a) * dir > 15f) {
                runs += ArrayList(cur.subList(0, extI + 1))
                val tail = ArrayList(cur.subList(extI, cur.size)); tail += s
                cur = tail; dir = -dir; ext = a; extI = cur.size - 1
            } else cur += s
        }
        runs += cur
        val full = runs.filter { r -> r.maxOf { it.second } - r.minOf { it.second } >= 120f && r.last().first - r.first().first >= 1_000L }
        assertTrue("slow full transitions: ${full.size}", full.size >= 6)
        var worstReversal = 0f; var worstSpan = 0f
        val report = StringBuilder()
        for (r in full) {
            val opening = r.last().second > r.first().second
            val tilts = r.map { it.third }
            // The whole range is covered: full frost at the swap angle, sharp at flat (the
            // smoother lags the angle by ~tau, so the ends are the run's extremes, not its edges).
            assertTrue("${if (opening) "open" else "close"} max ${tilts.max()}", tilts.max() >= FoldShader.MAX_TILT - 1.5f)
            assertTrue("${if (opening) "open" else "close"} min ${tilts.min()}", tilts.min() <= 1.5f)
            var rev = 0f
            for (i in 1 until tilts.size) rev = maxOf(rev, if (opening) tilts[i] - tilts[i - 1] else tilts[i - 1] - tilts[i])
            // Cumulative: how far the tilt ever runs back from its extreme so far, against the fold.
            var extreme = tilts.first(); var span = 0f
            for (t in tilts) { if (opening) { extreme = minOf(extreme, t); span = maxOf(span, t - extreme) } else { extreme = maxOf(extreme, t); span = maxOf(span, extreme - t) } }
            worstReversal = maxOf(worstReversal, rev); worstSpan = maxOf(worstSpan, span)
            report.append(String.format(java.util.Locale.ROOT, "  %-5s %4.1f s n=%3d tilt %4.1f..%4.1f per-sample reversal %.2f, run-back %.1f deg\n",
                if (opening) "open" else "close", (r.last().first - r.first().first) / 1000.0, r.size, tilts.max(), tilts.min(), rev, span))
            assertTrue(r.size >= 40)
        }
        println("angle-mode replay: ${full.size} full slow transitions\n$report  worst per-sample reversal $worstReversal deg, worst run-back $worstSpan deg")
        // Monotone within a transition: the estimator's ~9 deg error reverses the raw angle by
        // up to 15 deg on this recording (HingeAngleEstimatorTest); through the mapping (45/82
        // per deg) and the 60 ms smoother the drawn tilt never runs back more than 4 deg of 45,
        // and never by more than 1 deg between two samples.
        assertTrue("run-back $worstSpan", worstSpan <= 4f)
        assertTrue("reversal $worstReversal", worstReversal <= 1f)
        // Between samples the smoothed tilt moves gently even on the 0.3 s snap-close.
        var worstStep = 0f
        for (i in firstValid + 1 until out.size) worstStep = maxOf(worstStep, abs(out[i].third - out[i - 1].third))
        assertTrue("per-sample step $worstStep", worstStep <= 8f)
    }
}
