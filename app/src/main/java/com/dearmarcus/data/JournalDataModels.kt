package com.dearmarcus.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import java.time.Instant
import java.time.LocalDateTime

data class JournalEntryRecord(
    val id: String,
    val localDateTime: LocalDateTime,
    val wentWell: String,
    val wentPoorly: String,
    val doDifferently: String,
    val updatedAt: Instant,
)

data class ReflectionRecord(
    val entryId: String,
    val feedback: String,
    val memoryBefore: String,
    val memoryAfter: String,
    val memoryRevision: Int,
    val generatedAt: Instant,
    val aiStatus: String = "AVAILABLE",
    val isValid: Boolean = true,
    val invalidationReason: ReflectionInvalidationReason? = null,
)

enum class ReflectionInvalidationReason {
    ENTRY_EDITED,
    ENTRY_DELETED,
    PREDECESSOR_UNRESOLVED,
}

@Entity(tableName = "journal_entries")
data class JournalEntryEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "local_date_time") val localDateTime: String,
    @ColumnInfo(name = "went_well") val wentWell: String,
    @ColumnInfo(name = "went_poorly") val wentPoorly: String,
    @ColumnInfo(name = "do_differently") val doDifferently: String,
    @ColumnInfo(name = "updated_at_epoch_millis") val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "reflections",
    foreignKeys = [
        ForeignKey(
            entity = JournalEntryEntity::class,
            parentColumns = ["id"],
            childColumns = ["entry_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ReflectionEntity(
    @PrimaryKey @ColumnInfo(name = "entry_id") val entryId: String,
    val feedback: String,
    @ColumnInfo(name = "memory_before") val memoryBefore: String,
    @ColumnInfo(name = "memory_after") val memoryAfter: String,
    @ColumnInfo(name = "memory_revision") val memoryRevision: Int,
    @ColumnInfo(name = "generated_at_epoch_millis") val generatedAtEpochMillis: Long,
    @ColumnInfo(name = "ai_status") val aiStatus: String,
    @ColumnInfo(name = "is_valid") val isValid: Boolean,
    @ColumnInfo(name = "invalidation_reason")
    val invalidationReason: ReflectionInvalidationReason?,
)

class ReflectionInvalidationReasonConverter {
    @TypeConverter
    fun toDatabaseValue(reason: ReflectionInvalidationReason?): String? = reason?.name

    @TypeConverter
    fun fromDatabaseValue(value: String?): ReflectionInvalidationReason? =
        value?.let(ReflectionInvalidationReason::valueOf)
}

internal fun JournalEntryRecord.toEntity() = JournalEntryEntity(
    id = id,
    localDateTime = localDateTime.toString(),
    wentWell = wentWell,
    wentPoorly = wentPoorly,
    doDifferently = doDifferently,
    updatedAtEpochMillis = updatedAt.toEpochMilli(),
)

internal fun JournalEntryEntity.toRecord() = JournalEntryRecord(
    id = id,
    localDateTime = LocalDateTime.parse(localDateTime),
    wentWell = wentWell,
    wentPoorly = wentPoorly,
    doDifferently = doDifferently,
    updatedAt = Instant.ofEpochMilli(updatedAtEpochMillis),
)

internal fun ReflectionRecord.toEntity() = ReflectionEntity(
    entryId = entryId,
    feedback = feedback,
    memoryBefore = memoryBefore,
    memoryAfter = memoryAfter,
    memoryRevision = memoryRevision,
    generatedAtEpochMillis = generatedAt.toEpochMilli(),
    aiStatus = aiStatus,
    isValid = isValid,
    invalidationReason = invalidationReason,
)

internal fun ReflectionEntity.toRecord() = ReflectionRecord(
    entryId = entryId,
    feedback = feedback,
    memoryBefore = memoryBefore,
    memoryAfter = memoryAfter,
    memoryRevision = memoryRevision,
    generatedAt = Instant.ofEpochMilli(generatedAtEpochMillis),
    aiStatus = aiStatus,
    isValid = isValid,
    invalidationReason = invalidationReason,
)
