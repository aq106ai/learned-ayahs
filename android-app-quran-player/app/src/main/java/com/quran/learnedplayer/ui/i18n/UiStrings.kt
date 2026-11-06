package com.quran.learnedplayer.ui.i18n

import android.content.Context
import android.content.res.Configuration
import org.json.JSONObject
import java.util.Locale

/**
 * The app's interface language. The web app's `static/js/core/i18n.js` is the same, and both read
 * one table per language generated from `.setup/ui_strings.json` (here `assets/ui_strings_<code>.json`).
 *
 * `tx("Settings")` returns the text in the chosen language, or the English it was given when that
 * language has no entry, so a missing translation never blanks a screen. The English is the key;
 * `{0}`, `{1}` … are filled from the arguments in order.
 *
 * Why not strings.xml: `stringResource` only works inside composition, and much of this app builds
 * its text in click handlers, effects and view models; one shared English-keyed table also keeps
 * the two apps word for word the same. The cost is that Android's own translation tooling doesn't
 * see these strings. `UiStringsTest` checks every `tx("…")` in the code is in every shipped table.
 *
 * The language is applied to the Activity's configuration too ([wrap]), so its layout direction
 * (right to left for Urdu and Arabic) and the system's own dialogs follow it.
 */
object UiStrings {
    data class Language(val code: String, val name: String, val nativeName: String, val rtl: Boolean)

    val languages = listOf(
        Language("en", "English", "English", rtl = false),
        Language("ur", "Urdu", "اردو", rtl = true),
        Language("ar", "Arabic", "العربية", rtl = true),
    )

    @Volatile private var table: Map<String, String> = emptyMap()
    @Volatile var current: String = "en"
        private set

    val isRtl: Boolean get() = languages.firstOrNull { it.code == current }?.rtl == true

    /** [choice] is "" (follow the phone) or a code from [languages]; else the phone's own languages decide. */
    fun resolve(choice: String?, phone: List<Locale>): String {
        val codes = languages.map { it.code }
        if (choice in codes) return choice!!
        return phone.map { it.language }.firstOrNull { it in codes } ?: "en"
    }

    /** Loads the table for [code] (call when the app starts and when the choice changes). */
    fun use(context: Context, code: String) {
        current = code
        table = if (code == "en") {
            emptyMap()
        } else {
            runCatching {
                val json = JSONObject(context.assets.open("ui_strings_$code.json").bufferedReader().use { it.readText() })
                json.keys().asSequence().associateWith { json.getString(it) }
            }.getOrDefault(emptyMap())
        }
    }

    fun lookup(key: String): String = table[key] ?: key

    /** [base] with the chosen language's locale, so layout direction and system text follow it. */
    fun wrap(base: Context): Context {
        val locale = Locale.forLanguageTag(current)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }
}

/** The phone's languages, most preferred first. */
fun localesOf(config: Configuration): List<Locale> {
    val list = config.locales
    return List(list.size()) { list[it] }
}

/** The interface text for [key] (its English) in the chosen language; `{0}`… from [args]. */
fun tx(key: String, vararg args: Any?): String {
    var s = UiStrings.lookup(key)
    args.forEachIndexed { i, a -> s = s.replace("{$i}", a.toString()) }
    return s
}
