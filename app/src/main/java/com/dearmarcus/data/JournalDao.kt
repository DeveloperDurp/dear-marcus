package com.dearmarcus.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface JournalEntryDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: JournalEntryEntity): Long

    @Query("SELECT * FROM journal_entries WHERE id = :id")
    suspend fun findById(id: String): JournalEntryEntity?

    @Query("SELECT * FROM journal_entries ORDER BY local_date_time ASC, id ASC")
    suspend fun entriesOldestFirst(): List<JournalEntryEntity>

    @Query(
        """
        UPDATE journal_entries
        SET went_well = :wentWell,
            went_poorly = :wentPoorly,
            do_differently = :doDifferently,
            updated_at_epoch_millis = :updatedAtEpochMillis
        WHERE id = :id
        """,
    )
    suspend fun updateAnswers(
        id: String,
        wentWell: String,
        wentPoorly: String,
        doDifferently: String,
        updatedAtEpochMillis: Long,
    ): Int

    @Query("DELETE FROM journal_entries WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM journal_entries")
    suspend fun deleteAll()
}

@Dao
interface ReflectionDao {
    @Upsert
    suspend fun upsert(reflection: ReflectionEntity)

    @Query("SELECT * FROM reflections WHERE entry_id = :entryId")
    suspend fun findByEntryId(entryId: String): ReflectionEntity?

    @Query(
        """
        SELECT reflections.*
        FROM reflections
        INNER JOIN journal_entries ON journal_entries.id = reflections.entry_id
        WHERE reflections.is_valid = 1
          AND NOT EXISTS (
              SELECT 1
              FROM journal_entries AS earlier_entries
              LEFT JOIN reflections AS earlier_reflections
                  ON earlier_reflections.entry_id = earlier_entries.id
              WHERE (
                  earlier_entries.local_date_time < journal_entries.local_date_time
                  OR (
                      earlier_entries.local_date_time = journal_entries.local_date_time
                      AND earlier_entries.id < journal_entries.id
                  )
              )
              AND (
                  earlier_reflections.entry_id IS NULL
                  OR earlier_reflections.is_valid = 0
              )
          )
        ORDER BY journal_entries.local_date_time DESC, journal_entries.id DESC
        LIMIT 1
        """,
    )
    suspend fun latestValid(): ReflectionEntity?

    @Query(
        """
        SELECT reflections.*
        FROM reflections
        INNER JOIN journal_entries ON journal_entries.id = reflections.entry_id
        WHERE reflections.is_valid = 1
          AND (
              journal_entries.local_date_time < :localDateTime
              OR (
                  journal_entries.local_date_time = :localDateTime
                  AND journal_entries.id < :entryId
              )
          )
        ORDER BY journal_entries.local_date_time DESC, journal_entries.id DESC
        LIMIT 1
        """,
    )
    suspend fun latestValidBefore(
        localDateTime: String,
        entryId: String,
    ): ReflectionEntity?

    @Query("SELECT MAX(memory_revision) FROM reflections")
    suspend fun highestMemoryRevision(): Int?

    @Query(
        """
        SELECT COUNT(*)
        FROM journal_entries
        LEFT JOIN reflections ON reflections.entry_id = journal_entries.id
        WHERE reflections.entry_id IS NULL OR reflections.is_valid = 0
        """,
    )
    suspend fun entriesNeedingReflectionCount(): Int

    @Query(
        """
        UPDATE reflections
        SET is_valid = 0,
            invalidation_reason = :reason
        WHERE is_valid = 1
          AND entry_id IN (
              SELECT id
              FROM journal_entries
              WHERE local_date_time > :localDateTime
                 OR (local_date_time = :localDateTime AND id >= :entryId)
          )
        """,
    )
    suspend fun invalidateAtOrAfter(
        localDateTime: String,
        entryId: String,
        reason: ReflectionInvalidationReason,
    ): Int

    @Query(
        """
        UPDATE reflections
        SET is_valid = 0,
            invalidation_reason = :reason
        WHERE is_valid = 1
          AND entry_id IN (
              SELECT id
              FROM journal_entries
              WHERE local_date_time > :localDateTime
                 OR (local_date_time = :localDateTime AND id > :entryId)
          )
        """,
    )
    suspend fun invalidateAfter(
        localDateTime: String,
        entryId: String,
        reason: ReflectionInvalidationReason,
    ): Int

    @Query(
        """
        UPDATE reflections
        SET is_valid = 0,
            invalidation_reason = :reason
        WHERE is_valid = 1
          AND entry_id IN (
              SELECT id
              FROM journal_entries
              WHERE local_date_time > :localDateTime
                 OR (local_date_time = :localDateTime AND id >= :entryId)
          )
        """,
    )
    suspend fun invalidateAtOrAfterForUnresolvedPredecessor(
        localDateTime: String,
        entryId: String,
        reason: ReflectionInvalidationReason = ReflectionInvalidationReason.PREDECESSOR_UNRESOLVED,
    ): Int

    @Query("DELETE FROM reflections WHERE entry_id = :entryId")
    suspend fun deleteByEntryId(entryId: String): Int

    @Query("DELETE FROM reflections")
    suspend fun deleteAll()
}
