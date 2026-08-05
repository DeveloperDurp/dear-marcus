package com.dearmarcus.export

import com.dearmarcus.data.ReflectionRecord

class JournalBackupCodec {
    fun encode(backup: JournalBackup): JournalBackupDocument {
        val canonicalBackup = JournalBackupContract.canonicalize(backup)
        require(JournalBackupContract.validationFailure(canonicalBackup, requireCanonicalOrder = true) == null) {
            "Journal backup must contain a valid reflection chronology."
        }

        return JournalBackupDocument(
            fileName = JournalBackupContract.fileName(canonicalBackup),
            content = buildString {
                append("{\"format\":")
                appendJsonString(JournalBackupContract.FORMAT)
                append(",\"version\":${JournalBackupContract.VERSION}")
                append(",\"exportedAtEpochMillis\":${canonicalBackup.exportedAt.toEpochMilli()}")
                append(",\"entries\":[")
                canonicalBackup.entries.forEachIndexed { index, entry ->
                    if (index > 0) append(',')
                    appendEntry(entry)
                }
                append("]}")
            },
        )
    }

    fun decode(payload: String): JournalBackupDecodeResult {
        if (payload.isBlank()) return JournalBackupDecodeResult.Failure(JournalBackupDecodeFailure.BlankPayload)

        return when (val parsed = JournalBackupJsonParser().parse(payload)) {
            is JournalBackupDecodeResult.Failure -> parsed
            is JournalBackupDecodeResult.Success -> {
                val failure = JournalBackupContract.validationFailure(parsed.backup, requireCanonicalOrder = true)
                if (failure == null) parsed else JournalBackupDecodeResult.Failure(failure)
            }
        }
    }

    private fun StringBuilder.appendEntry(backupEntry: JournalBackupEntry) {
        val entry = backupEntry.entry
        append('{')
        appendField("id", entry.id)
        appendField("localDateTime", entry.localDateTime.toString())
        appendField("wentWell", entry.wentWell)
        appendField("wentPoorly", entry.wentPoorly)
        appendField("doDifferently", entry.doDifferently)
        append(",\"updatedAtEpochMillis\":${entry.updatedAt.toEpochMilli()},\"reflection\":")
        if (backupEntry.reflection == null) append("null") else appendReflection(backupEntry.reflection)
        append('}')
    }

    private fun StringBuilder.appendReflection(reflection: ReflectionRecord) {
        append('{')
        appendField("entryId", reflection.entryId)
        appendField("feedback", reflection.feedback)
        appendField("memoryBefore", reflection.memoryBefore)
        appendField("memoryAfter", reflection.memoryAfter)
        append(",\"memoryRevision\":${reflection.memoryRevision}")
        append(",\"generatedAtEpochMillis\":${reflection.generatedAt.toEpochMilli()}")
        appendField("aiStatus", reflection.aiStatus)
        append('}')
    }

    private fun StringBuilder.appendField(name: String, value: String) {
        if (last() != '{') append(',')
        appendJsonString(name)
        append(':')
        appendJsonString(value)
    }

    private fun StringBuilder.appendJsonString(value: String) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }
}
