package cz.pflanzer.foldduo

/** Release source set supplies a no-op: no broadcast receiver in a shipped build. */
internal object DebugPerf {
    fun attach(activity: MainActivity) = Unit
}
