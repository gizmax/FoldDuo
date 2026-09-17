package cz.pflanzer.foldduo

import androidx.compose.runtime.Composable

/** Release source set supplies a no-op: no per-tag counting, no logging, in a shipped build. */
internal object RecompositionCounter {
    fun increment(tag: String) = Unit
    fun startEpisode() = Unit
    fun endEpisode() = Unit
}

@Composable
internal fun trackRecomposition(tag: String) = Unit
