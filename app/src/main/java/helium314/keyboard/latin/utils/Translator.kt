// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import helium314.keyboard.latin.RichInputConnection

/**
 * Delegates the currently selected text (or, if nothing is selected, the text directly before the
 * cursor) to another app via [Intent.ACTION_PROCESS_TEXT], e.g. the system or Google translator.
 *
 * This keeps HeliBoard free of any network permission and works fully offline when the chosen
 * translator app supports it. The user picks the target app from the system chooser.
 */
object Translator {

    fun translate(context: Context, connection: RichInputConnection) {
        val selected = connection.getSelectedText(0)?.toString()
        val text = if (!selected.isNullOrEmpty()) {
            selected
        } else {
            connection.getTextBeforeCursor(1000, 0)?.toString().orEmpty()
        }
        if (text.isEmpty()) return

        val intent = Intent(Intent.ACTION_PROCESS_TEXT).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_PROCESS_TEXT, text)
            // readonly: we only want to view/translate, not let the app write back into the field
            putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(intent, context.getString(R.string.translate))
        try {
            context.startActivity(chooser)
        } catch (_: ActivityNotFoundException) {
            // no app installed that can process text
        }
    }
}
