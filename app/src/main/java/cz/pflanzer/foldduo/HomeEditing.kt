package cz.pflanzer.foldduo

const val GRID_COLUMNS = 4
/** 2026-09-17 noc "Mřížka 4x7 a obsah výš": 6 -> 7 (Tom, on the device: room for a bottom row; see [cz.pflanzer.foldduo.homeGeometry]'s `contentTop`). */
const val GRID_ROWS = 7
const val HOME_CELLS = GRID_COLUMNS * GRID_ROWS

/**
 * Per-page cell count before the 2026-09-17 "Mřížka 4x7" change (GRID_ROWS was 6). Frozen at its
 * historical value for old-schema/old-backup migrations ([migrateSchema5Apps],
 * [migrateSchema12HomeCells], [migrateSchema12LeadingCells]) — this must never track the live
 * [GRID_ROWS]/[HOME_CELLS] again, or a future grid change would silently corrupt these migrations.
 */
internal const val LEGACY_HOME_CELLS = GRID_COLUMNS * 6

/**
 * The row of the schema-5-migrated "third slot" full-width overflow widget ([migrateSchema5Widgets],
 * [cz.pflanzer.foldduo.WidgetController]'s `legacyPlacement`) — one row below the grid as it stood
 * before this change (GRID_ROWS was 6). Referenced by [replaceWidgetAtSameFootprint] and the
 * matching checks in LauncherModel.kt/LayoutBackup.kt to keep recognising that placement; pinned
 * so bumping [GRID_ROWS] again does not stop matching it.
 */
internal const val LEGACY_FULL_WIDTH_OVERFLOW_ROW = 6
const val EMPTY_WIDGET = -1
const val CLOCK_WIDGET = -2
const val DATE_WIDGET = -3
const val INFO_WIDGET = -4
const val NEEDS_BINDING_WIDGET = -5
// -6..-9 are the iOS-style built-ins (CLOCK_ANALOG_WIDGET .. PHOTOS_WIDGET) in BuiltinWidgets.kt.

/** Ids a persisted placement may carry: a host widget id, a built-in card, or the restore placeholder. */
fun isPlaceableWidgetId(id: Int) = id >= 0 || id == NEEDS_BINDING_WIDGET || isBuiltinWidgetId(id)

data class WidgetPlacement(val slot: Int, val id: Int, val page: Int, val column: Int, val row: Int, val spanX: Int, val spanY: Int,
    /** Look of a built-in card; ignored by host widgets. */
    val appearance: WidgetAppearance = WidgetAppearance.Auto,
    /** ARGB colour used by [WidgetAppearance.Tinted]; kept while another appearance is active. */
    val tint: Int? = null,
    /**
     * B26 "Smart stack" (WidgetStack.kt): ordered member ids sharing this placement's footprint.
     * Empty means a plain single widget ([id]); a size of one is never persisted (dissolved back
     * to empty) — every helper in WidgetStack.kt keeps that invariant. [id] is always one of
     * these members when the list is non-empty: the member currently shown.
     */
    val stackMembers: List<Int> = emptyList(),
    /** Smart Stack only: whether [StackRotation] may change the shown member automatically. */
    val smartRotate: Boolean = true)
data class WidgetRestore(val slot: Int, val providerComponent: String, val userSerial: Long, val title: String,
    val profileLabel: String, val isWork: Boolean = false, val sourceScope: String? = null)

val DEFAULT_WIDGET_PLACEMENTS = listOf(
    WidgetPlacement(0, CLOCK_WIDGET, 0, 0, 0, 2, 2),
    WidgetPlacement(1, DATE_WIDGET, 0, 2, 0, 2, 2),
)

fun homeCellPage(index: Int) = Math.floorDiv(index, HOME_CELLS)
fun homeCellLocal(index: Int) = Math.floorMod(index, HOME_CELLS)
fun homeCellIndex(page: Int, local: Int): Int {
    require(page >= -1 && local in 0 until HOME_CELLS)
    return page * HOME_CELLS + local
}

/** Shown when a widget of the requested size fits nowhere on the chosen page. */
const val NO_ROOM_FOR_SIZE_MESSAGE = "There isn’t room for this size. Choose another page or move an item first."

/** A "Move to …" destination offered in the widget options sheet. */
data class WidgetMoveTarget(val page: Int, val label: String)

/**
 * Pages a widget on [currentPage] can move to from its options sheet, in display order.
 * Unfolded ([expanded]) the left pane (page -1) comes first; from the left pane the Home pages
 * read "Move to Home N" so the two canvases stay distinguishable. The current page is not offered.
 * A trailing "New page" target (index [homePages]) creates a page on the fly, same as dropping
 * onto the "+" page in the pager.
 */
fun widgetMoveTargets(currentPage: Int, homePages: Int, expanded: Boolean): List<WidgetMoveTarget> {
    val leading = if (expanded && currentPage != -1) listOf(WidgetMoveTarget(-1, "Move to the left pane")) else emptyList()
    val homes = (0 until homePages).filter { it != currentPage }.map { page ->
        WidgetMoveTarget(page, if (currentPage == -1) "Move to Home ${page + 1}" else "Move to page ${page + 1}")
    }
    val newPage = if (currentPage != homePages) listOf(WidgetMoveTarget(homePages, "New page")) else emptyList()
    return leading + homes + newPage
}

private fun normalizedLeadingSlots(slots: List<String?>) =
    normalizeHomeSlots(slots.take(HOME_CELLS)).let { it + List(HOME_CELLS - it.size) { null } }

/** Nulls are intentional empty home cells. Only unused trailing cells are removed. */
fun normalizeHomeSlots(slots: List<String?>): List<String?> {
    val seen = mutableSetOf<String>()
    return slots.map { it?.takeIf { id -> id.isNotBlank() && seen.add(id) } }.dropLastWhile { it == null }
}

fun reconcileHomeSlots(slots: List<String?>, installed: Set<String>) =
    normalizeHomeSlots(slots.map { it?.takeIf(installed::contains) })

data class HomeLayout(
    val slots: List<String?>,
    val dock: List<String?>,
    val widgetPlacements: List<WidgetPlacement> = emptyList(),
    val folders: List<FolderEntry> = emptyList(),
    val widgetRestores: List<WidgetRestore> = emptyList(),
    val leadingSlots: List<String?> = List(HOME_CELLS) { null },
    /**
     * User-controlled page count (the "+" / "Přidat stránku" affordance and the customization
     * sheet's "Odebrat stránku"). Persisted; absent on migration defaults to 1, which is always
     * folded into [pageCount]'s minimum, so an unmigrated layout keeps exactly its derived page
     * count. Never lets [pageCount] drop below that derived minimum: a page still holding an icon
     * or widget cannot be made to vanish just by lowering this number.
     */
    val explicitPageCount: Int = 1,
    /**
     * B39 "Přehled stránek": Home pages the user hid via the page overview's checkmark (not the
     * mode filter — see [cz.pflanzer.foldduo.effectiveHiddenPages], which layers a mode's
     * complement on top of this set purely for pager/indicator purposes and is never persisted
     * here). Indices are real page numbers (`0 until pageCount`); the leading canvas (page -1)
     * can never appear. A hidden page's icons/widgets/folders are untouched — only the pager and
     * indicator skip it (see [visiblePageIndices]) — and it stays reachable from App Library.
     */
    val hiddenPages: Set<Int> = emptySet(),
    /** B46 "Dvojice aplikací": two-app tiles stored like folders (see [PairEntry]). */
    val pairs: List<PairEntry> = emptyList(),
) {
    val widgets: List<Int> get() {
        val last = widgetPlacements.maxOfOrNull { it.slot } ?: -1
        return List(maxOf(3, last + 1)) { slot -> placement(slot)?.id ?: EMPTY_WIDGET }
    }
    fun placement(slot: Int) = widgetPlacements.firstOrNull { it.slot == slot }
    fun folder(id: String) = folders.firstOrNull { it.id == id }
    fun widgetRestore(slot: Int) = widgetRestores.firstOrNull { it.slot == slot }
    /** The minimum number of pages this layout's content requires: pages holding icons or widgets can never vanish. */
    private val derivedPageCount get() = maxOf(homePageCount(slots.size),
        widgetPlacements.filter { it.page >= 0 }.maxOfOrNull { it.page + 1 } ?: 1)
    val pageCount: Int get() = maxOf(explicitPageCount.coerceAtLeast(1), derivedPageCount)
    fun slotAt(index: Int): String? = when (homeCellPage(index)) {
        -1 -> leadingSlots.getOrNull(homeCellLocal(index))
        in 0..Int.MAX_VALUE -> slots.getOrNull(index)
        else -> null
    }
    fun indexOfShortcut(id: String): Int? {
        val leading = leadingSlots.indexOf(id)
        if (leading >= 0) return homeCellIndex(-1, leading)
        return slots.indexOf(id).takeIf { it >= 0 }
    }
    fun slotsForPage(page: Int): List<String?> = when {
        page == -1 -> normalizedLeadingSlots(leadingSlots)
        page >= 0 -> List(HOME_CELLS) { local -> slots.getOrNull(homeCellIndex(page, local)) }
        else -> emptyList()
    }
    fun withSlot(index: Int, value: String?): HomeLayout = if (homeCellPage(index) == -1) {
        copy(leadingSlots = normalizedLeadingSlots(leadingSlots).toMutableList().apply { this[homeCellLocal(index)] = value })
    } else {
        val next = slots.toMutableList().apply { while (size <= index) add(null); this[index] = value }
        copy(slots = next.dropLastWhile { it == null })
    }
}

sealed interface DropTarget {
    data class Home(val index: Int) : DropTarget
    data class Dock(val index: Int) : DropTarget
    data class Library(val id: String) : DropTarget
    data class Widget(val index: Int) : DropTarget
    data class Folder(val id: String) : DropTarget
    data object Remove : DropTarget
}

fun canPlaceInDock(layout: HomeLayout, id: String): Boolean =
    id.isNotBlank() && !isReservedFolderId(id) && !isReservedPairId(id) &&
        layout.folders.none { id in it.appIds } && layout.pairs.none { id == it.first || id == it.second } &&
        (id in layout.dock || layout.dock.any { it == null })

fun WidgetPlacement.coveredIndices(): Set<Int> {
    if (page < -1) return emptySet()
    return buildSet {
        repeat(spanY) { y -> repeat(spanX) { x ->
            if (row + y < GRID_ROWS) add(homeCellIndex(page, (row + y) * GRID_COLUMNS + column + x))
        } }
    }
}

private fun WidgetPlacement.valid() =
    slot >= 0 && isPlaceableWidgetId(id) && page >= -1 && column >= 0 && row >= 0 &&
        spanX in 1..GRID_COLUMNS && spanY in 1..GRID_ROWS && column + spanX <= GRID_COLUMNS &&
        row + spanY <= GRID_ROWS

private fun widgetCells(layout: HomeLayout, exceptSlot: Int? = null) = layout.widgetPlacements
    .filter { it.slot != exceptSlot }.flatMapTo(mutableSetOf()) { it.coveredIndices() }

private fun overlaps(a: WidgetPlacement, b: WidgetPlacement) = a.page == b.page &&
    a.column < b.column + b.spanX && b.column < a.column + a.spanX &&
    a.row < b.row + b.spanY && b.row < a.row + a.spanY

/** Builds a non-persistable placement draft when the requested rectangle is available. */
fun widgetCandidate(layout: HomeLayout, slot: Int, targetIndex: Int, spanX: Int, spanY: Int): WidgetPlacement? {
    val page = homeCellPage(targetIndex)
    if (slot < 0 || page !in -1..layout.pageCount) return null
    val local = homeCellLocal(targetIndex)
    val candidate = WidgetPlacement(slot, EMPTY_WIDGET, page,
        local % GRID_COLUMNS, local / GRID_COLUMNS, spanX, spanY)
    if (spanX !in 1..GRID_COLUMNS || spanY !in 1..GRID_ROWS ||
        candidate.column + spanX > GRID_COLUMNS || candidate.row + spanY > GRID_ROWS) return null
    if (layout.widgetPlacements.any { it.slot != slot && overlaps(it, candidate) }) return null
    if (candidate.coveredIndices().any { layout.slotAt(it) != null }) return null
    return candidate
}

/** Moves use insertion order and transfer shortcuts between Home and the dock. */
fun dropApp(layout: HomeLayout, id: String, target: DropTarget): HomeLayout {
    if (id.isBlank() || layout.folders.any { id in it.appIds } || layout.pairs.any { id == it.first || id == it.second }) return layout
    return when (target) {
        is DropTarget.Home -> {
            val blocked = widgetCells(layout)
            val targetPage = homeCellPage(target.index)
            if (targetPage !in -1..layout.pageCount || target.index in blocked) return layout
            if (targetPage == -1) {
                val cells = normalizedLeadingSlots(layout.leadingSlots).toMutableList()
                val targetLocal = homeCellLocal(target.index)
                val sourceLocal = cells.indexOf(id)
                cells.indices.filter { it != sourceLocal && cells[it] == id }.forEach { cells[it] = null }
                val blockedLocal = blocked.filter { homeCellPage(it) == -1 }.mapTo(mutableSetOf(), ::homeCellLocal)
                if (targetLocal in blockedLocal) return layout
                if (sourceLocal == targetLocal) return layout
                if (sourceLocal >= 0) {
                    if (cells[targetLocal] == null) {
                        cells[sourceLocal] = null
                        cells[targetLocal] = id
                        return layout.copy(leadingSlots = cells,
                            slots = layout.slots.map { it?.takeUnless(id::equals) }.dropLastWhile { it == null },
                            dock = layout.dock.map { it?.takeUnless(id::equals) })
                    }
                    val usable = (minOf(sourceLocal, targetLocal)..maxOf(sourceLocal, targetLocal)).filterNot { it in blockedLocal }
                    val from = usable.indexOf(sourceLocal); val to = usable.indexOf(targetLocal)
                    if (from < 0 || to < 0) return layout
                    if (from < to) for (position in from until to) cells[usable[position]] = cells[usable[position + 1]]
                    else for (position in from downTo to + 1) cells[usable[position]] = cells[usable[position - 1]]
                    cells[targetLocal] = id
                } else if (cells[targetLocal] == null) cells[targetLocal] = id else {
                    val later = (targetLocal + 1 until HOME_CELLS).firstOrNull { it !in blockedLocal && cells[it] == null }
                    val earlier = (targetLocal - 1 downTo 0).firstOrNull { it !in blockedLocal && cells[it] == null }
                    val vacancy = later ?: earlier ?: return layout
                    val usable = (minOf(vacancy, targetLocal)..maxOf(vacancy, targetLocal)).filterNot { it in blockedLocal }
                    if (vacancy > targetLocal) for (position in usable.lastIndex downTo 1) cells[usable[position]] = cells[usable[position - 1]]
                    else for (position in 0 until usable.lastIndex) cells[usable[position]] = cells[usable[position + 1]]
                    cells[targetLocal] = id
                }
                return layout.copy(leadingSlots = cells,
                    slots = layout.slots.map { it?.takeUnless(id::equals) }.dropLastWhile { it == null },
                    dock = layout.dock.map { it?.takeUnless(id::equals) })
            }
            val slots = layout.slots.toMutableList()
            val source = slots.indexOf(id)
            slots.indices.filter { it != source && slots[it] == id }.forEach { slots[it] = null }
            while (slots.size <= target.index) slots.add(null)
            val occupied = slots[target.index] != null
            when {
                source == target.index -> Unit
                source >= 0 && !occupied -> { slots[source] = null; slots[target.index] = id }
                source >= 0 -> {
                    val usable = (minOf(source, target.index)..maxOf(source, target.index)).filterNot { it in blocked }
                    val from = usable.indexOf(source)
                    val to = usable.indexOf(target.index)
                    if (from < 0 || to < 0) return layout
                    if (from < to) for (position in from until to) slots[usable[position]] = slots[usable[position + 1]]
                    else for (position in from downTo to + 1) slots[usable[position]] = slots[usable[position - 1]]
                    slots[target.index] = id
                }
                else -> {
                    if (!occupied) slots[target.index] = id else {
                        var vacancy = target.index + 1
                        val limit = HOME_CELLS * (layout.pageCount + 1)
                        while (vacancy < limit && (vacancy in blocked || slots.getOrNull(vacancy) != null)) vacancy++
                        if (vacancy >= limit) return layout
                        while (slots.size <= vacancy) slots.add(null)
                        val usable = (target.index..vacancy).filterNot { it in blocked }
                        for (position in usable.lastIndex downTo 1) slots[usable[position]] = slots[usable[position - 1]]
                        slots[target.index] = id
                    }
                }
            }
            layout.copy(slots = slots.dropLastWhile { it == null }, dock = layout.dock.map { it?.takeUnless { dockId -> dockId == id } },
                leadingSlots = normalizedLeadingSlots(layout.leadingSlots).map { it?.takeUnless(id::equals) })
        }
        is DropTarget.Dock -> {
            if (target.index !in layout.dock.indices || !canPlaceInDock(layout, id)) return layout
            val dock = layout.dock.toMutableList()
            val source = dock.indexOf(id)
            dock.indices.filter { it != source && dock[it] == id }.forEach { dock[it] = null }
            val occupied = dock[target.index] != null
            when {
                source == target.index -> Unit
                source >= 0 && !occupied -> { dock[source] = null; dock[target.index] = id }
                source >= 0 -> { dock.removeAt(source); dock.add(target.index, id) }
                !occupied -> dock[target.index] = id
                else -> {
                    val later = (target.index + 1 until dock.size).firstOrNull { dock[it] == null }
                    val earlier = (target.index - 1 downTo 0).firstOrNull { dock[it] == null }
                    when {
                        later != null -> { for (i in later downTo target.index + 1) dock[i] = dock[i - 1]; dock[target.index] = id }
                        earlier != null -> { for (i in earlier until target.index) dock[i] = dock[i + 1]; dock[target.index] = id }
                        else -> return layout
                    }
                }
            }
            layout.copy(slots = layout.slots.map { it?.takeUnless { app -> app == id } }.dropLastWhile { it == null }, dock = dock,
                leadingSlots = normalizedLeadingSlots(layout.leadingSlots).map { it?.takeUnless(id::equals) })
        }
        else -> layout
    }
}

fun placeWidget(layout: HomeLayout, placement: WidgetPlacement): HomeLayout {
    if (!placement.valid()) return replaceWidgetAtSameFootprint(layout, placement)
    if (placement.id == NEEDS_BINDING_WIDGET && layout.widgetRestore(placement.slot) == null) return layout
    val without = layout.widgetPlacements.filterNot { it.slot == placement.slot }
    if (without.any { overlaps(it, placement) }) return layout
    if (placement.coveredIndices().any { layout.slotAt(it) != null }) return layout
    return layout.copy(widgetPlacements = (without + placement).sortedBy { it.slot },
        widgetRestores = if (placement.id == NEEDS_BINDING_WIDGET) layout.widgetRestores
            else layout.widgetRestores.filterNot { it.slot == placement.slot })
}

/** Rebinds retained legacy overflow without making that footprint newly placeable. */
fun replaceWidgetAtSameFootprint(layout: HomeLayout, placement: WidgetPlacement): HomeLayout {
    val existing = layout.placement(placement.slot) ?: return layout
    val sameFootprint = placement.page == existing.page && placement.column == existing.column &&
        placement.row == existing.row && placement.spanX == existing.spanX && placement.spanY == existing.spanY
    val retainedSpecial = existing.page > 0 && existing.slot / 3 == existing.page && existing.slot % 3 == 2 &&
        existing.column == 0 && existing.row == LEGACY_FULL_WIDTH_OVERFLOW_ROW && existing.spanX == GRID_COLUMNS && existing.spanY == 4
    if (!sameFootprint || !retainedSpecial || placement.id == EMPTY_WIDGET ||
        (placement.id == NEEDS_BINDING_WIDGET && layout.widgetRestore(placement.slot) == null)) return layout
    return layout.copy(widgetPlacements = layout.widgetPlacements.map { if (it.slot == placement.slot) placement else it },
        widgetRestores = if (placement.id == NEEDS_BINDING_WIDGET) layout.widgetRestores
            else layout.widgetRestores.filterNot { it.slot == placement.slot })
}

fun moveWidget(layout: HomeLayout, slot: Int, index: Int): HomeLayout {
    val old = layout.placement(slot) ?: return layout
    val page = homeCellPage(index)
    if (page !in -1..layout.pageCount) return layout
    val local = homeCellLocal(index)
    return placeWidget(layout, old.copy(page = page, column = local % GRID_COLUMNS,
        row = local / GRID_COLUMNS, spanY = old.spanY.coerceAtMost(GRID_ROWS)))
}

fun resizeWidget(layout: HomeLayout, slot: Int, spanX: Int, spanY: Int): HomeLayout {
    val old = layout.placement(slot) ?: return layout
    return placeWidget(layout, old.copy(spanX = spanX, spanY = spanY))
}

/** Restyles a built-in card or frames a host widget in place; geometry is untouched, so nothing can overlap or move. */
fun styleWidget(layout: HomeLayout, slot: Int, appearance: WidgetAppearance, tint: Int?): HomeLayout {
    val old = layout.placement(slot) ?: return layout
    if (!supportsWidgetAppearance(old.id)) return layout
    return layout.copy(widgetPlacements = layout.widgetPlacements.map {
        if (it.slot == slot) it.copy(appearance = appearance, tint = tint) else it })
}

/** Remove only the shortcut/placement, never the installed app or widget binding. */
fun removePlacement(layout: HomeLayout, source: DropTarget): HomeLayout = when (source) {
    is DropTarget.Home -> if (layout.slotAt(source.index)?.let { isFolderId(it) || isPairId(it) } == true) layout
        else layout.withSlot(source.index, null)
    is DropTarget.Dock -> layout.copy(dock = layout.dock.mapIndexed { i, id -> if (i == source.index) null else id })
    is DropTarget.Widget -> layout.copy(widgetPlacements = layout.widgetPlacements.filterNot { it.slot == source.index },
        widgetRestores = layout.widgetRestores.filterNot { it.slot == source.index })
    else -> layout
}

/** Appends one empty Home page after the last, for the pager's "+" / "Přidat stránku" affordance. */
fun addHomePage(layout: HomeLayout): HomeLayout = layout.copy(explicitPageCount = layout.pageCount + 1)

/**
 * Removes home page [page], shifting every later page's slots and widget placements down by one
 * so the pages after it keep their content. Refuses (returns [layout] unchanged) when [page] is
 * out of range or removing it would drop below the minimum of one page. When [force] is false
 * (the original "Odebrat stránku" behaviour) it also refuses when the page still holds an icon, a
 * folder, a pair or a widget.
 *
 * When [force] is true (2026-09-17 noc "Mazání stránek s dotazem") a non-empty page is deleted
 * anyway: its plain app shortcuts simply stop being on Home (the apps stay installed, so they
 * still show in App Library); any folder or pair whose tile sat on the page is dissolved — the
 * folder/pair entry itself is dropped, and since its member apps were never Home slots of their
 * own, they too simply leave Home the same way a plain shortcut does; any widget placed on the
 * page (a Smart Stack included — it is just data on one [WidgetPlacement]) is dropped from
 * [HomeLayout.widgetPlacements] entirely rather than shifted. The caller is responsible for
 * releasing the underlying host widget id, the same way a normal single-widget removal does (see
 * [WidgetController.remove]/[WidgetController.removeHomePage]).
 *
 * Either way, [HomeLayout.hiddenPages] is remapped the same way slots and widgets are: [page]
 * drops out, every later hidden page shifts down by one. The leading canvas (page -1) is never
 * affected.
 */
fun removeHomePage(layout: HomeLayout, page: Int, force: Boolean = false): HomeLayout {
    if (page !in 0 until layout.pageCount || layout.pageCount <= 1) return layout
    if (!force) {
        if (layout.slotsForPage(page).any { it != null }) return layout
        if (layout.widgetPlacements.any { it.page == page }) return layout
    }
    val padded = layout.slots + List((layout.pageCount * HOME_CELLS - layout.slots.size).coerceAtLeast(0)) { null }
    val removedIds = padded.filterIndexed { index, _ -> homeCellPage(index) == page }.filterNotNullTo(mutableSetOf())
    val shiftedSlots = normalizeHomeSlots(padded.filterIndexed { index, _ -> homeCellPage(index) != page })
    val shiftedWidgets = layout.widgetPlacements.mapNotNull { placement ->
        when {
            placement.page == page -> null
            placement.page > page -> placement.copy(page = placement.page - 1)
            else -> placement
        }
    }
    val shiftedHidden = layout.hiddenPages.mapNotNullTo(mutableSetOf()) {
        when { it == page -> null; it > page -> it - 1; else -> it }
    }
    return layout.copy(
        slots = shiftedSlots,
        widgetPlacements = shiftedWidgets,
        folders = if (removedIds.isEmpty()) layout.folders else layout.folders.filterNot { it.id in removedIds },
        pairs = if (removedIds.isEmpty()) layout.pairs else layout.pairs.filterNot { it.id in removedIds },
        hiddenPages = shiftedHidden,
        explicitPageCount = (layout.pageCount - 1).coerceAtLeast(1))
}

/**
 * Counts backing the "Smazat stránku?" confirmation body (2026-09-17 noc): every plain app
 * shortcut on [page] as one, plus every app inside a folder or pair sitting there (dissolving
 * either sends its members off Home the same way a plain shortcut leaving does) — that is the
 * first number; every widget placed on [page] (a Smart Stack counts once, like any other
 * placement) is the second.
 */
fun homeDeletePageCounts(layout: HomeLayout, page: Int): Pair<Int, Int> {
    val appCount = layout.slotsForPage(page).filterNotNull().sumOf { id ->
        when {
            isFolderId(id) -> layout.folder(id)?.appIds?.size ?: 0
            isPairId(id) -> if (layout.pair(id) != null) 2 else 0
            else -> 1
        }
    }
    val widgetCount = layout.widgetPlacements.count { it.page == page }
    return appCount to widgetCount
}

/**
 * Whether deleting [page] needs the "Smazat stránku?" confirmation (it holds at least one app,
 * folder, pair or widget) or can happen immediately with just a haptic (already empty).
 */
fun homeDeletePageNeedsConfirm(layout: HomeLayout, page: Int): Boolean {
    val (apps, widgets) = homeDeletePageCounts(layout, page)
    return apps > 0 || widgets > 0
}

/** The "Smazat stránku?" dialog's body line, built from [homeDeletePageCounts]'s two numbers. */
fun homeDeletePageMessage(appCount: Int, widgetCount: Int): String =
    "Stránka obsahuje $appCount aplikací / $widgetCount widgetů. Aplikace zůstanou v App Library."

/**
 * iOS-style cleanup: while there is more than one page and the last one holds nothing, drop it.
 * Middle empty pages are kept — only trailing pages disappear. Call this once an edit gesture
 * (drag, widget removal) has settled, not while it is still in progress.
 */
tailrec fun trimTrailingEmptyPages(layout: HomeLayout): HomeLayout {
    if (layout.pageCount <= 1) return layout
    val last = layout.pageCount - 1
    val empty = layout.slotsForPage(last).all { it == null } && layout.widgetPlacements.none { it.page == last }
    return if (!empty) layout else trimTrailingEmptyPages(removeHomePage(layout, last))
}

// --- B39 "Přehled stránek a režimy plochy": reordering, hiding, and the pager's visible-index mapping. ---

/**
 * Moves Home page [from] to position [to] (both `0 until pageCount`, the leading canvas is never
 * a candidate), taking its icons, folders, and widget placements — [WidgetPlacement.stackMembers]
 * along with them, since a placement moves as one unit — plus its hidden flag with it. Every
 * other page keeps its own content, just renumbered to make room. A no-op (returns [layout]
 * unchanged) when either index is out of range or they are equal.
 */
fun reorderHomePages(layout: HomeLayout, from: Int, to: Int): HomeLayout {
    val count = layout.pageCount
    if (from !in 0 until count || to !in 0 until count || from == to) return layout
    val order = (0 until count).toMutableList()
    val moved = order.removeAt(from)
    order.add(to, moved)
    // newPageOf[oldPage] = the page number that old page's content now lives at.
    val newPageOf = IntArray(count)
    order.forEachIndexed { newIndex, oldIndex -> newPageOf[oldIndex] = newIndex }
    val padded = layout.slots + List((count * HOME_CELLS - layout.slots.size).coerceAtLeast(0)) { null }
    val reordered = arrayOfNulls<String?>(count * HOME_CELLS)
    for (oldPage in 0 until count) {
        val newPage = newPageOf[oldPage]
        for (local in 0 until HOME_CELLS) reordered[newPage * HOME_CELLS + local] = padded.getOrNull(oldPage * HOME_CELLS + local)
    }
    val newWidgets = layout.widgetPlacements.map { placement ->
        if (placement.page in 0 until count) placement.copy(page = newPageOf[placement.page]) else placement
    }
    val newHidden = layout.hiddenPages.mapTo(mutableSetOf()) { if (it in 0 until count) newPageOf[it] else it }
    return layout.copy(slots = normalizeHomeSlots(reordered.toList()), widgetPlacements = newWidgets, hiddenPages = newHidden)
}

/**
 * Hides or shows Home page [page] (the overview's checkmark). Refuses — returning [layout]
 * unchanged — for the leading canvas, an out-of-range page, or a hide that would leave no page
 * visible at all (at least one must always remain, mirroring iOS). A hidden page's own content
 * is untouched; only [visiblePageIndices] (and everything built on it) skips it.
 */
fun setPageHidden(layout: HomeLayout, page: Int, hidden: Boolean): HomeLayout {
    if (page !in 0 until layout.pageCount) return layout
    if (!hidden) return layout.copy(hiddenPages = layout.hiddenPages - page)
    val next = layout.hiddenPages + page
    if (next.size >= layout.pageCount) return layout
    return layout.copy(hiddenPages = next)
}

/**
 * The real page numbers (`0 until pageCount`) the pager and indicator should actually show, in
 * order, once [hidden] pages are skipped. Never empty: if every page were hidden this falls back
 * to showing all of them, the same safety [setPageHidden] enforces on the way in.
 */
fun visiblePageIndices(pageCount: Int, hidden: Set<Int>): List<Int> {
    val visible = (0 until pageCount).filter { it !in hidden }
    return visible.ifEmpty { (0 until pageCount).toList() }
}

/** The real page shown at visible slot [visibleIndex] (clamped into range). `0 until pageCount` if nothing is hidden. */
fun realPageForVisibleIndex(pageCount: Int, hidden: Set<Int>, visibleIndex: Int): Int {
    val visible = visiblePageIndices(pageCount, hidden)
    return visible.getOrElse(visibleIndex.coerceIn(0, (visible.size - 1).coerceAtLeast(0))) { 0 }
}

/** The visible slot real page [page] currently sits at, or `null` while it is hidden. */
fun visibleIndexForRealPage(pageCount: Int, hidden: Set<Int>, page: Int): Int? =
    visiblePageIndices(pageCount, hidden).indexOf(page).takeIf { it >= 0 }

fun pinHomeApp(slots: List<String?>, id: String, pinned: Boolean, blocked: Set<Int> = emptySet()): List<String?> {
    if (!pinned) return normalizeHomeSlots(slots.map { if (it == id) null else it })
    if (id in slots) return slots
    val gap = slots.indices.firstOrNull { slots[it] == null && it !in blocked }
    if (gap != null) return slots.toMutableList().apply { set(gap, id) }
    var index = slots.size
    while (index in blocked) index++
    return slots + List(index - slots.size) { null } + id
}

fun migrateSchema5Apps(slots: List<String?>): List<String?> {
    if (slots.isEmpty()) return emptyList()
    // Frozen at the grid's shape when this migration was written (4x6, LEGACY_HOME_CELLS): it
    // centres a legacy 16-shortcut page (offset 8 = (24 - 16) / 2) inside what was then a
    // 24-cell page, and must keep doing exactly that however GRID_ROWS/HOME_CELLS change later.
    val result = MutableList(((slots.lastIndex / 16) + 1) * LEGACY_HOME_CELLS) { null as String? }
    slots.forEachIndexed { index, id -> result[index / 16 * LEGACY_HOME_CELLS + 8 + index % 16] = id }
    return normalizeHomeSlots(result)
}

/**
 * Schema 13 / layout-backup version 5 "Mřížka 4x7 a obsah výš" (2026-09-17 noc): widens every
 * Home page from [LEGACY_HOME_CELLS] (24, back when [GRID_ROWS] was 6) to [HOME_CELLS] (28,
 * GRID_ROWS 7). Rows 0-5 of every page keep their exact slot; the new row 6 starts empty.
 * [flatSlots] need not be a clean multiple of the legacy pitch (a persisted array commonly has its
 * trailing nulls trimmed already) — it is padded out to one first. Pure padding only, no
 * dedup/trim of its own; a caller that wants that still calls [normalizeHomeSlots] itself, same as
 * any other slot list. Widget placements, folders and pairs need no remapping of their own here: a
 * placement's page/row/column are independent ints already valid under seven rows, and a
 * folder/pair is referenced only by the id text sitting inside these very slots, which rides along
 * untouched by a purely positional widen.
 */
internal fun migrateSchema12HomeCells(flatSlots: List<String?>): List<String?> {
    if (flatSlots.isEmpty()) return flatSlots
    val pages = (flatSlots.size + LEGACY_HOME_CELLS - 1) / LEGACY_HOME_CELLS
    val padded = flatSlots + List(pages * LEGACY_HOME_CELLS - flatSlots.size) { null }
    return (0 until pages).flatMap { page ->
        padded.subList(page * LEGACY_HOME_CELLS, (page + 1) * LEGACY_HOME_CELLS) + List(HOME_CELLS - LEGACY_HOME_CELLS) { null }
    }
}

/** Same idea for the fixed-length leading (page -1) array: legacy input is always exactly [LEGACY_HOME_CELLS] long. */
internal fun migrateSchema12LeadingCells(legacyLeadingSlots: List<String?>): List<String?> =
    legacyLeadingSlots.take(LEGACY_HOME_CELLS) + List(HOME_CELLS - LEGACY_HOME_CELLS) { null }

fun migrateSchema5Widgets(widgets: List<Int>): List<WidgetPlacement> = buildList {
    widgets.forEachIndexed { slot, id ->
        if (id == EMPTY_WIDGET) return@forEachIndexed
        val page = slot / 3
        when (slot % 3) {
            0 -> add(WidgetPlacement(slot, id, page, 0, 0, 2, 2))
            1 -> add(WidgetPlacement(slot, id, page, 2, 0, 2, 2))
            2 -> if (page == 0) add(WidgetPlacement(slot, id, -1, 0, 0, 4, 6))
                else add(WidgetPlacement(slot, id, page, 0, 6, 4, 4))
        }
    }
}
