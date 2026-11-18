package com.example.freetv.data.parser

import java.util.Locale

/**
 * Pure helpers for channel identity, duplicate detection and automatic
 * language / category assignment.
 */
object ChannelClassifier {

    /**
     * Stable identity of a channel inside one playlist. tvg-id wins when present,
     * so entries with the same tvg-id (e.g. HD + SD feeds) become one channel
     * with several streams; otherwise the normalised name is used.
     */
    fun stableKey(tvgId: String?, name: String): String =
        if (!tvgId.isNullOrBlank()) "id:" + tvgId.trim().lowercase(Locale.ROOT)
        else "name:" + normalizeName(name)

    fun normalizeName(name: String): String =
        name.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()

    /** Lower-case alphanumerics only – used to match EPG display names. */
    fun matchKey(name: String): String =
        name.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    /** Merged channel = entries sharing a stable key. */
    data class MergedChannel(val key: String, val primary: M3uEntry, val streams: List<M3uEntry>)

    /** Groups entries by [stableKey], keeping file order and dropping duplicate URLs. */
    fun merge(entries: List<M3uEntry>): Pair<List<MergedChannel>, Int> {
        val map = LinkedHashMap<String, MutableList<M3uEntry>>()
        var duplicates = 0
        for (entry in entries) {
            val key = stableKey(entry.tvgId, entry.name)
            val list = map.getOrPut(key) { mutableListOf() }
            if (list.isNotEmpty()) duplicates++
            if (list.none { it.url == entry.url }) list += entry
        }
        return map.map { (key, list) -> MergedChannel(key, list.first(), list) } to duplicates
    }

    /**
     * True when a group keyword matches the channel's language, original group or name.
     * Keywords are matched as whole words so "eng" does not match "engine".
     */
    fun matches(groupName: String, keywords: String?, language: String?, originalGroup: String?, name: String): Boolean {
        val words = (keywords.orEmpty().split(',') + groupName)
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.length >= 3 }
            .distinct()
        if (words.isEmpty()) return false
        val haystacks = listOfNotNull(language, originalGroup, name).map { it.lowercase(Locale.ROOT) }
        return words.any { word ->
            haystacks.any { text -> containsWord(text, word) }
        }
    }

    private fun containsWord(text: String, word: String): Boolean {
        var index = text.indexOf(word)
        while (index >= 0) {
            val before = if (index == 0) ' ' else text[index - 1]
            val afterIndex = index + word.length
            val after = if (afterIndex >= text.length) ' ' else text[afterIndex]
            if (!before.isLetterOrDigit() && !after.isLetterOrDigit()) return true
            index = text.indexOf(word, index + 1)
        }
        return false
    }

    /** Language guess used when the playlist has no tvg-language. */
    fun detectLanguage(language: String?, groupTitle: String?, name: String, languageNames: Map<String, String?>): String? {
        if (!language.isNullOrBlank()) return language
        for ((lang, keywords) in languageNames) {
            if (matches(lang, keywords, null, groupTitle, name)) return lang
        }
        return null
    }
}
