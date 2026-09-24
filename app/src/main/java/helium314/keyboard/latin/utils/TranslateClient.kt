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
    private var lastDeliveredId = 0L

    /**
     * @param text      the text to translate (may be empty; in that case [onResult] is called with
     *                  `null, null` so the caller can clear the previous translation)
     * @param source    source language code, e.g. "en" or "auto"
     * @param target    target language code, e.g. "es"
     * @param endpoint  base URL of the translation server, e.g. "https://libretranslate.de"
     * @param onResult  called on the main thread with `(translatedText, errorMessage)`
     */
    fun translate(
        text: String,
        source: String,
        target: String,
        endpoint: String,
        onResult: (result: String?, error: String?) -> Unit
    ) {
        if (text.isBlank()) {
            mainHandler.post { onResult(null, null) }
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
                    }.toString()

                    conn.outputStream.use { os -> os.write(body.toByteArray(Charsets.UTF_8)) }

                    val code = conn.responseCode
                    val raw = if (code in 200..299) conn.inputStream else conn.errorStream
                    val response = raw?.bufferedReader()?.use { it.readText() }.orEmpty()

                    if (code in 200..299) {
                        val translated = JSONObject(response).optString("translatedText", "")
                        if (myId >= lastDeliveredId) {
                            lastDeliveredId = myId
                            mainHandler.post { onResult(translated, null) }
                        }
                    } else {
                        if (myId >= lastDeliveredId) {
                            lastDeliveredId = myId
                            val msg = runCatching { JSONObject(response).optString("error", "HTTP $code") }.getOrDefault("HTTP $code")
                            mainHandler.post { onResult(null, msg) }
                        }
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                if (myId >= lastDeliveredId) {
                    lastDeliveredId = myId
                    mainHandler.post { onResult(null, e.message ?: e.javaClass.simpleName) }
                }
            }
        }
    }
}
