package com.example.freetv.domain.model

enum class ImportStage { DOWNLOADING, PARSING, SAVING, DONE }

/** Progress of a playlist import or refresh. [fraction] is null when the size is unknown. */
data class ImportProgress(
    val stage: ImportStage,
    val fraction: Float? = null,
    val detail: String? = null,
)

sealed interface ImportResult {
    data class Success(
        val playlistId: Long,
        val channelCount: Int,
        val newChannels: Int,
        val updatedChannels: Int,
        val removedChannels: Int,
        val duplicatesMerged: Int,
        val invalidEntries: Int,
    ) : ImportResult

    data class DuplicatePlaylist(val existingId: Long, val existingName: String) : ImportResult

    data class Failure(val message: String) : ImportResult
}

sealed interface NumberAssignResult {
    data object Success : NumberAssignResult
    data class Conflict(val otherChannelId: Long, val otherChannelName: String) : NumberAssignResult
    data class Invalid(val reason: String) : NumberAssignResult
}

sealed interface GroupResult {
    data class Success(val groupId: Long) : GroupResult
    data class Error(val message: String) : GroupResult
}

data class StreamTestResult(val ok: Boolean, val message: String)

data class EpgImportResult(val ok: Boolean, val programCount: Int, val channelCount: Int, val message: String)
