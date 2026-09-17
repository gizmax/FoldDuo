package cz.pflanzer.foldduo

/**
 * App Library categorisation (PLAN.md, "App Library místo All apps"). Pure Kotlin: the
 * curated package table wins, then the manifest/installer category, then a small label
 * table; Samsung/Google system apps fall back to Utilities and everything else is Other.
 *
 * The package table is checked first on purpose: `ApplicationInfo.category` is not only
 * the manifest value — Play sets an installer override (`setApplicationCategoryHint`)
 * that maps its coarse "Communication" store category to [ManifestCategory.SOCIAL], which
 * put Chrome and Gmail into Social. Curated rows are more specific than that hint.
 */
enum class AppCategory(val title: String) {
    Suggestions("Suggestions"),
    RecentlyAdded("Recently Added"),
    Social("Social"),
    Productivity("Productivity"),
    Entertainment("Entertainment"),
    Music("Music"),
    Photos("Photo & Video"),
    Games("Games"),
    Maps("Travel"),
    Shopping("Shopping & Food"),
    Finance("Finance"),
    Health("Health & Fitness"),
    Utilities("Utilities"),
    Other("Other"),
}

/** `ApplicationInfo.CATEGORY_*` values, mirrored so this file and its tests need no Android types. */
object ManifestCategory {
    const val UNDEFINED = -1
    const val GAME = 0
    const val AUDIO = 1
    const val VIDEO = 2
    const val IMAGE = 3
    const val SOCIAL = 4
    const val NEWS = 5
    const val MAPS = 6
    const val PRODUCTIVITY = 7
    const val ACCESSIBILITY = 8
}

/** What the library needs to know about an installed app. `AppEntry` implements it. */
interface LibraryItem {
    val packageName: String
    val label: String
    val manifestCategory: Int
    val isSystem: Boolean
    val firstInstallTime: Long
}

data class LibrarySection<T : LibraryItem>(val category: AppCategory, val apps: List<T>)

const val SUGGESTION_LIMIT = 8
/**
 * Recently Added keeps this many of the newest installs, like the iOS folder: the last eight
 * regardless of how old they are, so the section never empties on a phone that has not seen a
 * new app for a while.
 */
const val RECENTLY_ADDED_LIMIT = 8

/**
 * Package rules, first match wins. An entry ending in `.` is a prefix; anything else matches
 * the package itself or its sub-packages (`com.discord` also matches `com.discord.beta`).
 */
private val PACKAGE_RULES: List<Pair<AppCategory, List<String>>> = listOf(
    AppCategory.Social to listOf(
        "com.whatsapp", "com.twitter.android", "com.facebook.", "com.instagram.", "org.telegram.", "com.discord",
        "com.snapchat.", "com.linkedin.", "org.thoughtcrime.securesms", "com.viber.", "com.reddit.", "com.pinterest",
        "com.zhiliaoapp.musically", "com.google.android.apps.messaging", "com.samsung.android.messaging",
        "com.google.android.talk", "com.skype.", "org.signal.", "com.threads.", "com.bsky."),
    AppCategory.Finance to listOf(
        "com.revolut.", "cz.csob.", "cz.airbank.", "cz.kb.", "eu.inmite.prj.kb.", "cz.moneta.", "cz.rb.", "cz.fio.",
        "com.google.android.apps.walletnfcrel", "com.samsung.android.spay", "com.paypal.", "com.coinbase.",
        "com.binance.", "com.wise.", "com.n26.", "cz.creditas."),
    AppCategory.Shopping to listOf(
        "com.alza.", "cz.alza.", "com.amazon.mShop.", "com.amazon.windowshop", "com.aliexpress.", "com.zalando.",
        "com.ebay.", "com.temu.", "com.zzkko", "cz.rohlik.", "cz.kosik.", "com.ikea.", "cz.mall.", "com.vinted.",
        "cz.datart.", "com.lidl."),
    AppCategory.Productivity to listOf(
        "com.samsung.android.app.notes", "com.samsung.android.calendar", "com.samsung.android.app.reminder",
        "com.google.android.apps.docs", "com.google.android.gm", "com.google.android.calendar", "com.google.android.keep",
        "com.google.android.apps.tasks", "com.google.android.apps.meetings", "com.microsoft.", "com.notion.id",
        "com.todoist", "com.slack", "us.zoom.videomeetings", "com.evernote", "md.obsidian", "com.dropbox.android",
        "com.google.android.apps.classroom", "com.samsung.android.email.provider"),
    AppCategory.Entertainment to listOf(
        "com.google.android.youtube", "com.netflix.", "com.hbo.", "com.wbd.stream", "com.disney.disneyplus",
        "com.amazon.avod.", "com.google.android.videos", "com.google.android.apps.magazines", "flipboard.app",
        "cz.seznam.novinky", "com.twitch.android.app", "tv.twitch.android.app", "com.cbs.app", "com.plexapp.android",
        "cz.o2.o2tv", "cz.sledovanitv."),
    AppCategory.Music to listOf(
        "com.spotify.", "com.soundcloud.android", "deezer.android.app", "com.aspiro.tidal", "com.audible.application",
        "com.apple.android.music", "com.google.android.apps.youtube.music", "com.samsung.android.app.music",
        "com.google.android.apps.podcasts", "fm.castbox.", "au.com.shiftyjelly.pocketcasts", "com.bandcamp.android",
        "com.shazam.android"),
    AppCategory.Photos to listOf(
        "com.google.android.apps.photos", "com.sec.android.gallery3d", "com.sec.android.app.camera",
        "com.google.android.GoogleCamera", "com.adobe.lrmobile", "com.niksoftware.snapseed", "com.vsco.cam",
        "com.samsung.android.app.galaxyraw", "com.canva.editor", "com.instagram.layout"),
    AppCategory.Games to listOf(
        "com.supercell.", "com.king.", "com.rovio.", "com.nianticlabs.", "com.mojang.", "com.ea.", "com.gameloft.",
        "com.miHoYo.", "com.google.android.play.games"),
    AppCategory.Maps to listOf(
        "com.google.android.apps.maps", "com.waze", "cz.seznam.mapy", "com.here.app.maps", "cz.dpp.", "com.ubercab",
        "ee.mtakso.client", "cz.liftago.", "cz.cd.", "com.komoot.android", "cz.regiojet.", "com.flixbus.app",
        "com.airbnb.android", "com.booking", "com.ryanair.cheapflights", "com.ideal.bus"),
    AppCategory.Health to listOf(
        "com.sec.android.app.shealth", "com.google.android.apps.fitness", "com.google.android.apps.healthdata",
        "com.fitbit.", "com.strava", "com.myfitnesspal.android", "com.garmin.android.apps.connectmobile", "com.oura.",
        "com.whoop.android", "com.samsung.android.wear.shealth", "cz.mzcr.", "com.calm.android", "com.headspace.android"),
    AppCategory.Utilities to listOf(
        "com.android.chrome", "com.sec.android.app.sbrowser", "org.mozilla.firefox", "com.brave.browser",
        "com.duckduckgo.mobile.android", "com.android.vending", "com.google.android.apps.authenticator2",
        "com.authy.authy", "com.google.android.apps.translate", "com.google.android.googlequicksearchbox",
        "com.google.android.apps.nbu.files", "com.sec.android.app.myfiles", "com.samsung.android.app.smartthings",
        "com.samsung.android.oneconnect", "com.termux", "com.tailscale.ipn", "com.nordvpn.android",
        "com.anthropic.claude", "com.openai.chatgpt", "com.google.android.apps.bard"),
)

/** Label words that mark an app when neither the manifest nor the package table knows it. */
private val LABEL_RULES: List<Pair<AppCategory, List<String>>> = listOf(
    AppCategory.Finance to listOf("bank", "banka", "wallet", "peněženka"),
    AppCategory.Shopping to listOf("shop", "e-shop", "obchod"),
    AppCategory.Games to listOf("game", "hra"),
)

private fun matchesPackage(packageName: String, rule: String): Boolean =
    if (rule.endsWith(".")) packageName.startsWith(rule)
    else packageName == rule || packageName.startsWith("$rule.")

private fun isSystemVendorPackage(packageName: String): Boolean =
    packageName.startsWith("com.samsung.") || packageName.startsWith("com.sec.") ||
        packageName.startsWith("com.google.android.") || packageName.startsWith("com.android.")

fun categorize(packageName: String, label: String, manifestCategory: Int, isSystem: Boolean): AppCategory {
    PACKAGE_RULES.firstOrNull { (_, rules) -> rules.any { matchesPackage(packageName, it) } }?.let { return it.first }
    when (manifestCategory) {
        ManifestCategory.GAME -> return AppCategory.Games
        ManifestCategory.AUDIO -> return AppCategory.Music
        ManifestCategory.VIDEO, ManifestCategory.NEWS -> return AppCategory.Entertainment
        ManifestCategory.IMAGE -> return AppCategory.Photos
        ManifestCategory.SOCIAL -> return AppCategory.Social
        ManifestCategory.MAPS -> return AppCategory.Maps
        ManifestCategory.PRODUCTIVITY -> return AppCategory.Productivity
        ManifestCategory.ACCESSIBILITY -> return AppCategory.Utilities
    }
    if (packageName.contains("bank", ignoreCase = true)) return AppCategory.Finance
    val words = label.lowercase()
    LABEL_RULES.firstOrNull { (_, keys) -> keys.any { key -> words.split(' ', '-', '_').any { it == key } } }
        ?.let { return it.first }
    if (isSystem && isSystemVendorPackage(packageName)) return AppCategory.Utilities
    return AppCategory.Other
}

fun LibraryItem.category(): AppCategory = categorize(packageName, label, manifestCategory, isSystem)

/**
 * Sections for the category grid. Suggestions (launcher's own recent launches, newest first,
 * at most [SUGGESTION_LIMIT]) and Recently Added (the [RECENTLY_ADDED_LIMIT] newest installs
 * of any age up to [now], listed by label like every other folder; [tileSlots] shows the three
 * newest large) come first; every other section lists its apps by label. Empty sections are
 * omitted. An app in Suggestions or Recently Added still appears in its own category, like on
 * iPhone. Apps without an install time, or dated after [now], never count as recent.
 */
fun <T : LibraryItem> buildLibrary(apps: List<T>, recentLaunches: List<String>, now: Long): List<LibrarySection<T>> {
    val byLabel = compareBy<T> { it.label.lowercase() }.thenBy { it.packageName }
    val suggestions = recentLaunches.distinct().flatMap { pkg -> apps.filter { it.packageName == pkg }.sortedWith(byLabel) }
        .distinct().take(SUGGESTION_LIMIT)
    val recentlyAdded = apps.filter { it.firstInstallTime in 1..now }
        .sortedWith(newestFirst<T>().then(byLabel)).take(RECENTLY_ADDED_LIMIT).sortedWith(byLabel)
    val grouped = apps.groupBy { it.category() }
    val sections = mutableListOf<LibrarySection<T>>()
    if (suggestions.isNotEmpty()) sections += LibrarySection(AppCategory.Suggestions, suggestions)
    if (recentlyAdded.isNotEmpty()) sections += LibrarySection(AppCategory.RecentlyAdded, recentlyAdded)
    AppCategory.entries.forEach { category ->
        if (category == AppCategory.Suggestions || category == AppCategory.RecentlyAdded) return@forEach
        grouped[category]?.takeIf { it.isNotEmpty() }?.let { sections += LibrarySection(category, it.sortedWith(byLabel)) }
    }
    return sections
}

private fun <T : LibraryItem> newestFirst() = compareByDescending<T> { it.firstInstallTime }

/** A folder tile holds [TILE_SLOTS] cells: large icons first, a 2×2 cluster of small ones last. */
const val TILE_SLOTS = 4
const val TILE_LARGE_ICONS = 3
const val TILE_CLUSTER_ICONS = 4

/** What a folder tile shows: [large] launchable icons and, when non-empty, a [cluster] of small ones. */
data class TileSlots<T : LibraryItem>(val large: List<T>, val cluster: List<T>) {
    val hasCluster: Boolean get() = cluster.isNotEmpty()
}

/**
 * iOS App Library tile rules: three large icons plus a cluster of four small ones; a folder
 * with fewer than five apps shows every app large; Suggestions is always up to four large
 * icons and never a cluster; Recently Added shows its three newest installs large and the
 * rest (in folder order) in the cluster.
 */
fun <T : LibraryItem> tileSlots(section: LibrarySection<T>): TileSlots<T> {
    val apps = section.apps
    val ordered = if (section.category == AppCategory.RecentlyAdded)
        apps.sortedWith(newestFirst<T>().thenBy { it.label.lowercase() }.thenBy { it.packageName }) else apps
    if (section.category == AppCategory.Suggestions || apps.size <= TILE_SLOTS)
        return TileSlots(ordered.take(TILE_SLOTS), emptyList())
    val large = ordered.take(TILE_LARGE_ICONS)
    return TileSlots(large, apps.filter { it !in large }.take(TILE_CLUSTER_ICONS))
}
