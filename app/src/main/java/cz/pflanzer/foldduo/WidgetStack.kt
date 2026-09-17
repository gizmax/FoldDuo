package cz.pflanzer.foldduo

/*
 * B26 "Smart stack na Today" (iOS Smart Stack): several widgets of the same footprint sharing one
 * WidgetPlacement. Pure data model and editing rules only — SmartStack.kt has the Compose UI
 * (flip, page dots, edit sheet) built on top, and StackRotation.kt the time-of-day auto-rotation.
 *
 * A stack is just a WidgetPlacement whose stackMembers holds two or more ids: id is always the
 * member currently shown, stackMembers its full membership in display order. A membership of one
 * (or zero) is not a stack — every function below keeps that invariant, dissolving a stack back
 * to a plain placement (stackMembers empty, id the sole survivor) the instant it would drop to one
 * member, and removing the placement outright if it would drop to none.
 */

/** True when [WidgetPlacement.stackMembers] actually holds a stack (two or more members). */
val WidgetPlacement.isStack: Boolean get() = stackMembers.size >= 2

/** Every member of this placement's stack, in order — or just its own id when it is not a stack. */
fun WidgetPlacement.stackedIds(): List<Int> = if (isStack) stackMembers else listOf(id)

/** Index of the shown member within [stackedIds]; 0 for a non-stack placement or an inconsistent id. */
fun WidgetPlacement.activeStackIndex(): Int = stackedIds().indexOf(id).coerceAtLeast(0)

/**
 * Repairs a placement loaded from storage: a stack whose [WidgetPlacement.id] is not among its
 * own members falls back to showing the first one, a membership of one dissolves to a plain
 * widget, and a membership of zero is left as a plain widget showing whatever [WidgetPlacement.id]
 * already was. Safe to call on an already-consistent placement (a no-op).
 */
fun WidgetPlacement.withSanitizedStack(): WidgetPlacement = when {
    stackMembers.size >= 2 -> if (id in stackMembers) this else copy(id = stackMembers.first())
    stackMembers.isEmpty() -> this
    else -> copy(id = stackMembers.single(), stackMembers = emptyList())
}

/** Two placements can be stacked together: different slots, identical footprint. */
fun canStackTogether(a: WidgetPlacement, b: WidgetPlacement): Boolean =
    a.slot != b.slot && a.spanX == b.spanX && a.spanY == b.spanY

/**
 * The other placement covering grid cell [index] (never [excludeSlot]) — used by the "drop widget
 * onto a widget" stacking gesture: LauncherScreen resolves the drop to a Home cell, so this looks
 * up whichever widget actually occupies it.
 */
fun widgetPlacementAt(layout: HomeLayout, index: Int, excludeSlot: Int): WidgetPlacement? =
    layout.widgetPlacements.firstOrNull { it.slot != excludeSlot && index in it.coveredIndices() }

/**
 * Drags [draggedSlot] onto [targetSlot]. When both placements exist and share a footprint,
 * [targetSlot] gains every member of [draggedSlot] (its own stack, or just its id) that it does
 * not already hold, appended in order, and switches to showing the widget that was just dropped;
 * [draggedSlot]'s own placement is removed, its members now living inside the target's. Returns
 * [layout] unchanged when either slot is missing, they are the same slot, or their spans differ.
 */
fun stackWidgets(layout: HomeLayout, targetSlot: Int, draggedSlot: Int): HomeLayout {
    val target = layout.placement(targetSlot) ?: return layout
    val dragged = layout.placement(draggedSlot) ?: return layout
    if (!canStackTogether(target, dragged)) return layout
    val merged = (target.stackedIds() + dragged.stackedIds()).distinct()
    val updatedTarget = target.copy(id = dragged.id, stackMembers = if (merged.size >= 2) merged else emptyList())
    return layout.copy(widgetPlacements = layout.widgetPlacements
        .filterNot { it.slot == draggedSlot }
        .map { if (it.slot == targetSlot) updatedTarget else it })
}

/**
 * Removes [memberId] from the stack at [slot]. Dropping to one member dissolves the stack (the
 * placement becomes a plain widget showing that member); dropping to zero removes the placement
 * outright. A no-op when [slot] is not a stack or does not hold [memberId].
 */
fun removeStackMember(layout: HomeLayout, slot: Int, memberId: Int): HomeLayout {
    val placement = layout.placement(slot) ?: return layout
    if (!placement.isStack || memberId !in placement.stackMembers) return layout
    val remaining = placement.stackMembers.filterNot { it == memberId }
    if (remaining.isEmpty()) return layout.copy(widgetPlacements = layout.widgetPlacements.filterNot { it.slot == slot })
    val nextId = if (placement.id == memberId) remaining.first() else placement.id
    val next = placement.copy(id = nextId, stackMembers = if (remaining.size >= 2) remaining else emptyList())
    return layout.copy(widgetPlacements = layout.widgetPlacements.map { if (it.slot == slot) next else it })
}

/** Drag-to-reorder in the stack edit sheet. A no-op when [slot] is not a stack, or [from]/[to] are out of range. */
fun reorderStackMember(layout: HomeLayout, slot: Int, from: Int, to: Int): HomeLayout {
    val placement = layout.placement(slot) ?: return layout
    if (!placement.isStack || from !in placement.stackMembers.indices || to !in placement.stackMembers.indices) return layout
    val next = placement.stackMembers.toMutableList().apply { add(to, removeAt(from)) }
    return layout.copy(widgetPlacements = layout.widgetPlacements.map { if (it.slot == slot) it.copy(stackMembers = next) else it })
}

/** Flips to the next ([forward] true) or previous member, wrapping. A no-op on a placement that is not a stack. */
fun cycleStackMember(layout: HomeLayout, slot: Int, forward: Boolean): HomeLayout {
    val placement = layout.placement(slot) ?: return layout
    if (!placement.isStack) return layout
    val members = placement.stackMembers
    val current = members.indexOf(placement.id).coerceAtLeast(0)
    val next = Math.floorMod(current + if (forward) 1 else -1, members.size)
    return layout.copy(widgetPlacements = layout.widgetPlacements.map { if (it.slot == slot) it.copy(id = members[next]) else it })
}

/** Jumps straight to [memberId] — a tapped page dot, or Smart Stack's own rotation. A no-op if it is not a member. */
fun showStackMember(layout: HomeLayout, slot: Int, memberId: Int): HomeLayout {
    val placement = layout.placement(slot) ?: return layout
    if (!placement.isStack || memberId !in placement.stackMembers) return layout
    return layout.copy(widgetPlacements = layout.widgetPlacements.map { if (it.slot == slot) it.copy(id = memberId) else it })
}

/** Toggles Smart Stack's automatic rotation for the stack at [slot]; a no-op when [slot] does not exist. */
fun setStackSmartRotate(layout: HomeLayout, slot: Int, enabled: Boolean): HomeLayout {
    layout.placement(slot) ?: return layout
    return layout.copy(widgetPlacements = layout.widgetPlacements.map { if (it.slot == slot) it.copy(smartRotate = enabled) else it })
}
