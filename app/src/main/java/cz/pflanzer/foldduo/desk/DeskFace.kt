package cz.pflanzer.foldduo.desk

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cz.pflanzer.foldduo.BuiltinAppleWidget
import cz.pflanzer.foldduo.LauncherState
import cz.pflanzer.foldduo.WidgetAppearance
import cz.pflanzer.foldduo.isBuiltinWidgetId
import cz.pflanzer.foldduo.standby.CalendarFace
import cz.pflanzer.foldduo.standby.ClockFace
import cz.pflanzer.foldduo.standby.PhotosFace
import cz.pflanzer.foldduo.standby.StandByEnvironment
import cz.pflanzer.foldduo.standby.StandByFace

/**
 * B47 "Stůl": the face pane — a read-only StandBy-like clock (always) plus, if the user picked a
 * second StandBy face (StandBySettings.kt "Faces"), that face beside it, and up to two Today
 * canvas widgets below (the built-in ones only — a real AppWidgetHostView needs its own host,
 * out of scope for a read-only preview pane; see the task report's doubts).
 */
@Composable
internal fun DeskFacePane(
    faces: List<StandByFace>,
    environment: StandByEnvironment,
    state: LauncherState,
    modifier: Modifier = Modifier,
) {
    val extraFace = faces.firstOrNull { it != StandByFace.Clock }
    val todayWidgetIds = remember(state.widgetPlacements) {
        state.widgetPlacements.filter { it.page == -1 && isBuiltinWidgetId(it.id) }.sortedBy { it.slot }.take(2).map { it.id }
    }
    Column(modifier.testTag("desk-face-pane").padding(16.dp)) {
        Row(Modifier.weight(1f).fillMaxWidth()) {
            ClockFace(java.time.LocalDateTime.now(), modifier = Modifier.weight(1f))
            if (extraFace != null) {
                Column(Modifier.weight(1f)) {
                    when (extraFace) {
                        StandByFace.Calendar -> CalendarFace(java.time.LocalDateTime.now(), environment.calendarGranted, modifier = Modifier.fillMaxSize())
                        StandByFace.Photos -> PhotosFace(java.time.LocalDateTime.now(), environment.photosGranted, modifier = Modifier.fillMaxSize())
                        StandByFace.Clock -> Unit
                    }
                }
            }
        }
        if (todayWidgetIds.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().height(140.dp).padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                todayWidgetIds.forEach { id ->
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = .08f))) {
                        BuiltinAppleWidget(id, onClick = {}, appearance = WidgetAppearance.Dark)
                    }
                }
            }
        }
    }
}
