package com.dearmarcus.export

import org.junit.Assert.assertEquals
import org.junit.Test

class JournalBackupCodecFailureTest {
    private val codec = JournalBackupCodec()

    @Test
    fun rejectsBlankPayload() {
        assertEquals(JournalBackupDecodeFailure.BlankPayload, decodeFailure(" \n\t"))
    }

    @Test
    fun rejectsMalformedJson() {
        assertEquals(JournalBackupDecodeFailure.MalformedJson, decodeFailure("{"))
    }

    @Test
    fun rejectsUnsupportedVersionBeforeAcceptingItsEntries() {
        assertEquals(JournalBackupDecodeFailure.UnsupportedVersion(2), decodeFailure(validPayload.replace("\"version\":1", "\"version\":2")))
    }

    @Test
    fun rejectsMissingRequiredEntryField() {
        assertEquals(
            JournalBackupDecodeFailure.MissingRequiredField("entries[0].wentWell"),
            decodeFailure(validPayload.replace("\"wentWell\":\"well\",", "")),
        )
    }

    @Test
    fun rejectsQuotedTimestampInsteadOfCoercingItToANumber() {
        assertEquals(
            JournalBackupDecodeFailure.InvalidBackup("$.exportedAtEpochMillis"),
            decodeFailure(
                validPayload.replace(
                    "\"exportedAtEpochMillis\":1785587400000",
                    "\"exportedAtEpochMillis\":\"1785587400000\"",
                ),
            ),
        )
    }

    @Test
    fun rejectsUnknownFieldsInsteadOfIgnoringThem() {
        assertEquals(
            JournalBackupDecodeFailure.UnknownField("$", "unexpected"),
            decodeFailure(validPayload.replace("\"entries\":[", "\"unexpected\":true,\"entries\":[")),
        )
    }

    @Test
    fun rejectsReflectionThatCannotBeTheFirstCurrentMemoryRevision() {
        assertEquals(
            JournalBackupDecodeFailure.InvalidReflection("entry-1"),
            decodeFailure(validPayload.replace("\"memoryBefore\":\"\"", "\"memoryBefore\":\"wrong\"")),
        )
    }

    private fun decodeFailure(payload: String): JournalBackupDecodeFailure {
        val result = codec.decode(payload)
        check(result is JournalBackupDecodeResult.Failure)
        return result.failure
    }

    private companion object {
        const val validPayload = """{"format":"dear-marcus.local-backup","version":1,"exportedAtEpochMillis":1785587400000,"entries":[{"id":"entry-1","localDateTime":"2026-08-01T18:30","wentWell":"well","wentPoorly":"poorly","doDifferently":"different","updatedAtEpochMillis":1785609000000,"reflection":{"entryId":"entry-1","feedback":"feedback","memoryBefore":"","memoryAfter":"memory","memoryRevision":1,"generatedAtEpochMillis":1785610800000,"aiStatus":"AVAILABLE"}}]}"""
    }
}
