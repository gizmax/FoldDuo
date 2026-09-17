package cz.pflanzer.foldduo

/*
 * B26 Smart stack: which member a stack shows automatically, by time of day. Pure Kotlin — the
 * caller classifies each member into a StackCategory (built-ins map directly via
 * [StackRotation.categoryForBuiltin]; a host widget's category comes from its provider label,
 * resolved in SmartStack.kt) and supplies a usage score from UsageStats when the permission is
 * granted. Nothing here touches Android, so the whole rule table is JVM-testable.
 *
 * Rotation itself only ever runs while the Today page is not on screen, or right on the resume
 * that brings it back (never while the user is looking at it): SmartStack.kt recomputes
 * [StackRotation.resolveIndex] at those two moments and plays the flip the next time the page is
 * shown, using [stackRotationMayRun] to gate when a recompute is even allowed.
 */

enum class DayPeriod { MORNING, MIDDAY, EVENING, NIGHT }

enum class StackCategory { CLOCK, CALENDAR, WEATHER, PHOTOS, OTHER }

data class StackMember(val id: Int, val category: StackCategory, val usageScore: Int = 0)

/** A manual swipe pins the stack on [memberId] for [STACK_PIN_DURATION_MS] from [pinnedAtMillis]. */
data class StackPin(val memberId: Int, val pinnedAtMillis: Long)

const val STACK_PIN_DURATION_MS = 30 * 60 * 1000L

object StackRotation {
    /** 6-10 Calendar/Reminders first, 10-17 most-used, 17-22 Weather/Photos, 22-6 Clock. */
    fun periodFor(hour: Int): DayPeriod {
        require(hour in 0..23) { "hour must be 0..23" }
        return when (hour) {
            in 6..9 -> DayPeriod.MORNING
            in 10..16 -> DayPeriod.MIDDAY
            in 17..21 -> DayPeriod.EVENING
            else -> DayPeriod.NIGHT
        }
    }

    /** Category of a built-in card; every host widget is [StackCategory.OTHER] unless the caller reclassifies it. */
    fun categoryForBuiltin(id: Int): StackCategory = when (id) {
        CALENDAR_WIDGET -> StackCategory.CALENDAR
        PHOTOS_WIDGET -> StackCategory.PHOTOS
        CLOCK_WIDGET, CLOCK_ANALOG_WIDGET -> StackCategory.CLOCK
        else -> StackCategory.OTHER
    }

    /**
     * The member index this hour's rule prefers, or null to keep whatever is already shown:
     * morning favours the first Calendar member, evening the first Weather or Photos member,
     * night the first Clock member. Midday favours the highest [StackMember.usageScore] while
     * [usageAvailable] — without that permission it returns null instead of guessing, keeping
     * today's existing order. Also null when no member matches the preferred category, or the
     * stack is empty.
     */
    fun preferredIndex(members: List<StackMember>, hour: Int, usageAvailable: Boolean): Int? {
        if (members.isEmpty()) return null
        return when (periodFor(hour)) {
            DayPeriod.MORNING -> members.indexOfFirst { it.category == StackCategory.CALENDAR }.takeIf { it >= 0 }
            DayPeriod.EVENING -> members.indexOfFirst {
                it.category == StackCategory.WEATHER || it.category == StackCategory.PHOTOS
            }.takeIf { it >= 0 }
            DayPeriod.NIGHT -> members.indexOfFirst { it.category == StackCategory.CLOCK }.takeIf { it >= 0 }
            DayPeriod.MIDDAY -> if (usageAvailable) members.indices.maxByOrNull { members[it].usageScore } else null
        }
    }

    /** Whether [pin] is still within its 30-minute window at [now]; false for a null, expired, or future-dated pin. */
    fun isPinned(pin: StackPin?, now: Long, durationMs: Long = STACK_PIN_DURATION_MS): Boolean =
        pin != null && now - pin.pinnedAtMillis in 0 until durationMs

    /**
     * The index Smart Stack would show right now: the manual pin while it still holds, else this
     * hour's [preferredIndex] rule when [smartRotate] is on, else [currentIndex] unchanged (no
     * rule matched, or automatic rotation is off).
     */
    fun resolveIndex(members: List<StackMember>, hour: Int, currentIndex: Int, usageAvailable: Boolean,
        smartRotate: Boolean, pin: StackPin?, now: Long): Int {
        if (members.isEmpty()) return currentIndex
        val bounded = currentIndex.coerceIn(0, members.lastIndex)
        if (isPinned(pin, now)) {
            val pinnedIndex = members.indexOfFirst { it.id == pin!!.memberId }
            if (pinnedIndex >= 0) return pinnedIndex
        }
        if (!smartRotate) return bounded
        return preferredIndex(members, hour, usageAvailable) ?: bounded
    }
}

/**
 * Whether Smart Stack may recompute [StackRotation.resolveIndex] right now: never while the Today
 * page is the one on screen and has been all along, only the instant it stops being visible or
 * the instant the app resumes onto it — so the member is already correct before it is next seen,
 * and the caller can play the flip on that reveal rather than mid-glance.
 */
fun stackRotationMayRun(pageVisible: Boolean, justBecameVisible: Boolean): Boolean =
    !pageVisible || justBecameVisible
