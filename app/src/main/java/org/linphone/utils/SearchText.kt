package org.linphone.utils

import java.text.Normalizer

object SearchText {
    // Hebrew points and accents (niqqud and cantillation marks). Punctuation in the same block stays.
    private val hebrewMarks = Regex("[֑-ׇֽֿׁׂׅׄ]")

    // The Yiddish ligatures and the letter pairs that they show
    private val yiddishLigatures = mapOf(
        'װ' to "וו", // װ -> וו
        'ױ' to "וי", // ױ -> וי
        'ײ' to "יי" // ײ -> יי
    )

    /**
     * Changes text to the form that a search compares, as the web client's toSearchText (WI 36515).
     * The search text and the searched values must both go through this function. Hebrew and Yiddish
     * text has more than one Unicode form for the same word: a letter with its points, or one
     * presentation-form character (אַ or U+FB2E), and a Yiddish ligature or two letters (װ or וו).
     * This function removes the points and accents, and changes presentation forms and ligatures to
     * plain letters. The function also changes the text to lower case.
     */
    fun from(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFKD).replace(hebrewMarks, "")
        val letters = StringBuilder(decomposed.length)
        for (c in decomposed) {
            letters.append(yiddishLigatures[c] ?: c)
        }
        return letters.toString().lowercase()
    }
}
