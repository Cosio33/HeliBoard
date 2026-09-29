// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Minimal client for a LibreTranslate-compatible translation API.
 *
 * It performs a `POST {endpoint}/translate` with a JSON body
 * `{ "q": text, "source": source, "target": target, "format": "text" }` and expects a JSON
 * response containing `translatedText`. Network work runs on a background thread, and the result
 * callback is always delivered on the main thread.
 *
 * A monotonic request id is used so that a slow response never overwrites a newer one.
 */
object TranslateClient {

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val requestId = AtomicLong(0)
    @Volatile
    private var lastDeliveredId = 0L

    /**
     * @param text      the text to translate (may be empty; in that case [onResult] is called with
     *                  `null, null` so the caller can clear the previous translation)
     * @param source    source language code, e.g. "en" or "auto"
     * @param target    target language code, e.g. "es"
     * @param endpoint  base URL of the translation server, e.g. "https://libretranslate.de"
     * @param apiKey    optional API key; sent as `api_key` in the JSON body only when non-blank
     * @param onResult  called on the main thread with `(translatedText, detectedLangCode, errorMessage)`;
     *                  `detectedLangCode` is the ISO code of the detected source language when `source`
     *                  is "auto" (e.g. "en"), or null otherwise / on error
     */
    fun translate(
        text: String,
        source: String,
        target: String,
        endpoint: String,
        apiKey: String = "",
        onResult: (result: String?, detectedLang: String?, error: String?) -> Unit
    ) {
        if (text.isBlank()) {
            // Invalidate any request still in flight so its stale result can't repaint the UI
            // after the user cleared the text.
            synchronized(this) { lastDeliveredId = requestId.get() + 1 }
            mainHandler.post { onResult(null, null, null) }
            return
        }
        val myId = requestId.incrementAndGet()
        executor.execute {
            try {
                val base = endpoint.trim().trimEnd('/')
                val url = URL("$base/translate")
                val conn = url.openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.setRequestProperty("Accept", "application/json")
                    conn.doOutput = true
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 15_000

                    val body = JSONObject().apply {
                        put("q", text)
                        put("source", source)
                        put("target", target)
                        put("format", "text")
                        if (apiKey.isNotBlank()) put("api_key", apiKey.trim())
                    }.toString()

                    conn.outputStream.use { os -> os.write(body.toByteArray(Charsets.UTF_8)) }

                    val code = conn.responseCode
                    val raw = if (code in 200..299) conn.inputStream else conn.errorStream
                    val response = raw?.bufferedReader()?.use { it.readText() }.orEmpty()

                    if (code in 200..299) {
                        val json = JSONObject(response)
                        val translated = json.optString("translatedText", "")
                        val detected = json.optJSONObject("detectedLanguage")?.optString("language")?.takeIf { it.isNotBlank() }
                        synchronized(this@TranslateClient) {
                            if (myId >= lastDeliveredId) {
                                lastDeliveredId = myId
                                mainHandler.post { onResult(translated, detected, null) }
                            }
                        }
                    } else {
                        synchronized(this@TranslateClient) {
                            if (myId >= lastDeliveredId) {
                                lastDeliveredId = myId
                                val msg = runCatching { JSONObject(response).optString("error", "HTTP $code") }.getOrDefault("HTTP $code")
                                mainHandler.post { onResult(null, null, msg) }
                            }
                        }
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                synchronized(this@TranslateClient) {
                    if (myId >= lastDeliveredId) {
                        lastDeliveredId = myId
                        mainHandler.post { onResult(null, null, e.message ?: e.javaClass.simpleName) }
                    }
                }
            }
        }
    }
}
