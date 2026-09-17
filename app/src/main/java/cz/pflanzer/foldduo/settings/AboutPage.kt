package cz.pflanzer.foldduo.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.net.Uri

/** Author, roots and licences (Customization → About). Same rows/type as the other settings pages. */
@Composable
internal fun AboutPage() {
    val context = LocalContext.current
    val version = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "?"
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Text("Fold Duo", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("about-title"))
        Text("Version $version · Galaxy Z Fold launcher in the spirit of iPhone Duo",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Text("Programmed by", style = MaterialTheme.typography.titleMedium)
        Text("Tomáš Pflanzer · @gizmax", modifier = Modifier.testTag("about-author"))
        Spacer(Modifier.height(16.dp))
        Text("Built on", style = MaterialTheme.typography.titleMedium)
        Text("Duo Launcher (jakesgoodapps / Duo Launcher contributors) — MIT licence. " +
            "Fold effect (frost shader, tilt follower, panel detection) ported from duo-open by marcoazeem — MIT licence.",
            modifier = Modifier.testTag("about-roots"))
        Spacer(Modifier.height(16.dp))
        Text("Design reference", style = MaterialTheme.typography.titleMedium)
        Text("Apple, “Design for iPhone Duo” (WWDC 2026 session): pane identity, fold avoidance, Continuum.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/jakesgoodapps/DuoLauncher"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("about-upstream")) { Text("Duo Launcher on GitHub") }
        OutlinedButton(onClick = {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/marcoazeem/duo-open"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }, Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 8.dp).testTag("about-duo-open")) { Text("duo-open on GitHub") }
        Spacer(Modifier.height(12.dp))
        Text("Third-party notices: docs/upstream/THIRD_PARTY_NOTICES.md in the source tree.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
