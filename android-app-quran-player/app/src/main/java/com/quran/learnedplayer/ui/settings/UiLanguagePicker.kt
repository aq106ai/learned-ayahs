package com.quran.learnedplayer.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.quran.learnedplayer.player.PlayerSettings
import com.quran.learnedplayer.ui.i18n.UiStrings
import com.quran.learnedplayer.ui.i18n.localesOf
import com.quran.learnedplayer.ui.i18n.tx
import com.quran.learnedplayer.ui.theme.AccentGreen
import com.quran.learnedplayer.ui.theme.Border
import com.quran.learnedplayer.ui.theme.Panel
import com.quran.learnedplayer.ui.theme.TextMuted

fun uiLanguageTag(code: String) = "ui_language_${code.ifEmpty { "phone" }}"

/**
 * The interface language: the phone's, or one we ship, each named in its own script so it can be
 * found without reading the current one. Applying it recreates the Activity, so every screen —
 * and its layout direction — is rebuilt in the new language (the web app reloads the page).
 */
@Composable
fun UiLanguagePicker(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val choice by PlayerSettings.uiLanguageFlow.collectAsState()
    Row(modifier = modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val options = listOf("" to tx("Phone's language")) + UiStrings.languages.map { it.code to it.nativeName }
        options.forEach { (code, label) ->
            FilterChip(
                selected = choice == code,
                onClick = { if (choice != code) applyUiLanguage(context, code) },
                label = { Text(label) },
                modifier = Modifier.testTag(uiLanguageTag(code)),
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = Border,
                    labelColor = TextMuted,
                    selectedContainerColor = AccentGreen,
                    selectedLabelColor = Panel,
                ),
            )
        }
    }
}

/** Saves [code] ("" = the phone's), loads its strings, and rebuilds the Activity in it. */
fun applyUiLanguage(context: Context, code: String) {
    PlayerSettings.uiLanguage = code
    val app = context.applicationContext
    UiStrings.use(app, UiStrings.resolve(code, localesOf(app.resources.configuration)))
    var c: Context? = context
    while (c is ContextWrapper && c !is Activity) c = c.baseContext
    (c as? Activity)?.recreate()
}
