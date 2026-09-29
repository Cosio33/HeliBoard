// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.content.Context
import android.view.View
import android.view.inputmethod.InputConnection
import android.widget.Toast
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs

/**
 * Central state holder for the integrated translator.
 *
 * - [active] is true while translate mode is on (the [TranslateBar] is shown).
 * - [bar] is the currently attached [TranslateBar]; it is (re)assigned every time the keyboard view
 *   is (re)created, so it always points at the live view.
 *
 * Tapping the toolbar translate key calls [toggle]; [onTextChanged] is called from
 * [helium314.keyboard.latin.LatinIME.onUpdateSelection] to feed the typed text into the bar.
 */
object TranslateController {
    var active = false
        private set
    var bar: TranslateBar? = null

    /** Toggle translate mode. Returns the new active state. */
    fun toggle(context: Context): Boolean {
        val enabled = context.prefs().getBoolean(Settings.PREF_TRANSLATE_ENABLED, Defaults.PREF_TRANSLATE_ENABLED)
        if (!enabled) {
            Toast.makeText(context, R.string.translate_enable, Toast.LENGTH_SHORT).show()
            return false
        }
        active = !active
        bar?.let {
            it.visibility = if (active) View.VISIBLE else View.GONE
            it.onActiveChanged(active)
        }
        return active
    }

    fun onTextChanged(connection: InputConnection?) {
        if (!active) return
        val text = connection?.getTextBeforeCursor(TranslateBar.MAX_SOURCE_CHARS, 0)?.toString().orEmpty()
        bar?.onSourceTextChanged(text)
    }

    /** Turn translate mode off and hide the bar. Safe to call from any class. */
    fun deactivate() {
        active = false
        bar?.visibility = View.GONE
    }
}
