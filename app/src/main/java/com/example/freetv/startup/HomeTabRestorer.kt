package com.example.freetv.startup

import com.example.freetv.domain.model.StartupLanguageMode

/** A home-screen tab as seen by the restore rules. */
data class TabCandidate(val key: String, val title: String, val isLanguage: Boolean, val groupName: String?)

data class TabChoice(val key: String, val message: String?)

/**
 * Chooses the home tab to open: the last group, the last language, the default
 * language, then Tamil (or the first language). Pure, so it is unit-tested.
 */
object HomeTabRestorer {

    const val FALLBACK_LANGUAGE = "Tamil"

    fun choose(
        tabs: List<TabCandidate>,
        mode: StartupLanguageMode,
        defaultLanguageTab: String?,
        restoreLanguage: Boolean,
        restoreGroup: Boolean,
        lastTab: String?,
        lastLanguageTab: String?,
        allTabKey: String,
    ): TabChoice {
        val byKey = tabs.associateBy { it.key }
        val lastGroup = lastTab?.takeIf { restoreGroup && it in byKey && byKey[it]?.isLanguage == false }
        val rememberedLanguage = lastLanguageTab ?: lastTab?.takeIf { tabs.any { t -> t.key == it && t.isLanguage } }
        val lastLanguage = rememberedLanguage?.takeIf { restoreLanguage && byKey[it]?.isLanguage == true }
        val default = defaultLanguageTab?.takeIf { it in byKey }

        val chosen = when (mode) {
            StartupLanguageMode.DEFAULT_LANGUAGE -> default ?: lastGroup ?: lastLanguage
            StartupLanguageMode.LAST_SELECTED -> lastGroup ?: lastLanguage ?: default
        }
        if (chosen != null) return TabChoice(chosen, null)

        val fallback = tabs.firstOrNull { it.isLanguage && it.groupName.equals(FALLBACK_LANGUAGE, ignoreCase = true) }
            ?: tabs.firstOrNull { it.isLanguage }
        val key = fallback?.key ?: allTabKey
        val languageMissing = restoreLanguage && rememberedLanguage != null && rememberedLanguage !in byKey
        val message = if (languageMissing) {
            "Your saved language is not available. Showing ${fallback?.title ?: "all channels"}."
        } else {
            null
        }
        return TabChoice(key, message)
    }
}
