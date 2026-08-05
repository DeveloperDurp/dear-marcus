package com.dearmarcus.export

import com.dearmarcus.data.JournalEntryRecord
import com.dearmarcus.data.ReflectionRecord
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDateTime
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal class JournalBackupJsonParser {
    fun parse(payload: String): JournalBackupDecodeResult = try {
        val root = JSONObject(payload)
        root.requireOnlyFields("$", ROOT_FIELDS)
        if (root.requireString("$.format") != JournalBackupContract.FORMAT) {
            fail(JournalBackupDecodeFailure.InvalidBackup("$.format"))
        }
        val version = root.requireInt("$.version")
        if (version != JournalBackupContract.VERSION) fail(JournalBackupDecodeFailure.UnsupportedVersion(version))

        val entries = root.requireArray("$.entries").let { array ->
            List(array.length()) { index -> parseEntry(array.requireObject(index, "entries[$index]"), index) }
        }
        JournalBackupDecodeResult.Success(
            JournalBackup(
                exportedAt = Instant.ofEpochMilli(root.requireLong("$.exportedAtEpochMillis")),
                entries = entries,
            ),
        )
    } catch (error: BackupParseException) {
        JournalBackupDecodeResult.Failure(error.failure)
    } catch (_: JSONException) {
        JournalBackupDecodeResult.Failure(JournalBackupDecodeFailure.MalformedJson)
    } catch (_: RuntimeException) {
        JournalBackupDecodeResult.Failure(JournalBackupDecodeFailure.InvalidBackup("$"))
    }

    private fun parseEntry(json: JSONObject, index: Int): JournalBackupEntry {
        val path = "entries[$index]"
        json.requireOnlyFields(path, ENTRY_FIELDS)
        val entry = JournalEntryRecord(
            id = json.requireString("$path.id"),
            localDateTime = json.requireLocalDateTime("$path.localDateTime"),
            wentWell = json.requireString("$path.wentWell"),
            wentPoorly = json.requireString("$path.wentPoorly"),
            doDifferently = json.requireString("$path.doDifferently"),
            updatedAt = Instant.ofEpochMilli(json.requireLong("$path.updatedAtEpochMillis")),
        )
        val reflectionValue = json.requireNullableValue("$path.reflection")
        return JournalBackupEntry(
            entry = entry,
            reflection = when (reflectionValue) {
                JSONObject.NULL -> null
                is JSONObject -> parseReflection(reflectionValue, "$path.reflection")
                else -> fail(JournalBackupDecodeFailure.InvalidBackup("$path.reflection"))
            },
        )
    }

    private fun parseReflection(json: JSONObject, path: String): ReflectionRecord {
        json.requireOnlyFields(path, REFLECTION_FIELDS)
        return ReflectionRecord(
            entryId = json.requireString("$path.entryId"),
            feedback = json.requireString("$path.feedback"),
            memoryBefore = json.requireString("$path.memoryBefore"),
            memoryAfter = json.requireString("$path.memoryAfter"),
            memoryRevision = json.requireInt("$path.memoryRevision"),
            generatedAt = Instant.ofEpochMilli(json.requireLong("$path.generatedAtEpochMillis")),
            aiStatus = json.requireString("$path.aiStatus"),
        )
    }

    private fun JSONObject.requireOnlyFields(path: String, expected: Set<String>) {
        val fields = keys()
        while (fields.hasNext()) {
            val field = fields.next()
            if (field !in expected) fail(JournalBackupDecodeFailure.UnknownField(path, field))
        }
        expected.firstOrNull { !has(it) }?.let { field ->
            fail(JournalBackupDecodeFailure.MissingRequiredField("$path.$field"))
        }
    }

    private fun JSONObject.requireString(path: String): String =
        requireValue(path).let { value ->
            value as? String ?: fail(JournalBackupDecodeFailure.InvalidBackup(path))
        }

    private fun JSONObject.requireLong(path: String): Long = when (val value = requireValue(path)) {
        is Int -> value.toLong()
        is Long -> value
        is BigInteger -> {
            if (value < BigInteger.valueOf(Long.MIN_VALUE) || value > BigInteger.valueOf(Long.MAX_VALUE)) {
                fail(JournalBackupDecodeFailure.InvalidBackup(path))
            }
            value.toLong()
        }
        else -> fail(JournalBackupDecodeFailure.InvalidBackup(path))
    }

    private fun JSONObject.requireInt(path: String): Int = requireLong(path).let { value ->
        value.toInt().takeIf { it.toLong() == value }
            ?: fail(JournalBackupDecodeFailure.InvalidBackup(path))
    }

    private fun JSONObject.requireArray(path: String): JSONArray =
        requireValue(path) as? JSONArray ?: fail(JournalBackupDecodeFailure.InvalidBackup(path))

    private fun JSONObject.requireValue(path: String): Any {
        val value = requireNullableValue(path)
        if (value == JSONObject.NULL) fail(JournalBackupDecodeFailure.InvalidBackup(path))
        return value
    }

    private fun JSONObject.requireNullableValue(path: String): Any {
        val field = path.substringAfterLast('.')
        if (!has(field)) fail(JournalBackupDecodeFailure.MissingRequiredField(path))
        return get(field)
    }

    private fun JSONObject.requireLocalDateTime(path: String): LocalDateTime {
        val serialized = requireString(path)
        val parsed = try {
            LocalDateTime.parse(serialized)
        } catch (_: RuntimeException) {
            fail(JournalBackupDecodeFailure.InvalidBackup(path))
        }
        if (parsed.toString() != serialized) fail(JournalBackupDecodeFailure.InvalidBackup(path))
        return parsed
    }

    private fun JSONArray.requireObject(index: Int, path: String): JSONObject =
        get(index) as? JSONObject ?: fail(JournalBackupDecodeFailure.InvalidBackup(path))

    private companion object {
        val ROOT_FIELDS = setOf("format", "version", "exportedAtEpochMillis", "entries")
        val ENTRY_FIELDS = setOf(
            "id",
            "localDateTime",
            "wentWell",
            "wentPoorly",
            "doDifferently",
            "updatedAtEpochMillis",
            "reflection",
        )
        val REFLECTION_FIELDS = setOf(
            "entryId",
            "feedback",
            "memoryBefore",
            "memoryAfter",
            "memoryRevision",
            "generatedAtEpochMillis",
            "aiStatus",
        )
    }
}

private class BackupParseException(
    val failure: JournalBackupDecodeFailure,
) : RuntimeException()

private fun fail(failure: JournalBackupDecodeFailure): Nothing = throw BackupParseException(failure)
