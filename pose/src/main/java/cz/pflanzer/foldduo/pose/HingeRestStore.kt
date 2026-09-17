package cz.pflanzer.foldduo.pose

import android.content.Context
import android.content.SharedPreferences

/**
 * Per-device rest values of the uncalibrated magnetometer at the two hinge rests (closed and
 * open), the self-calibration signal of [HingeAngleEstimator] (pose/testdata/README.md): the
 * table's shape is the shipped default's ([HingeCalibration.FOLD8_BX]), its endpoints are
 * whatever this device reads at rest. Learned on first use, kept in SharedPreferences.
 */
class HingeRestStore(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    class Rest(val x: Float, val y: Float, val z: Float, val feature: Float, val learnedAtMs: Long)

    var closed: Rest? = load("closed")
        private set
    var open: Rest? = load("open")
        private set

    /** Record the rest at [angleDeg] (0 -> closed, 180 -> open). */
    fun record(angleDeg: Float, rest: Rest) {
        val key = if (angleDeg <= 90f) "closed" else "open"
        if (key == "closed") closed = rest else open = rest
        prefs.edit().putFloat("$key.x", rest.x).putFloat("$key.y", rest.y).putFloat("$key.z", rest.z)
            .putFloat("$key.feature", rest.feature).putLong("$key.at", rest.learnedAtMs).apply()
    }

    /** [default] with its endpoints at this device's rests, or [default] itself while a rest is missing. */
    fun calibration(default: HingeCalibration): HingeCalibration {
        val c = closed ?: return default
        val o = open ?: return default
        return default.rescaledTo(c.feature, o.feature)
    }

    fun clear() {
        closed = null; open = null
        prefs.edit().clear().apply()
    }

    private fun load(key: String): Rest? {
        if (!prefs.contains("$key.feature")) return null
        return Rest(prefs.getFloat("$key.x", 0f), prefs.getFloat("$key.y", 0f), prefs.getFloat("$key.z", 0f),
            prefs.getFloat("$key.feature", 0f), prefs.getLong("$key.at", 0L))
    }

    companion object {
        const val PREFS = "hinge_rests"
    }
}
