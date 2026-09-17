package cz.pflanzer.foldduo

/**
 * B28 "Spotlight": pure logic behind the universal search overlay — app query matching and
 * ranking, the curated settings-shortcut table and the pull-down gesture threshold. Free of
 * Android and Compose types so it runs as plain JUnit under app/src/test; the Compose glue
 * (the overlay, the pull-down modifier, contacts/UsageStats/Intent plumbing) lives in
 * Spotlight.kt.
 */

// --- Pull-down / swipe-up gesture threshold ---------------------------------------------

/** Minimum vertical travel (dp) before a drag on empty Home space opens or dismisses Spotlight. */
internal const val SPOTLIGHT_GESTURE_THRESHOLD_DP = 48f

/**
 * Whether a drag from the down point to a point [deltaXDp]/[deltaYDp] away (dp) is a "vertical
 * swipe" for Spotlight purposes: past [thresholdDp] on the vertical axis and dominant over
 * whatever horizontal drift occurred (so a mostly-horizontal page swipe never triggers it).
 */
internal fun spotlightVerticalSwipeTriggered(deltaXDp: Float, deltaYDp: Float, thresholdDp: Float = SPOTLIGHT_GESTURE_THRESHOLD_DP): Boolean =
    kotlin.math.abs(deltaYDp) >= thresholdDp && kotlin.math.abs(deltaYDp) > kotlin.math.abs(deltaXDp)

/** A pull-down (finger moving down) past the threshold: opens Spotlight. */
internal fun spotlightPullDownTriggered(deltaXDp: Float, deltaYDp: Float, thresholdDp: Float = SPOTLIGHT_GESTURE_THRESHOLD_DP): Boolean =
    deltaYDp > 0f && spotlightVerticalSwipeTriggered(deltaXDp, deltaYDp, thresholdDp)

/**
 * A swipe **up** (finger moving up) on the open Spotlight, past the threshold, dismisses it — the
 * sheet came down from the top, so it goes back up (17. 9. night, Tom: "swipe up zase zasune
 * nahoru"; Spotlight v2 had briefly flipped this to a swipe down). [SPOTLIGHT_GESTURE_THRESHOLD_DP]
 * shared with the pull-down that opens it. The caller only lets this fire while the results list
 * cannot scroll any further up itself (`!canScrollForward`), so a long list scrolls first and
 * closes on the overscroll.
 */
internal fun spotlightResultsDismissTriggered(deltaXDp: Float, deltaYDp: Float, thresholdDp: Float = SPOTLIGHT_GESTURE_THRESHOLD_DP): Boolean =
    deltaYDp < 0f && spotlightVerticalSwipeTriggered(deltaXDp, deltaYDp, thresholdDp)

// --- Shade vs Spotlight arbitration (B28 follow-up) -------------------------------------

/**
 * Ordinary touch-slop travel (dp) at which the notification shade opens once a downward drag is
 * in its lane — an order of magnitude below [SPOTLIGHT_GESTURE_THRESHOLD_DP], matching the
 * pre-existing shade gesture PageGestures.kt already had (Compose's own touch slop is close to
 * this, but the shared recognizer decides in dp so the rule stays testable here, independent of
 * any device's actual slop constant).
 */
internal const val SHADE_GESTURE_THRESHOLD_DP = 8f

/** How much of the page's height, measured down from the top, still counts as "near the top" for [downwardHomeGestureLane]. */
internal const val SHADE_TOP_FRACTION = 0.2f

/**
 * B42 "Dosah na coveru": how much of the page's height, measured up from the *bottom*, is the
 * Reachability lane — a third lane alongside the shade and Spotlight, cover-only (see
 * [downwardHomeGestureLane]'s [bottomFraction] parameter, which callers leave at its default 0f
 * — disabled — everywhere except the cover pager's own `onePageGestures` call).
 */
internal const val REACHABILITY_BOTTOM_FRACTION = 0.12f

/** Ordinary touch-slop-scale travel (dp) at which Reachability engages once a downward drag is in its lane — a deliberate short pull, well short of Spotlight's much larger threshold. */
internal const val REACHABILITY_GESTURE_THRESHOLD_DP = 16f

/** Which of the conflicting downward-drag features a gesture belongs to, decided purely from where it started. */
internal enum class DownwardHomeGestureLane { SHADE, SPOTLIGHT, REACHABILITY }

/** Terminal outcome of [downwardHomeGestureTarget]: which one actually fired, or neither yet. */
internal enum class DownwardHomeGestureTarget { NONE, SHADE, SPOTLIGHT, REACHABILITY }

/**
 * B28 follow-up "Spotlight vs shade" (+ B42 "Dosah na coveru"): all three live on the exact same
 * input — a downward drag over empty Home space — so PageGestures.kt's shared recognizer (the
 * single place with priority over all of them) has to pick one lane for the whole gesture before
 * any can fire, purely from where the drag *started*, since by the time enough of it has
 * happened to tell them apart by distance alone the shade would already have missed its usual,
 * much smaller trigger.
 *
 * The rule: a drag starting in the top [topFraction] of the page, or over the status rail
 * ([overRail] — true anywhere along it, not just its top, matching `shadePanelFor`'s existing
 * three-way split of the rail column itself), is the shade's lane, exactly as before this
 * arbitration existed. A drag starting in the bottom [bottomFraction] of the page (B42, cover
 * only — every other caller passes 0f, which never matches since `startYFraction` never exceeds
 * 1f) is Reachability's lane. Anywhere else on the page is Spotlight's lane. A page short enough
 * that the top and bottom bands would overlap keeps the shade's claim (checked first), so the
 * shade's usual small threshold is never accidentally weakened. Once a lane is picked it does
 * not change for the rest of the gesture, even if [startYFraction]/[overRail] are recomputed from
 * a still-live `down` position on every call — callers pass the same fixed start point
 * throughout one gesture, so the lane itself is stable; only [deltaYDp] grows.
 */
internal fun downwardHomeGestureLane(
    startYFraction: Float,
    overRail: Boolean,
    topFraction: Float = SHADE_TOP_FRACTION,
    bottomFraction: Float = 0f,
): DownwardHomeGestureLane = when {
    overRail || startYFraction < topFraction -> DownwardHomeGestureLane.SHADE
    bottomFraction > 0f && startYFraction >= 1f - bottomFraction -> DownwardHomeGestureLane.REACHABILITY
    else -> DownwardHomeGestureLane.SPOTLIGHT
}

/**
 * The live outcome of a downward drag at its current [deltaXDp]/[deltaYDp], given where it
 * started ([startYFraction], [overRail]; see [downwardHomeGestureLane]): [DownwardHomeGestureTarget.SHADE]
 * once the shade lane clears [shadeThresholdDp] (in practice immediately — it is barely past
 * touch slop), [DownwardHomeGestureTarget.SPOTLIGHT] once the Spotlight lane clears the much
 * larger [spotlightThresholdDp], [DownwardHomeGestureTarget.REACHABILITY] once the Reachability
 * lane (only reachable at all when [bottomFraction] > 0f — the cover's own call) clears
 * [reachabilityThresholdDp], and [DownwardHomeGestureTarget.NONE] otherwise — including a drag
 * that is not (yet, or at all) more vertical than horizontal, or moving upward. `NONE` in the
 * Spotlight or Reachability lane is not necessarily final: the caller keeps calling this on every
 * subsequent move while the drag stays downward-dominant, since more travel is exactly what it is
 * waiting for (PageGestures.kt does not re-decide the lane, only re-evaluates the threshold).
 */
internal fun downwardHomeGestureTarget(
    startYFraction: Float,
    overRail: Boolean,
    deltaXDp: Float,
    deltaYDp: Float,
    topFraction: Float = SHADE_TOP_FRACTION,
    shadeThresholdDp: Float = SHADE_GESTURE_THRESHOLD_DP,
    spotlightThresholdDp: Float = SPOTLIGHT_GESTURE_THRESHOLD_DP,
    bottomFraction: Float = 0f,
    reachabilityThresholdDp: Float = REACHABILITY_GESTURE_THRESHOLD_DP,
): DownwardHomeGestureTarget = when (downwardHomeGestureLane(startYFraction, overRail, topFraction, bottomFraction)) {
    DownwardHomeGestureLane.SHADE ->
        if (spotlightPullDownTriggered(deltaXDp, deltaYDp, shadeThresholdDp)) DownwardHomeGestureTarget.SHADE else DownwardHomeGestureTarget.NONE
    DownwardHomeGestureLane.SPOTLIGHT ->
        if (spotlightPullDownTriggered(deltaXDp, deltaYDp, spotlightThresholdDp)) DownwardHomeGestureTarget.SPOTLIGHT else DownwardHomeGestureTarget.NONE
    DownwardHomeGestureLane.REACHABILITY ->
        if (spotlightPullDownTriggered(deltaXDp, deltaYDp, reachabilityThresholdDp)) DownwardHomeGestureTarget.REACHABILITY else DownwardHomeGestureTarget.NONE
}

// --- Query matching and ranking ----------------------------------------------------------

private val SPOTLIGHT_COMBINING_MARKS = Regex("\\p{Mn}+")

/** Case- and accent-folded form of [text] for matching ("Kalendář" and "kalendar" both fold to "kalendar"). */
internal fun spotlightFold(text: String): String =
    java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
        .replace(SPOTLIGHT_COMBINING_MARKS, "")
        .lowercase()

/** True when every character of [query] appears in [target], in order, not necessarily contiguous. */
internal fun spotlightIsSubsequence(query: String, target: String): Boolean {
    if (query.isEmpty()) return true
    var qi = 0
    for (c in target) {
        if (c == query[qi]) {
            qi++
            if (qi == query.length) return true
        }
    }
    return false
}

/** Ordered worst→best so a ranking sort can compare by ordinal directly. */
internal enum class SpotlightMatchKind { NONE, SUBSEQUENCE, PREFIX, EXACT }

/** How [label] matches [query], accent- and case-folded on both sides. */
internal fun spotlightMatch(query: String, label: String): SpotlightMatchKind {
    val q = spotlightFold(query.trim())
    if (q.isEmpty()) return SpotlightMatchKind.NONE
    val l = spotlightFold(label)
    return when {
        l == q -> SpotlightMatchKind.EXACT
        l.startsWith(q) -> SpotlightMatchKind.PREFIX
        spotlightIsSubsequence(q, l) -> SpotlightMatchKind.SUBSEQUENCE
        else -> SpotlightMatchKind.NONE
    }
}

/** An app as far as ranking cares: its id (for recency lookup) and label (for matching). */
internal data class SpotlightAppCandidate(val id: String, val label: String)

/**
 * [apps] matching [query], best first: an exact or prefix match beats a fuzzy subsequence one;
 * within the same match kind, an app launched more recently (lower index in [recencyIds], newest
 * first — [LauncherState.recentLaunches] or a UsageStats-derived list) wins; a final alphabetical
 * fallback keeps the order stable when neither signal distinguishes two apps.
 */
internal fun rankSpotlightApps(query: String, apps: List<SpotlightAppCandidate>, recencyIds: List<String>): List<SpotlightAppCandidate> {
    val recencyIndex = recencyIds.withIndex().associate { (index, id) -> id to index }
    return apps.asSequence()
        .map { it to spotlightMatch(query, it.label) }
        .filter { (_, kind) -> kind != SpotlightMatchKind.NONE }
        .sortedWith(
            compareByDescending<Pair<SpotlightAppCandidate, SpotlightMatchKind>> { it.second.ordinal }
                .thenBy { recencyIndex[it.first.id] ?: Int.MAX_VALUE }
                .thenBy { it.first.label.lowercase() }
        )
        .map { it.first }
        .toList()
}

/**
 * The apps shown before any text is typed: newest-launched first when [recencyAvailable] (real
 * UsageStats data, or the launcher's own [LauncherState.recentLaunches] history), alphabetical
 * otherwise.
 */
internal fun defaultAppOrder(apps: List<SpotlightAppCandidate>, recencyIds: List<String>, recencyAvailable: Boolean): List<SpotlightAppCandidate> {
    if (!recencyAvailable) return apps.sortedBy { it.label.lowercase() }
    val recencyIndex = recencyIds.withIndex().associate { (index, id) -> id to index }
    return apps.sortedWith(compareBy({ recencyIndex[it.id] ?: Int.MAX_VALUE }, { it.label.lowercase() }))
}

// --- Settings shortcuts ---------------------------------------------------------------------

/**
 * One curated `Settings.ACTION_*` row: [action] is the raw intent action string (kept as a plain
 * String, not a reference to `android.provider.Settings`, so this file stays Android-free);
 * [labelEn]/[labelCs] are both searched, the UI shows whichever matches the device locale.
 */
internal data class SpotlightSettingsShortcut(val action: String, val labelEn: String, val labelCs: String)

/** ~20 curated system settings screens, offline (no INTERNET involved — these are local intents). */
internal val SPOTLIGHT_SETTINGS_SHORTCUTS: List<SpotlightSettingsShortcut> = listOf(
    SpotlightSettingsShortcut("android.settings.WIFI_SETTINGS", "Wi-Fi", "Wi-Fi"),
    SpotlightSettingsShortcut("android.settings.BLUETOOTH_SETTINGS", "Bluetooth", "Bluetooth"),
    SpotlightSettingsShortcut("android.settings.WIRELESS_SETTINGS", "Network & internet", "Síť a internet"),
    SpotlightSettingsShortcut("android.settings.AIRPLANE_MODE_SETTINGS", "Airplane mode", "Režim Letadlo"),
    SpotlightSettingsShortcut("android.settings.DATA_ROAMING_SETTINGS", "Mobile data", "Mobilní data"),
    SpotlightSettingsShortcut("android.settings.DISPLAY_SETTINGS", "Display", "Displej"),
    SpotlightSettingsShortcut("android.settings.SOUND_SETTINGS", "Sound", "Zvuk"),
    SpotlightSettingsShortcut("android.settings.ZEN_MODE_PRIORITY_SETTINGS", "Do Not Disturb", "Nerušit"),
    SpotlightSettingsShortcut("android.settings.BATTERY_SAVER_SETTINGS", "Battery", "Baterie"),
    SpotlightSettingsShortcut("android.settings.INTERNAL_STORAGE_SETTINGS", "Storage", "Úložiště"),
    SpotlightSettingsShortcut("android.settings.MANAGE_APPLICATIONS_SETTINGS", "Apps", "Aplikace"),
    SpotlightSettingsShortcut("android.settings.NOTIFICATION_SETTINGS", "Notifications", "Oznámení"),
    SpotlightSettingsShortcut("android.settings.LOCATION_SOURCE_SETTINGS", "Location", "Poloha"),
    SpotlightSettingsShortcut("android.settings.SECURITY_SETTINGS", "Security", "Zabezpečení"),
    SpotlightSettingsShortcut("android.settings.SYNC_SETTINGS", "Accounts", "Účty"),
    SpotlightSettingsShortcut("android.settings.ACCESSIBILITY_SETTINGS", "Accessibility", "Usnadnění"),
    SpotlightSettingsShortcut("android.settings.DATE_SETTINGS", "Date & time", "Datum a čas"),
    SpotlightSettingsShortcut("android.settings.LOCALE_SETTINGS", "Languages", "Jazyky"),
    SpotlightSettingsShortcut("android.settings.INPUT_METHOD_SETTINGS", "Keyboard", "Klávesnice"),
    SpotlightSettingsShortcut("android.settings.NFC_SETTINGS", "NFC", "NFC"),
    SpotlightSettingsShortcut("android.settings.APPLICATION_DEVELOPMENT_SETTINGS", "Developer options", "Možnosti pro vývojáře"),
    SpotlightSettingsShortcut("android.settings.DEVICE_INFO_SETTINGS", "About phone", "O telefonu"),
)

/** Sanity for [SPOTLIGHT_SETTINGS_SHORTCUTS]: every action and every label is unique. */
internal fun spotlightSettingsShortcutsAreSane(shortcuts: List<SpotlightSettingsShortcut> = SPOTLIGHT_SETTINGS_SHORTCUTS): Boolean {
    val actions = shortcuts.map { it.action }
    val enLabels = shortcuts.map { it.labelEn }
    val csLabels = shortcuts.map { it.labelCs }
    return actions.size == actions.toSet().size &&
        enLabels.size == enLabels.toSet().size &&
        csLabels.size == csLabels.toSet().size &&
        shortcuts.all { it.action.isNotBlank() && it.labelEn.isNotBlank() && it.labelCs.isNotBlank() }
}

// --- B46 "Dvojice aplikací": pairs in Spotlight results ----------------------------------

/** A pair as far as Spotlight ranking cares: its id (to launch it) and its two member labels. */
internal data class SpotlightPairCandidate(val id: String, val firstLabel: String, val secondLabel: String)

/** How a pair's row title reads: "Dvojice: A + B" (Czech) or "Pair: A + B" (English), matching
 * the locale switch [SPOTLIGHT_SETTINGS_SHORTCUTS] rows already use. */
internal fun spotlightPairTitle(firstLabel: String, secondLabel: String, czech: Boolean): String =
    "${if (czech) "Dvojice" else "Pair"}: $firstLabel + $secondLabel"

/**
 * [pairs] whose *either* member label matches [query] (folded/subsequence, same rule as
 * [spotlightMatch]), best match first, then alphabetically by first label so the order is stable.
 * Empty query returns nothing — pairs are search-only, never part of the default (empty-query)
 * app list.
 */
internal fun rankSpotlightPairs(query: String, pairs: List<SpotlightPairCandidate>): List<SpotlightPairCandidate> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()
    return pairs.asSequence()
        .map { candidate -> candidate to maxOf(spotlightMatch(q, candidate.firstLabel), spotlightMatch(q, candidate.secondLabel)) }
        .filter { (_, kind) -> kind != SpotlightMatchKind.NONE }
        .sortedWith(compareByDescending<Pair<SpotlightPairCandidate, SpotlightMatchKind>> { it.second.ordinal }
            .thenBy { it.first.firstLabel.lowercase() })
        .map { it.first }
        .toList()
}

// --- Result row entrance stagger ----------------------------------------------------------

/** Stagger between one result row's fade+rise entrance and the next on a query change. */
internal const val SPOTLIGHT_ROW_STAGGER_MS = 20L
internal const val SPOTLIGHT_ROW_STAGGER_MAX_ITEMS = 12

/** Delay before result row [index] starts its fade/rise entrance; negative indices start immediately. */
internal fun spotlightRowDelayMs(index: Int): Long =
    index.coerceIn(0, SPOTLIGHT_ROW_STAGGER_MAX_ITEMS) * SPOTLIGHT_ROW_STAGGER_MS

// --- Spotlight v2 "Bold Spotlight" (17. 9. evening): sections, Top Hit, empty state ------------

/**
 * The UI sections a matched result can land in — ordinal order is also the on-screen order
 * ("Aplikace", "Kontakty", "Nastavení", "Akce"). The web-search row is not here: it is not ranked
 * against a query (it always "matches"), is never a [pickSpotlightTopHit] candidate, and is always
 * appended last by the caller.
 */
internal enum class SpotlightSectionKind { APPS, CONTACTS, SETTINGS, ACTIONS }

/** One matched candidate as far as Top Hit selection / section grouping cares: which section it
 * belongs to, its id (opaque to this file — the caller's own prefixed key, e.g. "app:x"), and how
 * well it matched the query. */
internal data class SpotlightCandidate(val sectionKind: SpotlightSectionKind, val id: String, val match: SpotlightMatchKind)

/**
 * The single best match across every section, to show as the "Top Hit" card: the highest
 * [SpotlightMatchKind] wins outright; a tie between candidates of different sections breaks by
 * [SpotlightSectionKind]'s declared order (apps before contacts before settings before actions) so
 * the choice is deterministic, then by [SpotlightCandidate.id] so two candidates that are
 * otherwise indistinguishable still resolve to a stable pick. `null` when nothing in [candidates]
 * matched at all (including an empty list).
 */
internal fun pickSpotlightTopHit(candidates: List<SpotlightCandidate>): SpotlightCandidate? =
    candidates.filter { it.match != SpotlightMatchKind.NONE }
        .sortedWith(compareByDescending<SpotlightCandidate> { it.match.ordinal }
            .thenBy { it.sectionKind.ordinal }
            .thenBy { it.id })
        .firstOrNull()

/**
 * [candidates] grouped into sections for display, in [SpotlightSectionKind] order, empty sections
 * dropped entirely (so the caller never has to check for an empty list before drawing a header).
 * Within a section, ids are ordered by match strength, best first — ties keep their relative order
 * from [candidates] (a stable sort), since candidates are already ranked before this groups them.
 */
internal fun groupSpotlightSections(candidates: List<SpotlightCandidate>): List<Pair<SpotlightSectionKind, List<String>>> =
    SpotlightSectionKind.entries.mapNotNull { kind ->
        val ids = candidates.asSequence()
            .filter { it.sectionKind == kind && it.match != SpotlightMatchKind.NONE }
            .sortedByDescending { it.match.ordinal }
            .map { it.id }
            .toList()
        if (ids.isEmpty()) null else kind to ids
    }

/** The empty-state (no query typed) content blocks, top to bottom. */
internal enum class SpotlightEmptyStateBlock { SUGGESTIONS, FIELD, RECENT, SHORTCUTS }

/**
 * Fixed top-to-bottom order of the empty-state content: a row of predicted apps *above* the
 * search field, then "Recent" and "Shortcuts" glass cards below it — all inside the same scrolling
 * `LazyColumn` (Spotlight.kt), so this is the order its items are emitted in, not a layout
 * described in pixels.
 */
internal val SPOTLIGHT_EMPTY_STATE_ORDER: List<SpotlightEmptyStateBlock> = listOf(
    SpotlightEmptyStateBlock.SUGGESTIONS, SpotlightEmptyStateBlock.FIELD,
    SpotlightEmptyStateBlock.RECENT, SpotlightEmptyStateBlock.SHORTCUTS,
)

/**
 * Bottom content padding (dp) for the results/empty-state `LazyColumn`: at least [minPaddingDp] so
 * the last row always has breathing room, growing to whichever of [imeHeightDp] (the keyboard) or
 * [navigationBarDp] (the gesture/nav bar) is taller — never both added together, since only one of
 * them is ever actually the closest obstruction under the last row at a time. In the real overlay
 * the keyboard itself is cleared by `Modifier.imePadding()`, so callers pass `0f` for [imeHeightDp]
 * there and use this only for the nav-bar/minimum floor (17. 9. evening "Spotlight v2"); the
 * [imeHeightDp] parameter still exists so the math itself — and the "keyboard wins when it's the
 * tallest obstruction" rule — is unit-testable independent of that wiring choice.
 */
internal fun spotlightBottomContentPaddingDp(imeHeightDp: Float, navigationBarDp: Float, minPaddingDp: Float = 24f): Float =
    maxOf(imeHeightDp, navigationBarDp, minPaddingDp)

// --- Spotlight přes oba pane (17. 9. noc): two-column inner layout, highlight selection ---------

/**
 * Whether Spotlight splits into two columns (field/results on the right, a companion
 * suggestions/preview column on the left) or stays the single iPhone-style column: only when the
 * window actually has a fold seam ([hasSeam] — `LocalFoldSeam.current?.splits(width)`, `false` on
 * the cover or an unfolded-but-too-narrow window) *and* [widthDp] clears [thresholdDp], same
 * [FOLD_THRESHOLD_DP] every other wide/compact decision in the launcher uses. Before this patch
 * Spotlight always confined itself to one pane (`Pane.Right`) even when unfolded wide — Tom's "na
 * širokém otevřeném udělej Spotlight přes obě půlky" request.
 */
internal fun spotlightUsesTwoColumns(hasSeam: Boolean, widthDp: Float, thresholdDp: Float = FOLD_THRESHOLD_DP): Boolean =
    hasSeam && widthDp >= thresholdDp

/** What the left companion column shows: the empty-state suggestions/recent/shortcuts, or a live preview of the highlighted result. */
internal enum class SpotlightLeftPaneContent { SUGGESTIONS, PREVIEW }

/** [SpotlightLeftPaneContent.SUGGESTIONS] with no query typed yet, [SpotlightLeftPaneContent.PREVIEW] once there is one. */
internal fun spotlightLeftPaneContent(queryBlank: Boolean): SpotlightLeftPaneContent =
    if (queryBlank) SpotlightLeftPaneContent.SUGGESTIONS else SpotlightLeftPaneContent.PREVIEW

/**
 * Which result key is actually highlighted (and previewed on the left, two-column layout): the
 * caller's own [storedKey] (set by tapping a row) survives as long as it is still among
 * [availableKeys] for the current query; otherwise the highlight resets to [topHitKey] — the
 * default, matching the spec's "default: the Top Hit" — falling further back to the first of
 * [availableKeys] when there is no Top Hit at all (a query that only matched the always-present
 * "Hledat na webu" row, for instance). `null` only when [availableKeys] is itself empty.
 */
internal fun spotlightEffectiveHighlight(storedKey: String?, topHitKey: String?, availableKeys: List<String>): String? = when {
    storedKey != null && storedKey in availableKeys -> storedKey
    topHitKey != null && topHitKey in availableKeys -> topHitKey
    else -> availableKeys.firstOrNull()
}

/**
 * The key to move the highlight to for a hardware-keyboard ↑/↓ press: one step through
 * [orderedKeys] (the on-screen top-to-bottom order) in the direction of [delta] (negative = up,
 * positive = down), clamped at either end rather than wrapping. [currentKey] not being in
 * [orderedKeys] (nothing highlighted yet) starts from the first entry. `null` when [orderedKeys]
 * is empty.
 */
internal fun spotlightMoveHighlight(currentKey: String?, orderedKeys: List<String>, delta: Int): String? {
    if (orderedKeys.isEmpty()) return null
    val index = orderedKeys.indexOf(currentKey)
    val next = if (index < 0) 0 else (index + delta).coerceIn(0, orderedKeys.size - 1)
    return orderedKeys[next]
}

/** How many of the predicted [available] apps the left pane's 2×4 suggestions grid shows — capped at [capacity] (8, two rows of 4) even when more are ranked. */
internal fun spotlightSuggestionsGridCount(available: Int, capacity: Int = 8): Int = available.coerceIn(0, capacity)

/** Up to two uppercase initials from [name] ("Tomáš Pflanzer" -> "TP", "Cher" -> "C", blank -> "?")
 * — the contact preview's avatar fallback; this launcher has no contact-photo lookup anywhere. */
internal fun spotlightInitials(name: String): String {
    val letters = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        .take(2).mapNotNull { it.firstOrNull()?.uppercaseChar() }
    return if (letters.isEmpty()) "?" else letters.joinToString("")
}
