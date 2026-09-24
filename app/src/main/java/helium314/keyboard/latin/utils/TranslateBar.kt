// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.LayoutInflater
import android.view.inputmethod.InputConnection
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import helium314.keyboard.latin.R
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
    private var lastSourceText: String = ""
    private var lastTranslation: String? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val translateRunnable = Runnable { translateCurrent() }

    init {
        LayoutInflater.from(context).inflate(R.layout.translate_bar, this, true)
        spinnerSource = findViewById(R.id.spinner_source)
        spinnerTarget = findViewById(R.id.spinner_target)
        textResult = findViewById(R.id.text_result)
        defaultTextColors = textResult.textColors

        val displayNames = TranslateLanguages.LANGUAGES.map { it.second }
        val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, displayNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerSource.adapter = adapter
        spinnerTarget.adapter = adapter

        spinnerSource.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>, v: android.view.View?, pos: Int, id: Long) {
                if (suppressSpinnerEvents) return
                currentSource = TranslateLanguages.LANGUAGES[pos].first
                prefs.edit().putString(Settings.PREF_TRANSLATE_SOURCE, currentSource).apply()
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
            showPlaceholder()
            return
        }
        mainHandler.postDelayed(translateRunnable, 400)
    }

    private fun refreshFromPrefs() {
        currentSource = prefs.getString(Settings.PREF_TRANSLATE_SOURCE, Defaults.PREF_TRANSLATE_SOURCE) ?: Defaults.PREF_TRANSLATE_SOURCE
        currentTarget = prefs.getString(Settings.PREF_TRANSLATE_TARGET, Defaults.PREF_TRANSLATE_TARGET) ?: Defaults.PREF_TRANSLATE_TARGET
        currentEndpoint = prefs.getString(Settings.PREF_TRANSLATE_ENDPOINT, Defaults.PREF_TRANSLATE_ENDPOINT) ?: Defaults.PREF_TRANSLATE_ENDPOINT
        suppressSpinnerEvents = true
        spinnerSource.setSelection(TranslateLanguages.indexOfCode(currentSource))
        spinnerTarget.setSelection(TranslateLanguages.indexOfCode(currentTarget))
        suppressSpinnerEvents = false
    }

    private fun translateCurrent() {
        val text = lastSourceText
        if (text.isBlank()) {
            lastTranslation = null
            showPlaceholder()
            return
        }
        TranslateClient.translate(text, currentSource, currentTarget, currentEndpoint) { result, error ->
            if (error != null) {
                lastTranslation = null
                textResult.text = context.getString(R.string.translate_error, error)
                textResult.setTextColor(Color.RED)
            } else {
                lastTranslation = result
                textResult.text = result
                textResult.setTextColor(defaultTextColors)
            }
        }
    }

    private fun swapLanguages() {
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
            if (lastSourceText.isNotEmpty() && before.endsWith(lastSourceText)) {
                conn.deleteSurroundingText(lastSourceText.length, 0)
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
        val edit = EditText(context).apply {
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setText(currentEndpoint)
            setSelection(currentEndpoint.length)
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.translate_endpoint_dialog_title)
            .setMessage(R.string.translate_endpoint_dialog_msg)
            .setView(edit)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val value = edit.text.toString().trim()
                if (value.isNotEmpty()) {
                    currentEndpoint = value
                    prefs.edit().putString(Settings.PREF_TRANSLATE_ENDPOINT, value).apply()
                    translateCurrent()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
