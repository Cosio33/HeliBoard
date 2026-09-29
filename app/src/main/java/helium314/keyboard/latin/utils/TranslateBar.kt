// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputConnection
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Colors
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs

/**
 * Integrated translator bar shown above the keyboard while translate mode is active.
 *
 * It reads the source/target language and the API endpoint from [Settings], lets the user change them
 * live, translates the text before the cursor as the user types, and inserts the translation on tap.
 */
class TranslateBar(
    context: Context,
    private val getConnection: () -> InputConnection?
) : LinearLayout(context) {

    companion object {
        const val MAX_SOURCE_CHARS = 4000
    }

    private val prefs = context.prefs()
    private val spinnerSource: Spinner
    private val spinnerTarget: Spinner
    private val textResult: TextView
    private val defaultTextColors: android.content.res.ColorStateList

    private var suppressSpinnerEvents = false
    private var currentSource: String = Defaults.PREF_TRANSLATE_SOURCE
    private var currentTarget: String = Defaults.PREF_TRANSLATE_TARGET
    private var currentEndpoint: String = Defaults.PREF_TRANSLATE_ENDPOINT
    private var currentApiKey: String = Defaults.PREF_TRANSLATE_API_KEY
    private var lastSourceText: String = ""
    private var lastTranslation: String? = null
    private var lastDetectedLang: String? = null
    private lateinit var sourceAdapter: ArrayAdapter<String>

    private val mainHandler = Handler(Looper.getMainLooper())
    private val translateRunnable = Runnable { translateCurrent() }

    init {
        LayoutInflater.from(context).inflate(R.layout.translate_bar, this, true)
        spinnerSource = findViewById(R.id.spinner_source)
        spinnerTarget = findViewById(R.id.spinner_target)
        textResult = findViewById(R.id.text_result)
        defaultTextColors = textResult.textColors

        val displayNames = TranslateLanguages.LANGUAGES.map { it.second }
        // Custom collapsed views so it is obvious which spinner is the source ("Origen: …") and
        // which is the target ("Destino: …"); in auto mode the source also shows what was detected.
        val targetAdapter = object : ArrayAdapter<String>(context, android.R.layout.simple_spinner_item, displayNames) {
            override fun getView(position: Int, convertView: android.view.View?, parent: ViewGroup): android.view.View {
                val view = super.getView(position, convertView, parent) as TextView
                view.ellipsize = android.text.TextUtils.TruncateAt.END
                view.text = context.getString(R.string.translate_target_label, TranslateLanguages.LANGUAGES.getOrNull(position)?.second ?: "")
                return view
            }
        }.also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        sourceAdapter = object : ArrayAdapter<String>(context, android.R.layout.simple_spinner_item, displayNames) {
            override fun getView(position: Int, convertView: android.view.View?, parent: ViewGroup): android.view.View {
                val view = super.getView(position, convertView, parent) as TextView
                view.ellipsize = android.text.TextUtils.TruncateAt.END
                view.text = sourceCollapsedLabel(position)
                return view
            }
        }.also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinnerSource.adapter = sourceAdapter
        spinnerTarget.adapter = targetAdapter

        spinnerSource.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>, v: android.view.View?, pos: Int, id: Long) {
                if (suppressSpinnerEvents) return
                currentSource = TranslateLanguages.LANGUAGES[pos].first
                prefs.edit().putString(Settings.PREF_TRANSLATE_SOURCE, currentSource).apply()
                lastDetectedLang = null
                translateCurrent()
            }
            override fun onNothingSelected(p: AdapterView<*>) {}
        }
        spinnerTarget.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>, v: android.view.View?, pos: Int, id: Long) {
                if (suppressSpinnerEvents) return
                currentTarget = TranslateLanguages.LANGUAGES[pos].first
                prefs.edit().putString(Settings.PREF_TRANSLATE_TARGET, currentTarget).apply()
                translateCurrent()
            }
            override fun onNothingSelected(p: AdapterView<*>) {}
        }

        findViewById<ImageButton>(R.id.btn_swap).setOnClickListener { swapLanguages() }
        findViewById<ImageButton>(R.id.btn_insert).setOnClickListener { insertTranslation() }
        textResult.setOnClickListener { insertTranslation() }
        findViewById<ImageButton>(R.id.btn_close).setOnClickListener { TranslateController.deactivate() }
        findViewById<ImageButton>(R.id.btn_settings).setOnClickListener { showEndpointDialog() }

        // Tint the icons with the theme color used by the native toolbar keys, so they are clearly
        // visible on any keyboard theme (same mechanism as SuggestionStripView.setupKey).
        val colors = Settings.getValues().mColors
        for (id in intArrayOf(R.id.btn_swap, R.id.btn_insert, R.id.btn_close, R.id.btn_settings)) {
            val button = findViewById<ImageButton>(id)
            button.scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            button.adjustViewBounds = false
            colors.setColor(button, ColorType.TOOL_BAR_KEY)
        }

        refreshFromPrefs()
        showPlaceholder()
    }

    /** Called when translate mode turns on/off. */
    fun onActiveChanged(active: Boolean) {
        if (active) {
            refreshFromPrefs()
            translateCurrent()
        } else {
            lastTranslation = null
            showPlaceholder()
        }
    }

    /** Feed the text currently before the cursor into the translator. */
    fun onSourceTextChanged(text: String) {
        lastSourceText = text
        // Debounce so we don't fire a network request on every single keystroke.
        mainHandler.removeCallbacks(translateRunnable)
        if (text.isBlank()) {
            lastTranslation = null
            if (lastDetectedLang != null) {
                lastDetectedLang = null
                refreshSourceLabel()
            }
            showPlaceholder()
            return
        }
        mainHandler.postDelayed(translateRunnable, 400)
    }

    private fun refreshFromPrefs() {
        currentSource = prefs.getString(Settings.PREF_TRANSLATE_SOURCE, Defaults.PREF_TRANSLATE_SOURCE) ?: Defaults.PREF_TRANSLATE_SOURCE
        currentTarget = prefs.getString(Settings.PREF_TRANSLATE_TARGET, Defaults.PREF_TRANSLATE_TARGET) ?: Defaults.PREF_TRANSLATE_TARGET
        currentEndpoint = prefs.getString(Settings.PREF_TRANSLATE_ENDPOINT, Defaults.PREF_TRANSLATE_ENDPOINT) ?: Defaults.PREF_TRANSLATE_ENDPOINT
        currentApiKey = prefs.getString(Settings.PREF_TRANSLATE_API_KEY, Defaults.PREF_TRANSLATE_API_KEY) ?: Defaults.PREF_TRANSLATE_API_KEY
        suppressSpinnerEvents = true
        spinnerSource.setSelection(TranslateLanguages.indexOfCode(currentSource))
        spinnerTarget.setSelection(TranslateLanguages.indexOfCode(currentTarget))
        suppressSpinnerEvents = false
    }

    /** Collapsed label for the source spinner, e.g. "Origen: Español" or "Origen: Detectar (inglés)". */
    private fun sourceCollapsedLabel(position: Int): String {
        val name = TranslateLanguages.LANGUAGES.getOrNull(position)?.second ?: ""
        return if (TranslateLanguages.LANGUAGES.getOrNull(position)?.first == "auto") {
            val detected = lastDetectedLang?.let { TranslateLanguages.displayName(it) }
            if (detected != null) context.getString(R.string.translate_origin_label, "$name ($detected)")
            else context.getString(R.string.translate_origin_label, name)
        } else {
            context.getString(R.string.translate_origin_label, name)
        }
    }

    private fun refreshSourceLabel() {
        sourceAdapter.notifyDataSetChanged()
    }

    private fun translateCurrent() {
        val text = lastSourceText
        if (text.isBlank()) {
            lastTranslation = null
            showPlaceholder()
            return
        }
        TranslateClient.translate(text, currentSource, currentTarget, currentEndpoint, currentApiKey) { result, detected, error ->
            if (error != null) {
                lastTranslation = null
                textResult.text = context.getString(R.string.translate_error, error)
                textResult.setTextColor(Color.RED)
            } else {
                lastTranslation = result
                if (currentSource == "auto" && detected != null && detected != lastDetectedLang) {
                    lastDetectedLang = detected
                    refreshSourceLabel()
                }
                textResult.text = result
                textResult.setTextColor(defaultTextColors)
            }
        }
    }

    private fun swapLanguages() {
        lastDetectedLang = null
        if (currentSource == "auto") {
            // can't have "auto" as target, fall back to English
            currentSource = currentTarget
            currentTarget = "en"
        } else {
            val tmp = currentSource
            currentSource = currentTarget
            currentTarget = if (tmp == "auto") "en" else tmp
        }
        prefs.edit()
            .putString(Settings.PREF_TRANSLATE_SOURCE, currentSource)
            .putString(Settings.PREF_TRANSLATE_TARGET, currentTarget)
            .apply()
        suppressSpinnerEvents = true
        spinnerSource.setSelection(TranslateLanguages.indexOfCode(currentSource))
        spinnerTarget.setSelection(TranslateLanguages.indexOfCode(currentTarget))
        suppressSpinnerEvents = false
        translateCurrent()
    }

    private fun insertTranslation() {
        val translated = lastTranslation ?: return
        val conn = getConnection() ?: return
        val before = conn.getTextBeforeCursor(MAX_SOURCE_CHARS, 0)?.toString().orEmpty()
        conn.beginBatchEdit()
        try {
            conn.finishComposingText()
            val src = lastSourceText.trimEnd()
            if (src.isNotEmpty()) {
                // Replace the source text if it is still right before the cursor (modulo trailing
                // whitespace the editor may have added); otherwise just insert at the cursor.
                val trailingWs = before.length - before.trimEnd().length
                val start = before.length - trailingWs - src.length
                if (start >= 0 && before.substring(start, before.length - trailingWs) == src) {
                    conn.deleteSurroundingText(src.length + trailingWs, 0)
                }
            }
            conn.commitText(translated, 1)
        } finally {
            conn.endBatchEdit()
        }
    }

    private fun showPlaceholder() {
        textResult.text = context.getString(R.string.translate_result_placeholder)
        textResult.setTextColor(defaultTextColors)
    }

    private fun showEndpointDialog() {
        // Simple container: endpoint field + optional API key field.
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 0)
        }
        val edit = EditText(context).apply {
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            hint = context.getString(R.string.translate_endpoint_hint)
            setText(currentEndpoint)
        }
        container.addView(TextView(context).apply {
            text = context.getString(R.string.translate_endpoint_dialog_msg)
            textSize = 13f
        })
        container.addView(edit)
        val editApiKey = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = context.getString(R.string.translate_api_key_hint)
            setText(currentApiKey)
        }
        container.addView(TextView(context).apply {
            text = context.getString(R.string.translate_api_key_dialog_msg)
            textSize = 13f
            setPadding(0, 24, 0, 0)
        })
        container.addView(editApiKey)

        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.translate_endpoint_dialog_title)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val value = edit.text.toString().trim()
                val key = editApiKey.text.toString().trim()
                currentApiKey = key
                prefs.edit().putString(Settings.PREF_TRANSLATE_API_KEY, key).apply()
                if (value.isNotEmpty()) {
                    currentEndpoint = value
                    prefs.edit().putString(Settings.PREF_TRANSLATE_ENDPOINT, value).apply()
                }
                translateCurrent()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        // An InputMethodService is not an Activity, so a plain AlertDialog has no valid
        // window token and crashes with BadTokenException. Attach it to the IME window
        // token using the attached-dialog window type, and add FLAG_ALT_FOCUSABLE_IM so the
        // dialog doesn't steal focus from the keyboard. Mirrors InputMethodPicker.kt.
        val window = dialog.window
        val layoutParams = window?.attributes
        layoutParams?.token = windowToken
        layoutParams?.type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
        window?.attributes = layoutParams
        window?.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        dialog.show()
    }
}
