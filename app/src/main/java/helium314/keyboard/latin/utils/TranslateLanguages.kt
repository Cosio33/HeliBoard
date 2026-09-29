// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

/**
 * Languages supported by the integrated translator. The first element must be "auto" (detect source
 * language), the rest are real language codes accepted by LibreTranslate-compatible servers.
 * Display names are in Spanish to match the app's user-facing language.
 */
object TranslateLanguages {
    val LANGUAGES: List<Pair<String, String>> = listOf(
        "auto" to "Detectar",
        "en" to "Inglés",
        "es" to "Español",
        "fr" to "Francés",
        "de" to "Alemán",
        "it" to "Italiano",
        "pt" to "Portugués",
        "ru" to "Ruso",
        "nl" to "Neerlandés",
        "ja" to "Japonés",
        "zh" to "Chino",
        "ko" to "Coreano",
        "ar" to "Árabe",
        "hi" to "Hindi",
        "tr" to "Turco",
        "pl" to "Polaco",
        "uk" to "Ucraniano",
        "sv" to "Sueco",
        "ca" to "Catalán",
        "el" to "Griego",
        "cs" to "Checo",
        "ro" to "Rumano",
        "hu" to "Húngaro",
        "id" to "Indonesio",
        "vi" to "Vietnamita",
        "th" to "Tailandés",
        "he" to "Hebreo",
        "fa" to "Persa",
    )

    private val byCode = LANGUAGES.toMap()

    fun displayName(code: String): String = byCode[code] ?: code

    fun indexOfCode(code: String): Int = LANGUAGES.indexOfFirst { it.first == code }.let { if (it < 0) 0 else it }
}
