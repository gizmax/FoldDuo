package cz.pflanzer.foldduo

/** Release source set supplies a no-op: no watchdog thread/logging in a shipped build. */
internal object MainThreadWatchdog {
    fun start() = Unit
}
