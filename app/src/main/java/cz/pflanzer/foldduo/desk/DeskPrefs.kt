package cz.pflanzer.foldduo.desk

import android.content.Context

/** Settings of B47 "Stůl", one SharedPreferences file ("desk") — mirrors standby/StandByPrefs.kt. */
class DeskPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("desk", Context.MODE_PRIVATE)

    /** "Desk when standing" (StandBy settings page): Off / Desk / StandBy face, Desk by default. */
    var mode: DeskMode
        get() = prefs.getString(KEY_MODE, null)?.let { name -> runCatching { DeskMode.valueOf(name) }.getOrNull() } ?: DeskMode.Desk
        set(value) = prefs.edit().putString(KEY_MODE, value.name).apply()

    var cardOrder: List<DeskCard>
        get() = DeskCardOrder.decode(prefs.getString(KEY_ORDER, null))
        set(value) = prefs.edit().putString(KEY_ORDER, DeskCardOrder.encode(value)).apply()

    var hiddenCards: Set<DeskCard>
        get() = DeskCardOrder.decodeHidden(prefs.getString(KEY_HIDDEN, null))
        set(value) = prefs.edit().putString(KEY_HIDDEN, DeskCardOrder.encodeHidden(value)).apply()

    /** The quick-note card's single multiline field. */
    var noteText: String
        get() = prefs.getString(KEY_NOTE, null).orEmpty()
        set(value) = prefs.edit().putString(KEY_NOTE, value).apply()

    private companion object {
        const val KEY_MODE = "mode"
        const val KEY_ORDER = "card_order"
        const val KEY_HIDDEN = "hidden_cards"
        const val KEY_NOTE = "note_text"
    }
}
