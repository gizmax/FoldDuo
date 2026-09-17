package cz.pflanzer.foldduo.pose

/** Physical pose of the Galaxy Z Fold 8 as seen by the launcher. See PLAN.md, Fáze 1. */
enum class FoldPose { Closed, Open, Tent, Stand, Flip, InMotion }

/** Which physical panel is currently behind logical display 0. */
enum class Panel { Cover, Inner, Unknown }

/**
 * One classifier input. Thresholds are calibrated against `pose/testdata/`
 * (probe JSONL merged with the host-side `sysState` poller), never guessed.
 */
data class PoseSample(
    val hingeDeg: Float,          // NaN until the sensor has reported
    val gravity: FloatArray,      // m/s², device axes
    val panel: Panel,
    val innerOn: Boolean,
    val coverOn: Boolean,
    val stillMs: Long,
    val elapsedMs: Long,
)
