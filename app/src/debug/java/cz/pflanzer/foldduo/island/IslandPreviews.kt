package cz.pflanzer.foldduo.island

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cz.pflanzer.foldduo.DuoTheme

/** Android Studio previews of the rail island (80 dp = rail 96 dp − 16 dp) on a dune-coloured ground. */
private val sampleItems = listOf(
    IslandItem("call", packageName = "com.samsung.android.dialer", kind = IslandKind.CALL, title = "Mum",
        text = "Mobile", chronometerBase = System.currentTimeMillis() - 83_000L,
        actions = listOf(IslandAction("Hang up"), IslandAction("Speaker"))),
    IslandItem("timer", packageName = "com.sec.android.app.clockpackage", kind = IslandKind.TIMER, title = "Pasta",
        chronometerBase = System.currentTimeMillis() + 7 * 60_000L, countDown = true, actions = listOf(IslandAction("Stop")),
        accentColor = 0xFF5F57D9.toInt()),
    IslandItem("nav", packageName = "com.google.android.apps.maps", kind = IslandKind.NAVIGATION, title = "200 m",
        text = "Turn right onto Údolní", progress = .35f),
    IslandItem("media:com.spotify.music", packageName = "com.spotify.music", kind = IslandKind.MEDIA,
        title = "Weightless", text = "Marconi Union", progress = .42f, media = IslandMedia(playing = true)),
)

@Composable
private fun IslandGround(content: @Composable () -> Unit) {
    DuoTheme(dark = true) {
        Box(Modifier.size(140.dp, 420.dp).background(Color(0xFF8C6D46)), contentAlignment = Alignment.TopEnd) {
            Box(Modifier.padding(top = 16.dp, end = 8.dp).width(80.dp)) { content() }
        }
    }
}

@Preview(name = "Island collapsed, +2 overflow", showBackground = false)
@Composable
private fun IslandCollapsedPreview() = IslandGround {
    RailIsland(sampleItems, expandedKey = null, onExpandedChange = {})
}

@Preview(name = "Island single timer", showBackground = false)
@Composable
private fun IslandSinglePreview() = IslandGround {
    RailIsland(sampleItems.filter { it.kind == IslandKind.TIMER }, expandedKey = null, onExpandedChange = {})
}

/** "Ostrůvek do plochy": the expanded card is a root-level overlay now, not part of [RailIsland] itself — previewed on a wider ground so it has page-width room to grow into. */
private val previewAnchor = IslandCardAnchor(islandTopDp = 16f, pillWidthDp = 80f, paneStartDp = 0f, paneWidthDp = 360f, isCover = false)

@Composable
private fun IslandExpandedGround(content: @Composable () -> Unit) {
    DuoTheme(dark = true) {
        Box(Modifier.size(380.dp, 420.dp).background(Color(0xFF8C6D46)), contentAlignment = Alignment.TopEnd) { content() }
    }
}

@Preview(name = "Island expanded call", showBackground = false)
@Composable
private fun IslandExpandedCallPreview() = IslandExpandedGround {
    IslandExpandedOverlay(sampleItems, expandedKey = "call", anchor = previewAnchor, onDismiss = {})
}

@Preview(name = "Island expanded media", showBackground = false)
@Composable
private fun IslandExpandedMediaPreview() = IslandExpandedGround {
    IslandExpandedOverlay(sampleItems, expandedKey = "media:com.spotify.music", anchor = previewAnchor, onDismiss = {})
}
