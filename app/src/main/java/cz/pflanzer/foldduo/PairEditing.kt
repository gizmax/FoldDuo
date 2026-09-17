package cz.pflanzer.foldduo

import java.util.UUID

/**
 * B46 "Dvojice aplikací": pure logic for a Home item holding two apps that launch together in
 * split screen. Modelled after FolderEditing.kt (same reserved-id-prefix trick, same "one
 * shortcut, several member ids" shape), but a pair always has exactly two members with a fixed
 * order — [PairEntry.first] is the app launched on the inner display, then made to occupy the
 * left pane once split screen opens; [PairEntry.second] is launched adjacent to it, into the
 * right pane. "Swap sides" (PairTile.kt's long-press menu) exchanges the two. The Android-facing
 * launch sequence itself lives in PairLaunch.kt; this file only decides what a drag/drop or menu
 * action does to a [HomeLayout].
 */

private const val PAIR_PREFIX = "pair:"

data class PairEntry(val id: String, val first: String, val second: String)

fun newPairId(): String = PAIR_PREFIX + UUID.randomUUID()

/** Loose prefix check: reserves the "pair:" namespace so a real app/profile id can never collide
 * with one, mirroring [isReservedFolderId]. Used everywhere a plain app shortcut is expected. */
fun isReservedPairId(id: String) = id.startsWith(PAIR_PREFIX)

/** Strict check: prefix *and* a well-formed UUID, mirroring [isFolderId] — confirms [id] really
 * is one of this layout's own pair ids, not just something that happens to start with "pair:". */
fun isPairId(id: String) = id.startsWith(PAIR_PREFIX) &&
    runCatching { UUID.fromString(id.removePrefix(PAIR_PREFIX)) }.isSuccess

fun HomeLayout.pair(id: String) = pairs.firstOrNull { it.id == id }

/** True when [id] is an ordinary, placeable app shortcut — neither empty, a folder, nor a pair.
 * The only kind of Home occupant [appOnAppDropOutcome] can turn into a folder or a pair. */
internal fun isPlainAppShortcut(id: String?) = id != null && !isReservedFolderId(id) && !isReservedPairId(id)

private fun HomeLayout.withoutPairShortcut(id: String) = copy(
    slots = slots.map { it?.takeUnless(id::equals) }.dropLastWhile { it == null },
    leadingSlots = leadingSlots.map { it?.takeUnless(id::equals) },
    dock = dock.map { it?.takeUnless(id::equals) },
)

/**
 * Creates [pair] at [targetIndex], mirroring [createFolder]'s contract exactly: the target cell
 * must currently be empty or hold one of the two member apps, neither member may already be a
 * folder or pair member (theirs or anyone else's), and the pair's own id must be fresh. Returns
 * [layout] unchanged on any violation.
 */
fun createPair(layout: HomeLayout, pair: PairEntry, targetIndex: Int): HomeLayout {
    if (!isPairId(pair.id) || layout.indexOfShortcut(pair.id) != null || layout.pair(pair.id) != null ||
        pair.first == pair.second ||
        listOf(pair.first, pair.second).any { it.isBlank() || isReservedFolderId(it) || isReservedPairId(it) } ||
        layout.folders.any { existing -> pair.first in existing.appIds || pair.second in existing.appIds } ||
        layout.pairs.any { existing -> pair.first == existing.first || pair.first == existing.second ||
            pair.second == existing.first || pair.second == existing.second } ||
        homeCellPage(targetIndex) !in -1..layout.pageCount || targetIndex in widgetCellsForPairs(layout)) return layout
    val target = layout.slotAt(targetIndex)
    if (target != null && target != pair.first && target != pair.second) return layout
    var next = layout.withoutPairShortcut(pair.first).withoutPairShortcut(pair.second)
    next = next.withSlot(targetIndex, pair.id)
    return next.copy(pairs = next.pairs + pair)
}

/** Long-press menu "Swap sides": exchanges [PairEntry.first]/[PairEntry.second] in place. */
fun swapPairSides(layout: HomeLayout, pairId: String): HomeLayout {
    if (layout.pair(pairId) == null) return layout
    return layout.copy(pairs = layout.pairs.map { if (it.id == pairId) it.copy(first = it.second, second = it.first) else it })
}

/**
 * Long-press menu "Split into apps": dissolves the pair, putting [PairEntry.first] back at the
 * pair's own cell and [PairEntry.second] into the nearest free cell on the *same* canvas (the
 * leading page if the pair lived there, otherwise the pair's own Home page) — never crossing
 * between the two, which would violate the leading canvas's "unfolded-only" exclusivity invariant
 * ([LauncherModel]'s saved-state schema 8 check). If that canvas is completely full, [second]
 * simply has nowhere to reappear and is dropped (same "best effort" fallback widget placement
 * uses elsewhere) — a known limitation, see STATUS.md.
 */
fun splitPair(layout: HomeLayout, pairId: String): HomeLayout {
    val pair = layout.pair(pairId) ?: return layout
    val cell = layout.indexOfShortcut(pairId) ?: return layout
    val withoutPair = layout.copy(pairs = layout.pairs.filterNot { it.id == pairId }).withSlot(cell, pair.first)
    val vacancy = firstOpenCellOnSameCanvas(withoutPair, cell) ?: return withoutPair
    return dropApp(withoutPair, pair.second, DropTarget.Home(vacancy))
}

/** Long-press menu "Remove": drops the whole pair tile, unpinning both apps from Home (same
 * effect as unpinning them individually) rather than leaving either stranded on the grid. */
fun removePair(layout: HomeLayout, pairId: String): HomeLayout {
    if (layout.pair(pairId) == null) return layout
    return layout.withoutPairShortcut(pairId).copy(pairs = layout.pairs.filterNot { it.id == pairId })
}

/**
 * Uninstalling one member of a pair leaves the other as a plain shortcut at the pair's own cell —
 * the same "last member wins the cell" rule [reconcileFolders] applies to folders. Called from
 * [LauncherModel.refresh] alongside [reconcileFolders].
 */
fun reconcilePairs(layout: HomeLayout, removedAppIds: Set<String>): HomeLayout {
    var next = layout
    removedAppIds.forEach { appId ->
        next.pairs.firstOrNull { appId == it.first || appId == it.second }?.let { pair ->
            val survivor = if (pair.first == appId) pair.second else pair.first
            val cell = next.indexOfShortcut(pair.id)
            next = (cell?.let { next.withSlot(it, survivor) } ?: next).copy(pairs = next.pairs.filterNot { it.id == pair.id })
        }
    }
    // Both members disappearing in the same refresh can promote the second one into the cell
    // before it is removed in turn — the same fix reconcileFolders applies after dissolving a
    // folder down to its last child, for exactly the same reason (iteration order over a Set
    // cannot otherwise be trusted to resurrect nothing).
    return removedAppIds.fold(next) { current, appId -> current.withoutPairShortcut(appId) }
}

private fun firstOpenCellOnSameCanvas(layout: HomeLayout, cell: Int): Int? {
    val page = homeCellPage(cell)
    return if (page == -1) {
        (0 until HOME_CELLS).map { homeCellIndex(-1, it) }.firstOrNull { layout.slotAt(it) == null }
    } else {
        val blocked = layout.widgetPlacements.flatMapTo(mutableSetOf()) { it.coveredIndices() }
        val start = homeCellIndex(page, 0)
        (start until start + HOME_CELLS).firstOrNull { it !in blocked && layout.slotAt(it) == null }
    }
}

private fun widgetCellsForPairs(layout: HomeLayout) = layout.widgetPlacements
    .flatMapTo(mutableSetOf()) { it.coveredIndices() }

// --- B46 drag/drop: hold-to-pair vs. quick-drop-to-folder ------------------------------------

/** How long a drag must dwell on another app tile before release turns it into a Pair instead of
 * a Folder — the task's "HOLDING ≥ 700 ms". */
const val PAIR_HOLD_THRESHOLD_MS = 700L

enum class AppOnAppDropOutcome { FOLDER, PAIR }

/**
 * Dropping app A onto app B: releasing quickly still creates a Folder (today's behaviour);
 * holding the drag over B for at least [thresholdMs] switches the outcome to a Pair. A pure,
 * boundary-tested decision — no Compose/Android types — so the Compose glue (HomeDrag.kt's dwell
 * tracking, LauncherScreen.kt's `finishDrag`) only has to feed it a duration.
 */
fun appOnAppDropOutcome(dwellMs: Long, thresholdMs: Long = PAIR_HOLD_THRESHOLD_MS): AppOnAppDropOutcome =
    if (dwellMs >= thresholdMs) AppOnAppDropOutcome.PAIR else AppOnAppDropOutcome.FOLDER
