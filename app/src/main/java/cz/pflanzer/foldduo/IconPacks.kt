package cz.pflanzer.foldduo

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.core.graphics.drawable.toBitmap
import org.xmlpull.v1.XmlPullParser

/** Intents every Play Store icon pack declares (Nova / ADW / Apex / Lawnchair convention). */
internal val ICON_PACK_ACTIONS = listOf(
    "org.adw.launcher.THEMES", "com.novalauncher.THEME", "com.anddoes.launcher.THEME", "com.teslacoilsw.launcher.THEME",
)

/** Parsed `appfilter.xml`: explicit component mappings plus the fallback treatment for uncovered apps. */
internal data class AppFilter(
    val components: Map<String, String> = emptyMap(),
    val iconBacks: List<String> = emptyList(),
    val iconMask: String? = null,
    val iconUpon: String? = null,
    val scale: Float = 1f,
) {
    /** First mapping for each package, used when the exact activity isn't listed. */
    val packages: Map<String, String> by lazy {
        buildMap { components.forEach { (key, drawable) -> putIfAbsent(key.substringBefore('/'), drawable) } }
    }
    val hasTreatment: Boolean get() = iconBacks.isNotEmpty() || iconMask != null || iconUpon != null

    fun drawableFor(packageName: String, className: String): String? =
        components["$packageName/$className"] ?: packages[packageName]

    fun iconBackFor(packageName: String, className: String): String? =
        iconBacks.takeIf { it.isNotEmpty() }?.let { it[Math.floorMod("$packageName/$className".hashCode(), it.size)] }
}

/** `ComponentInfo{pkg/.Cls}` → `pkg/pkg.Cls`; null for malformed or placeholder entries. */
internal fun normalizeAppFilterComponent(raw: String): String? {
    val inner = raw.trim().removePrefix("ComponentInfo{").removeSuffix("}").trim()
    val pkg = inner.substringBefore('/', "").trim()
    val cls = inner.substringAfter('/', "").trim()
    if (pkg.isEmpty() || cls.isEmpty() || pkg.contains(' ') || cls.contains(' ')) return null
    return "$pkg/${if (cls.startsWith('.')) pkg + cls else cls}"
}

/** Builds an [AppFilter] from (tag, attributes) start elements, independent of the XML source. */
internal fun parseAppFilter(elements: Sequence<Pair<String, Map<String, String>>>): AppFilter {
    val components = LinkedHashMap<String, String>()
    val backs = mutableListOf<String>()
    var mask: String? = null; var upon: String? = null; var scale = 1f
    fun images(attrs: Map<String, String>) = attrs.entries.filter { it.key.startsWith("img") }
        .sortedBy { it.key.removePrefix("img").toIntOrNull() ?: Int.MAX_VALUE }.map { it.value }.filter { it.isNotBlank() }
    for ((tag, attrs) in elements) when (tag) {
        "item" -> {
            val key = attrs["component"]?.let(::normalizeAppFilterComponent) ?: continue
            val drawable = attrs["drawable"]?.takeIf { it.isNotBlank() } ?: continue
            components.putIfAbsent(key, drawable)
        }
        "iconback" -> backs += images(attrs)
        "iconmask" -> mask = images(attrs).firstOrNull() ?: mask
        "iconupon" -> upon = images(attrs).firstOrNull() ?: upon
        "scale" -> scale = attrs["factor"]?.toFloatOrNull()?.takeIf { it > 0f && it <= 2f } ?: scale
    }
    return AppFilter(components, backs, mask, upon, scale)
}

private val XML_COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
private val XML_TAG = Regex("<\\s*([A-Za-z_][\\w.-]*)((?:\\s+[\\w:.-]+\\s*=\\s*(?:\"[^\"]*\"|'[^']*'))*)\\s*/?>")
private val XML_ATTR = Regex("([\\w:.-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')")

/** Minimal start-tag scanner for plain-text `assets/appfilter.xml`; appfilters are flat attribute lists. */
internal fun appFilterElements(xml: String): Sequence<Pair<String, Map<String, String>>> =
    XML_TAG.findAll(XML_COMMENT.replace(xml, "")).map { match ->
        match.groupValues[1] to XML_ATTR.findAll(match.groupValues[2]).associate { attr ->
            attr.groupValues[1] to xmlUnescape(attr.groupValues[2].ifEmpty { attr.groupValues[3] })
        }
    }

private fun xmlUnescape(value: String) = value.replace("&lt;", "<").replace("&gt;", ">")
    .replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

internal data class IconPackInfo(val packageName: String, val label: String, val icon: Bitmap?)

/** Installed icon packs, deduplicated across the four launcher conventions and sorted by label. */
internal fun installedIconPacks(context: Context): List<IconPackInfo> {
    val pm = context.packageManager
    return ICON_PACK_ACTIONS.flatMap { action ->
        runCatching { pm.queryIntentActivities(Intent(action), PackageManager.GET_META_DATA) }.getOrDefault(emptyList())
    }.map { it.activityInfo.packageName }.distinct().filter { it != context.packageName }.mapNotNull { pkg ->
        runCatching {
            val info = pm.getApplicationInfo(pkg, 0)
            IconPackInfo(pkg, pm.getApplicationLabel(info).toString(),
                runCatching { pm.getApplicationIcon(info).toBitmap(96, 96) }.getOrNull())
        }.getOrNull()
    }.sortedBy { it.label.lowercase() }
}

/** A loaded pack: its resources plus the parsed appfilter. */
internal class LoadedIconPack(val packageName: String, val version: Long, private val resources: Resources, val filter: AppFilter) {
    private fun drawable(name: String?): Drawable? {
        if (name == null) return null
        val id = resources.getIdentifier(name, "drawable", packageName).takeIf { it != 0 }
            ?: resources.getIdentifier(name, "mipmap", packageName).takeIf { it != 0 } ?: return null
        return runCatching { resources.getDrawable(id, null) }.getOrNull()
    }
    fun iconFor(component: ComponentName): Drawable? = drawable(filter.drawableFor(component.packageName, component.className))
    fun backFor(component: ComponentName): Drawable? = drawable(filter.iconBackFor(component.packageName, component.className))
    fun mask(): Drawable? = drawable(filter.iconMask)
    fun upon(): Drawable? = drawable(filter.iconUpon)

    companion object {
        fun load(context: Context, packageName: String): LoadedIconPack? = runCatching {
            val pm = context.packageManager
            val version = pm.getPackageInfo(packageName, 0).lastUpdateTime
            val res = pm.getResourcesForApplication(packageName)
            val fromAssets = runCatching { res.assets.open("appfilter.xml").bufferedReader().use { it.readText() } }.getOrNull()
            val filter = if (fromAssets != null) parseAppFilter(appFilterElements(fromAssets)) else {
                val id = res.getIdentifier("appfilter", "xml", packageName)
                if (id == 0) return null
                res.getXml(id).use { parser ->
                    parseAppFilter(sequence {
                        while (parser.next() != XmlPullParser.END_DOCUMENT) if (parser.eventType == XmlPullParser.START_TAG)
                            yield(parser.name to (0 until parser.attributeCount).associate { parser.getAttributeName(it) to parser.getAttributeValue(it) })
                    }.toList().asSequence())
                }
            }
            LoadedIconPack(packageName, version, res, filter)
        }.getOrNull()
    }
}
