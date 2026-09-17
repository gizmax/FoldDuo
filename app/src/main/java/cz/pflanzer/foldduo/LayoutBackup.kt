package cz.pflanzer.foldduo

import android.content.ComponentName
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

const val LAYOUT_BACKUP_VERSION = 5
const val MAX_LAYOUT_BACKUP_BYTES = 2 * 1024 * 1024
private const val MAX_BACKUP_HOME_CELLS = HOME_CELLS * 100

data class BackupWidgetDescriptor(
    val slot: Int,
    val providerComponent: String?,
    val userSerial: Long?,
    val title: String,
    val profileLabel: String,
    val builtinId: Int? = null,
    val isWork: Boolean = false,
)

data class LayoutImportPreview(
    val layout: HomeLayout,
    val missingApps: List<String>,
    val profileIssues: List<String>,
    val appCount: Int,
    val folderCount: Int,
    val widgetCount: Int,
    val compact: LayoutPreset,
    val expanded: LayoutPreset,
    val labels: Boolean,
    val googleSearch: Boolean,
    val verticalStatus: Boolean,
    /** B46 "Dvojice aplikací": count of pairs that survived the import (both members installed). */
    val pairCount: Int = 0,
)

fun layoutBackupScope(context: Context): String {
    val prefs = context.getSharedPreferences("layout_backup_identity", Context.MODE_PRIVATE)
    return prefs.getString("scope", null) ?: java.util.UUID.randomUUID().toString().also { prefs.edit().putString("scope", it).apply() }
}

fun encodeLayoutBackup(state: LauncherState, widgetDescriptors: List<BackupWidgetDescriptor>, sourceScope: String): String {
    require(sourceScope.isNotBlank())
    require(state.leadingSlots.size == HOME_CELLS) { "Unfolded-only page must contain exactly $HOME_CELLS cells" }
    val descriptorBySlot = widgetDescriptors.associateBy(BackupWidgetDescriptor::slot)
    val apps = JSONArray().also { array -> state.apps.forEach { app -> array.put(JSONObject()
        .put("id", app.id).put("label", app.label).put("component", app.component.flattenToString())
        .put("userSerial", app.userSerial).put("profileLabel", app.profileLabel).put("work", app.isWork)) } }
    val folders = JSONArray().also { array -> state.folders.forEach { folder -> array.put(JSONObject()
        .put("id", folder.id).put("title", folder.title).put("apps", JSONArray(folder.appIds))) } }
    val pairs = JSONArray().also { array -> state.pairs.forEach { pair -> array.put(JSONObject()
        .put("id", pair.id).put("first", pair.first).put("second", pair.second)) } }
    val widgets = JSONArray().also { array -> state.widgetPlacements.forEach { placement ->
        val saved = state.widgetRestores.firstOrNull { it.slot == placement.slot }
        val descriptor = descriptorBySlot[placement.slot]
        val item = JSONObject().put("slot", placement.slot).put("page", placement.page)
            .put("column", placement.column).put("row", placement.row).put("spanX", placement.spanX).put("spanY", placement.spanY)
            .putWidgetAppearance(placement)
        // B26 Smart stack: a stack persists across the export/import boundary only when every
        // member is a built-in card (ids are stable across devices); a stack holding a bound
        // third-party widget flattens to just its currently shown member — re-adding the other
        // apps after restore isn't automated, since a slot can only carry one pending rebind.
        if (placement.isStack && placement.stackMembers.all(::isBuiltinWidgetId)) item.put("stack", JSONArray(placement.stackMembers))
        if (!placement.smartRotate) item.put("smartRotate", false)
        when {
            isBuiltinWidgetId(placement.id) -> item.put("builtinId", placement.id)
            saved != null -> item.put("provider", saved.providerComponent).put("userSerial", saved.userSerial)
                .put("title", saved.title).put("profileLabel", saved.profileLabel).put("work", saved.isWork)
                .put("sourceScope", exportedWidgetScope(saved, sourceScope))
            descriptor?.providerComponent != null && descriptor.userSerial != null -> item.put("provider", descriptor.providerComponent)
                .put("userSerial", descriptor.userSerial).put("title", descriptor.title).put("profileLabel", descriptor.profileLabel)
                .put("work", descriptor.isWork).put("sourceScope", sourceScope)
            else -> error("Widget ${placement.slot} has no portable provider descriptor")
        }
        array.put(item)
    } }
    fun preset(value: LayoutPreset) = JSONObject().put("iconSize", value.iconSize).put("rowGap", value.rowGap)
        .put("dockWidth", value.dockWidth).put("dockPosition", value.dockPosition).put("dockAlignToGrid", value.dockAlignToGrid)
    return JSONObject().put("version", LAYOUT_BACKUP_VERSION).put("sourceScope", sourceScope).put("apps", apps)
        .put("homeSlots", JSONArray(state.homeSlots)).put("leadingSlots", JSONArray(state.leadingSlots))
        .put("dock", JSONArray(state.dock)).put("folders", folders).put("pairs", pairs).put("widgets", widgets)
        .put("labels", state.labels).put("googleSearch", state.googleSearch).put("verticalStatus", state.verticalStatus)
        .put("pageCount", state.explicitPageCount)
        .put("compact", preset(state.compact)).put("expanded", preset(state.expanded)).toString(2)
}

internal fun exportedWidgetScope(restore: WidgetRestore, currentScope: String) = restore.sourceScope ?: currentScope

fun decodeLayoutBackup(raw: String, currentApps: List<AppEntry>, currentProfiles: List<AppProfile>, currentScope: String): LayoutImportPreview {
    require(raw.toByteArray(Charsets.UTF_8).size <= MAX_LAYOUT_BACKUP_BYTES) { "Layout backup is larger than 2 MB" }
    val root = JSONObject(raw)
    val version = root.strictInt("version")
    require(version in 1..LAYOUT_BACKUP_VERSION) { "Unsupported layout backup version" }
    val sourceScope = root.getString("sourceScope").also { require(it.isNotBlank()) }
    val sameScope = sourceScope == currentScope
    val appMetadata = root.getJSONArray("apps").let { array -> List(array.length()) { index ->
        val item = array.getJSONObject(index)
        val id = item.getString("id")
        val identity = parseProfileAppId(id) ?: error("Invalid app identity")
        require(item.getString("component") == identity.component)
        require(ComponentName.unflattenFromString(identity.component) != null)
        val serial = item.strictLong("userSerial")
        require(serial >= 0 && item.getString("label").isNotBlank() && item.getString("profileLabel").isNotBlank())
        if (item.strictBoolean("work")) require(identity.userSerial == serial) else require(identity.userSerial == null)
        id to item.getString("label")
    } }.also { entries -> require(entries.map { it.first }.distinct().size == entries.size) }.toMap()
    val available = currentApps.filter { !it.isWork || sameScope }.mapTo(mutableSetOf(), AppEntry::id)
    val missing = linkedSetOf<String>()
    fun importedApp(id: String?): String? {
        if (id == null) return null
        require(id in appMetadata) { "Layout references an app without metadata" }
        return id.takeIf { it in available } ?: run { missing += "$id (${appMetadata.getValue(id)})"; null }
    }
    val slotsArray = root.getJSONArray("homeSlots")
    require(slotsArray.length() <= MAX_BACKUP_HOME_CELLS)
    val parsedSlots = List(slotsArray.length()) { index -> if (slotsArray.isNull(index)) null else slotsArray.getString(index) }
    // Backup version 5 "Mřížka 4x7 a obsah výš" (2026-09-17 noc): versions below 5 wrote 24-cell
    // pages (GRID_ROWS was 6); widen each to 28, rows 0-5 unchanged, the new row 6 empty. Purely
    // positional, so it runs before anything below reads these arrays by index/homeCellIndex.
    val rawSlots = if (version < 5) migrateSchema12HomeCells(parsedSlots) else parsedSlots
    val rawLeadingSlots = if (version == 1) List(HOME_CELLS) { null } else {
        val array = root.getJSONArray("leadingSlots")
        if (version >= 5) {
            require(array.length() == HOME_CELLS) { "Unfolded-only page must contain exactly $HOME_CELLS cells" }
            List(HOME_CELLS) { index -> if (array.isNull(index)) null else array.getString(index) }
        } else {
            require(array.length() == LEGACY_HOME_CELLS) { "Unfolded-only page must contain exactly $LEGACY_HOME_CELLS cells" }
            migrateSchema12LeadingCells(List(LEGACY_HOME_CELLS) { index -> if (array.isNull(index)) null else array.getString(index) })
        }
    }
    val folderArray = root.getJSONArray("folders")
    val importedFolders = List(folderArray.length()) { index ->
        val item = folderArray.getJSONObject(index)
        val children = item.getJSONArray("apps")
        FolderEntry(item.getString("id"), item.getString("title"), List(children.length()) { children.getString(it) })
    }
    require(importedFolders.map(FolderEntry::id).distinct().size == importedFolders.size)
    require(importedFolders.flatMap(FolderEntry::appIds).distinct().size == importedFolders.sumOf { it.appIds.size })
    importedFolders.forEach { folder ->
        require(isFolderId(folder.id) && folder.title.isNotBlank() && folder.appIds.size >= 2)
        require(folder.appIds.none(::isReservedFolderId))
        require((rawSlots + rawLeadingSlots).count(folder.id::equals) == 1)
    }
    // B46 "Dvojice aplikací": absent (version < 4) migrates to no pairs at all.
    val pairArray = root.optJSONArray("pairs") ?: JSONArray()
    val importedPairs = List(pairArray.length()) { index ->
        val item = pairArray.getJSONObject(index)
        PairEntry(item.getString("id"), item.getString("first"), item.getString("second"))
    }
    require(importedPairs.map(PairEntry::id).distinct().size == importedPairs.size)
    require(importedPairs.flatMap { listOf(it.first, it.second) }.distinct().size == importedPairs.size * 2)
    importedPairs.forEach { pair ->
        require(isPairId(pair.id) && pair.first.isNotBlank() && pair.second.isNotBlank() && pair.first != pair.second)
        require(!isReservedFolderId(pair.first) && !isReservedFolderId(pair.second) &&
            !isReservedPairId(pair.first) && !isReservedPairId(pair.second))
        require((rawSlots + rawLeadingSlots).count(pair.id::equals) == 1)
    }
    val dockArray = root.getJSONArray("dock")
    require(dockArray.length() == 4)
    val rawDock = List(4) { index -> if (dockArray.isNull(index)) null else dockArray.getString(index) }
    val surfaceApps = (rawSlots + rawLeadingSlots).filterNotNull().filterNot(::isReservedFolderId).filterNot(::isReservedPairId) +
        rawDock.filterNotNull() + importedFolders.flatMap(FolderEntry::appIds) + importedPairs.flatMap { listOf(it.first, it.second) }
    require(surfaceApps.distinct().size == surfaceApps.size) { "An app shortcut appears more than once" }
    val folderResults = importedFolders.associate { folder -> folder.id to folder.copy(appIds = folder.appIds.mapNotNull(::importedApp)) }
    val folders = folderResults.values.filter { it.appIds.size >= 2 }
    // A pair with both members installed survives as-is; with exactly one, that one becomes a
    // plain shortcut at the pair's cell (mirrors a folder dissolving to its last member); with
    // neither, the cell just goes empty.
    val pairResults = importedPairs.associate { pair ->
        val first = importedApp(pair.first)
        val second = importedApp(pair.second)
        pair.id to when {
            first != null && second != null -> pair.id to pair
            first != null -> first to null
            second != null -> second to null
            else -> null to null
        }
    }
    val pairs = pairResults.values.mapNotNull { it.second }
    val slots = rawSlots.map { value -> when {
        value == null -> null
        isReservedFolderId(value) -> folderResults[value]?.let { folder -> when (folder.appIds.size) { 0 -> null; 1 -> folder.appIds.single(); else -> folder.id } }
            ?: error("Orphan folder reference")
        isReservedPairId(value) -> pairResults[value]?.first ?: error("Orphan pair reference")
        else -> importedApp(value)
    } }
    val leadingSlots = rawLeadingSlots.map { value -> when {
        value == null -> null
        isReservedFolderId(value) -> folderResults[value]?.let { folder -> when (folder.appIds.size) { 0 -> null; 1 -> folder.appIds.single(); else -> folder.id } }
            ?: error("Orphan folder reference")
        isReservedPairId(value) -> pairResults[value]?.first ?: error("Orphan pair reference")
        else -> importedApp(value)
    } }
    val dock = rawDock.map { value -> value?.also { require(!isReservedFolderId(it) && !isReservedPairId(it)) }?.let(::importedApp) }
    // Absent (backups saved before pageCount existed) defaults to 1, which HomeLayout.pageCount
    // always folds into its derived minimum, matching the layout it would have had before.
    val explicitPageCount = root.optInt("pageCount", 1).coerceIn(1, MAX_BACKUP_HOME_CELLS / HOME_CELLS)
    var layout = HomeLayout(slots.dropLastWhile { it == null }, dock, folders = folders, leadingSlots = leadingSlots,
        explicitPageCount = explicitPageCount, pairs = pairs)
    val profileSerials = currentProfiles.mapTo(mutableSetOf(), AppProfile::userSerial)
    val profileIssues = linkedSetOf<String>()
    val widgetArray = root.getJSONArray("widgets")
    require(widgetArray.length() <= 500)
    val widgetSlots = mutableSetOf<Int>()
    repeat(widgetArray.length()) { index ->
        val item = widgetArray.getJSONObject(index)
        val slot = item.strictInt("slot")
        require(widgetSlots.add(slot)) { "Widget slots must be unique" }
        val builtin = if (item.has("builtinId")) item.strictInt("builtinId") else null
        val provider = item.optString("provider").takeIf(String::isNotBlank)
        val id = if (builtin != null) {
            require(isBuiltinWidgetId(builtin)) { "Unknown built-in widget $builtin" }; builtin
        } else NEEDS_BINDING_WIDGET
        val stackMembers = item.widgetStackMembers().also { members ->
            require(members.all(::isBuiltinWidgetId)) { "Backup stack members must be built-in widgets" }
        }
        val placement = WidgetPlacement(slot, id, item.strictInt("page"), item.strictInt("column"), item.strictInt("row"),
            item.strictInt("spanX"), item.strictInt("spanY"), item.widgetAppearance(), item.widgetTint(),
            stackMembers, item.widgetSmartRotate()).withSanitizedStack()
        require(validBackupPlacement(placement) && layout.widgetPlacements.none { backupOverlaps(it, placement) })
        require(placement.coveredIndices().none { layout.slotAt(it) != null })
        val restore = if (id == NEEDS_BINDING_WIDGET) {
            require(provider != null && ComponentName.unflattenFromString(provider) != null)
            val savedSerial = item.strictLong("userSerial"); require(savedSerial >= 0)
            val title = item.getString("title"); val profileLabel = item.getString("profileLabel")
            require(title.isNotBlank() && profileLabel.isNotBlank())
            val work = item.strictBoolean("work")
            val widgetScope = item.optString("sourceScope").takeIf { it.isNotBlank() } ?: sourceScope
            val serial = if (work) savedSerial else currentProfiles.firstOrNull { it.isPersonal }?.userSerial ?: savedSerial
            if ((work && widgetScope != currentScope) || serial !in profileSerials) profileIssues += "$title ($profileLabel profile requires explicit mapping)"
            WidgetRestore(slot, provider, serial, title, profileLabel, work, widgetScope)
        } else null
        layout = layout.copy(widgetPlacements = (layout.widgetPlacements + placement).sortedBy { it.slot },
            widgetRestores = layout.widgetRestores + listOfNotNull(restore))
    }
    fun preset(key: String): LayoutPreset {
        val item = root.getJSONObject(key)
        val loaded = LayoutPreset(item.strictFloat("iconSize"), item.strictFloat("rowGap"),
            item.strictFloat("dockWidth"), item.strictFloat("dockPosition"), item.strictBoolean("dockAlignToGrid"))
        require(loaded == loaded.sanitized()) { "Invalid layout preset" }
        return loaded
    }
    // Validate settings eagerly even though HomeLayout contains placement data only.
    val compact = preset("compact"); val expanded = preset("expanded")
    val labels = root.strictBoolean("labels"); val googleSearch = root.strictBoolean("googleSearch")
    val verticalStatus = root.strictBoolean("verticalStatus")
    return LayoutImportPreview(layout, missing.toList(), profileIssues.toList(),
        appCount = (slots + leadingSlots).count { it != null && !isReservedFolderId(it) && !isReservedPairId(it) } +
            dock.count { it != null } + folders.sumOf { it.appIds.size } + pairs.size * 2,
        folderCount = folders.size, widgetCount = layout.widgetPlacements.size,
        compact = compact, expanded = expanded, labels = labels, googleSearch = googleSearch, verticalStatus = verticalStatus,
        pairCount = pairs.size)
}

/**
 * Appearance fields of a placement, shared by the saved state and the backup file: "appearance"
 * is the enum name, "tint" the ARGB colour and only written when set. Both are optional on read
 * (older files have neither) and an unknown appearance falls back to Auto.
 */
internal fun JSONObject.putWidgetAppearance(placement: WidgetPlacement): JSONObject {
    put("appearance", placement.appearance.name)
    placement.tint?.let { put("tint", it) }
    return this
}

internal fun JSONObject.widgetAppearance(): WidgetAppearance =
    parseWidgetAppearance(if (has("appearance") && !isNull("appearance")) optString("appearance") else null)

internal fun JSONObject.widgetTint(): Int? = if (has("tint") && !isNull("tint")) strictInt("tint") else null

/**
 * B26 Smart stack fields of a placement, shared by the saved state and the backup file: "stack"
 * is the ordered member ids (present only while the placement actually is a stack) and
 * "smartRotate" the automatic-rotation toggle (written only when off; missing means on). Both are
 * optional on read, so a layout saved before this feature existed keeps exactly the placement it
 * had before: no stack, rotation on.
 */
internal fun JSONObject.putWidgetStack(placement: WidgetPlacement): JSONObject {
    if (placement.isStack) put("stack", JSONArray(placement.stackMembers))
    if (!placement.smartRotate) put("smartRotate", false)
    return this
}

internal fun JSONObject.widgetStackMembers(): List<Int> {
    val array = optJSONArray("stack") ?: return emptyList()
    return List(array.length()) { array.getInt(it) }
}

internal fun JSONObject.widgetSmartRotate(): Boolean = optBoolean("smartRotate", true)

internal fun validBackupPlacement(value: WidgetPlacement): Boolean {
    val base = value.slot in 0..10_000 && value.page in -1..99 && value.column >= 0 && value.row >= 0 &&
        value.spanX in 1..GRID_COLUMNS && value.spanY in 1..GRID_ROWS && value.column + value.spanX <= GRID_COLUMNS
    val inside = value.row + value.spanY <= GRID_ROWS
    val overflow = value.page > 0 && value.slot / 3 == value.page && value.slot % 3 == 2 && value.column == 0 &&
        value.row == LEGACY_FULL_WIDTH_OVERFLOW_ROW && value.spanX == GRID_COLUMNS && value.spanY == 4
    return base && (inside || overflow)
}

private fun backupOverlaps(a: WidgetPlacement, b: WidgetPlacement) = a.page == b.page &&
    a.column < b.column + b.spanX && b.column < a.column + a.spanX &&
    a.row < b.row + b.spanY && b.row < a.row + a.spanY

private fun JSONObject.strictInt(key: String): Int {
    val number = get(key) as? Number ?: error("$key must be an integer")
    val value = number.toDouble()
    require(value.isFinite() && value % 1.0 == 0.0 && value in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble())
    return value.toInt()
}

private fun JSONObject.strictLong(key: String): Long {
    val number = get(key) as? Number ?: error("$key must be an integer")
    val text = number.toString()
    return text.toLongOrNull()?.takeIf { it >= 0 } ?: error("$key must be a non-negative integer")
}

private fun JSONObject.strictFloat(key: String): Float {
    val number = get(key) as? Number ?: error("$key must be a number")
    return number.toFloat().takeIf(Float::isFinite) ?: error("$key must be finite")
}

private fun JSONObject.strictBoolean(key: String) = get(key) as? Boolean ?: error("$key must be a boolean")
