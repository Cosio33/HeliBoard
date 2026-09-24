// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.TranslateLanguages
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.initPreview
import helium314.keyboard.settings.preferences.ListPreference
import helium314.keyboard.settings.preferences.SwitchPreference
import helium314.keyboard.settings.preferences.TextInputPreference
import helium314.keyboard.latin.utils.previewDark

@Composable
fun TranslatorScreen(
    onClickBack: () -> Unit,
) {
    val items = createTranslatorSettings(LocalContext.current)
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.translate_title),
        settings = items
    )
}

fun createTranslatorSettings(context: Context) = listOf(
    Setting(context, Settings.PREF_TRANSLATE_ENABLED, R.string.translate_enable, R.string.translate_enable_summary) {
        SwitchPreference(it, Defaults.PREF_TRANSLATE_ENABLED)
    },
    Setting(context, Settings.PREF_TRANSLATE_ENDPOINT, R.string.translate_endpoint, R.string.translate_endpoint_summary) {
        TextInputPreference(it, Defaults.PREF_TRANSLATE_ENDPOINT, info = context.getString(R.string.translate_endpoint_dialog_msg))
    },
    Setting(context, Settings.PREF_TRANSLATE_SOURCE, R.string.translate_source) {
        ListPreference(it, TranslateLanguages.LANGUAGES.map { l -> l.second to l.first }, Defaults.PREF_TRANSLATE_SOURCE)
    },
    Setting(context, Settings.PREF_TRANSLATE_TARGET, R.string.translate_target) {
        ListPreference(it, TranslateLanguages.LANGUAGES.map { l -> l.second to l.first }, Defaults.PREF_TRANSLATE_TARGET)
    }
)

@Preview
@Composable
private fun Preview() {
    initPreview(LocalContext.current)
    Theme(previewDark) {
        TranslatorScreen { }
    }
}
