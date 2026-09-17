package cz.pflanzer.foldduo.pose

import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Replays the recorded Fold 8 fixtures through [PoseClassifier] and checks
 * agreement with (a) the host-side system state (CLOSED/TENT/OPENED) outside
 * transition windows and (b) the manual labels Table/Flip/Stand.
 */
class FixtureReplayTest {
    private data class Row(val t: Long, val type: String, val o: JSONObject)

    private fun load(name: String): List<Row> = File("testdata", name).readLines()
        .filter { it.isNotBlank() }
        .map { JSONObject(it) }
        .map { Row(it.getLong("wallMs"), it.getString("type"), it) }

    private fun sysPose(name: String): FoldPose? = when (name) {
        "CLOSED" -> FoldPose.Closed; "TENT" -> FoldPose.Tent; "OPENED" -> FoldPose.Open; else -> null
    }

    /** Replays one session; returns (agreement ratio vs sysState, per-label majority pose). */
    private fun replay(probe: String, sys: String): Pair<Double, Map<String, FoldPose>> {
        val rows = (load(probe) + load(sys)).sortedBy { it.t }
        val classifier = PoseClassifier()
        var panel = Panel.Unknown
        var hinge = Float.NaN
        var lastTransition = Long.MIN_VALUE
        var sysState: FoldPose? = null
        var lastG = floatArrayOf(0f, 0f, 0f)
        var lastMotion = Long.MIN_VALUE
        var current: FoldPose? = null
        val sysChanges = rows.filter { it.type == "sysState" }.map { it.t }
        val labels = rows.filter { it.type == "label" }
        var agree = 0; var total = 0
        val perLabel = mutableMapOf<String, MutableMap<FoldPose, Int>>()
        for (r in rows) {
            when (r.type) {
                "window" -> { val p = r.o.optString("panel"); val np = if (p == "cover") Panel.Cover else if (p == "inner") Panel.Inner else panel
                    if (np != panel) { panel = np; lastTransition = r.t } }
                "hinge" -> { val d = r.o.getDouble("deg").toFloat(); if (HingeStep.of(d) != HingeStep.of(hinge)) lastTransition = r.t; hinge = d }
                "sysState" -> sysState = sysPose(r.o.getString("name"))
                "gravity" -> {
                    val g = floatArrayOf(r.o.getDouble("x").toFloat(), r.o.getDouble("y").toFloat(), r.o.getDouble("z").toFloat())
                    val delta = kotlin.math.abs(g[0] - lastG[0]) + kotlin.math.abs(g[1] - lastG[1]) + kotlin.math.abs(g[2] - lastG[2])
                    if (delta > 0.35f || lastMotion == Long.MIN_VALUE) lastMotion = r.t
                    lastG = g
                    val pose = classifier.classify(panel, hinge, g[0], g[1], g[2],
                        if (lastTransition == Long.MIN_VALUE) Long.MAX_VALUE else r.t - lastTransition,
                        r.t - lastMotion, current)
                    current = pose
                    val truth = sysState
                    val nearChange = sysChanges.any { kotlin.math.abs(it - r.t) < 1500 }
                    if (truth != null && !nearChange) {
                        total++
                        // Flip and Stand are refinements of Closed / Open; count them as agreement.
                        val coarse = when (pose) { FoldPose.Flip -> FoldPose.Closed; FoldPose.Stand -> FoldPose.Open; else -> pose }
                        if (coarse == truth) agree++
                    }
                    labels.firstOrNull { r.t - it.t in 1000..5000 }?.let { l ->
                        perLabel.getOrPut(l.o.getString("label")) { mutableMapOf() }.merge(pose, 1, Int::plus)
                    }
                }
            }
        }
        val majority = perLabel.mapValues { (_, m) -> m.maxByOrNull { it.value }!!.key }
        println("$probe: sysState agreement ${"%.1f".format(100.0 * agree / total)}% of $total samples; labels=$majority")
        return (agree.toDouble() / total) to majority
    }

    @Test fun session1_openCloseCycles_matchSystemState() {
        val (ratio, _) = replay("20260913-165318.jsonl", "sysstate-20260913-165317.jsonl")
        assertTrue("agreement $ratio", ratio >= 0.97)
    }

    @Test fun session2_heldPoses_matchSystemStateAndLabels() {
        val (ratio, labels) = replay("20260913-175448.jsonl", "sysstate-20260913-170029.jsonl")
        assertTrue("agreement $ratio", ratio >= 0.97)
        assertTrue("labels $labels", labels["Tent"] == FoldPose.Tent)
        assertTrue("labels $labels", labels["Table"] == FoldPose.Open)
        assertTrue("labels $labels", labels["Flip"] == FoldPose.Flip)
        assertTrue("labels $labels", labels["Stand"] == FoldPose.Stand)
    }
}
