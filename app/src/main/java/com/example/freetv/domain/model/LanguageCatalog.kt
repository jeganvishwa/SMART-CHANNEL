package com.example.freetv.domain.model

/** A built-in language playlist source and its logical folder. */
data class LanguagePlaylist(
    /** ISO 639-2 code, e.g. "tam". */
    val code: String,
    /** Name of the matching language group, e.g. "Tamil". */
    val groupName: String,
    /** Label shown on the home screen, in the native script. */
    val nativeLabel: String,
    val playlistName: String,
    val url: String,
) {
    /** Logical folder (not a file-system folder). */
    val folder: String get() = "Languages/$groupName"
}

/**
 * The four public language playlists from the iptv-org project.
 * These lists contain publicly available streams collected by that project;
 * FreeTV does not verify that every stream works or is available in every country.
 */
object LanguageCatalog {

    val PLAYLISTS = listOf(
        LanguagePlaylist("tam", "Tamil", "தமிழ் Tamil", "Tamil TV", "https://iptv-org.github.io/iptv/languages/tam.m3u"),
        LanguagePlaylist("eng", "English", "English", "English TV", "https://iptv-org.github.io/iptv/languages/eng.m3u"),
        LanguagePlaylist("hin", "Hindi", "हिन्दी Hindi", "Hindi TV", "https://iptv-org.github.io/iptv/languages/hin.m3u"),
        LanguagePlaylist("mal", "Malayalam", "മലയാളം Malayalam", "Malayalam TV", "https://iptv-org.github.io/iptv/languages/mal.m3u"),
    )

    fun byCode(code: String?): LanguagePlaylist? = PLAYLISTS.firstOrNull { it.code.equals(code, ignoreCase = true) }

    fun byGroupName(name: String?): LanguagePlaylist? = PLAYLISTS.firstOrNull { it.groupName.equals(name, ignoreCase = true) }

    /** Home-screen label for a language group: native script for the built-in languages. */
    fun labelFor(groupName: String): String = byGroupName(groupName)?.nativeLabel ?: groupName

    /** Folder text for UI ("Languages / Tamil"). */
    fun displayFolder(folder: String?): String? = folder?.replace("/", " / ")
}
