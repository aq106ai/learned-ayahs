package com.quran.learnedplayer.data

import com.quran.learnedplayer.ui.i18n.tx

/** The app's colour theme. Both are green; [DARK] is the original look and the default. */
enum class AppTheme {
    DARK,
    LIGHT,
    ;

    val label: String
        get() = when (this) {
            DARK -> tx("Dark")
            LIGHT -> tx("Light")
        }

    companion object {
        val DEFAULT = DARK

        fun fromKey(key: String?): AppTheme = entries.firstOrNull { it.name == key } ?: DEFAULT
    }
}
