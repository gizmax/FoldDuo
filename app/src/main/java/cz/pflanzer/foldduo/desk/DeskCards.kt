package cz.pflanzer.foldduo.desk

/**
 * B47 "Stůl": the deck's cards, their persisted order and which are hidden. Pure (no Android
 * types), mirrors standby/StandByRules.kt's `FaceOrder` — tolerant decode, `,`-joined encode.
 */
enum class DeskCard { Media, Timer, Note, Calculator, Toggles, Recents }

object DeskCardOrder {
    val DEFAULT: List<DeskCard> = DeskCard.entries

    fun encode(order: List<DeskCard>): String = order.joinToString(",") { it.name }

    /** Tolerant inverse of [encode]: unknown names dropped, duplicates collapsed, any card missing
     * from [text] is appended at the end (in [DEFAULT] order) so a newly added card type always
     * shows up for an existing user instead of silently disappearing. Empty/null -> [DEFAULT]. */
    fun decode(text: String?): List<DeskCard> {
        if (text.isNullOrBlank()) return DEFAULT
        val known = text.split(',').mapNotNull { name ->
            DeskCard.entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
        }.distinct()
        val missing = DEFAULT.filter { it !in known }
        return (known + missing).ifEmpty { DEFAULT }
    }

    fun encodeHidden(hidden: Set<DeskCard>): String = hidden.joinToString(",") { it.name }

    fun decodeHidden(text: String?): Set<DeskCard> {
        if (text.isNullOrBlank()) return emptySet()
        return text.split(',').mapNotNull { name ->
            DeskCard.entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
        }.toSet()
    }

    /** Moves the card at [from] to [to] (both clamped into range), shifting the rest — a plain
     * list reorder, the model behind a drag-to-reorder gesture. No-op for an out-of-range or
     * equal pair. */
    fun move(order: List<DeskCard>, from: Int, to: Int): List<DeskCard> {
        if (order.isEmpty()) return order
        val f = from.coerceIn(0, order.lastIndex)
        val t = to.coerceIn(0, order.lastIndex)
        if (f == t) return order
        val mutable = order.toMutableList()
        val item = mutable.removeAt(f)
        mutable.add(t, item)
        return mutable
    }

    fun hide(hidden: Set<DeskCard>, card: DeskCard): Set<DeskCard> = hidden + card

    fun show(hidden: Set<DeskCard>, card: DeskCard): Set<DeskCard> = hidden - card

    /** What the deck actually renders: [order] with every [hidden] card filtered out, still in order. */
    fun visible(order: List<DeskCard>, hidden: Set<DeskCard>): List<DeskCard> = order.filterNot { it in hidden }
}
