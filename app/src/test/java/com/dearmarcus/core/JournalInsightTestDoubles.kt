package com.dearmarcus.core

import java.time.Instant
import java.time.LocalDateTime

internal class FakeJournalInsightStore : JournalInsightStore {
    private val entriesById = linkedMapOf<String, JournalEntry>()
    private val reflectionsByEntryId = linkedMapOf<String, Reflection>()

    val events = mutableListOf<String>()

    override suspend fun saveEntry(entry: JournalEntry) {
        check(entriesById.putIfAbsent(entry.id(), entry) == null)
        events += "save-entry:${entry.id()}"
    }

    override suspend fun entriesOldestFirst(): List<JournalEntry> = entriesById.values.sortedWith(
        compareBy<JournalEntry>({ it.localDateTime() }, { it.id() }),
    )

    override suspend fun reflectionFor(entryId: String): Reflection? = reflectionsByEntryId[entryId]

    override suspend fun invalidateReflectionsAtOrAfter(entry: JournalEntry) {
        entriesOldestFirst()
            .dropWhile { it.isBefore(entry) }
            .forEach { candidate ->
                reflectionsByEntryId[candidate.id()] = reflectionsByEntryId[candidate.id()]?.invalidated()
                    ?: return@forEach
            }
    }

    override suspend fun latestValidReflectionBefore(entry: JournalEntry): Reflection? =
        entriesOldestFirst()
            .asSequence()
            .takeWhile { it.isBefore(entry) }
            .mapNotNull { reflectionsByEntryId[it.id()] }
            .filter { it.isValid() }
            .lastOrNull()

    override suspend fun highestMemoryRevision(): Int =
        reflectionsByEntryId.values.maxOfOrNull { it.memoryRevision() } ?: 0

    override suspend fun saveReflectionIfEntryUnchanged(
        entry: JournalEntry,
        reflection: Reflection,
    ): Boolean {
        if (entriesById[entry.id()]?.hasSameSnapshot(entry) != true) return false
        if (reflectionsByEntryId[reflection.entryId()]?.isValid() == true) return false
        check(reflection.isValid())
        check(reflection.memoryRevision() > highestMemoryRevision())
        reflectionsByEntryId[reflection.entryId()] = reflection
        events += "save-reflection:${reflection.entryId()}"
        return true
    }

    fun seed(entry: JournalEntry, reflection: Reflection? = null) {
        entriesById[entry.id()] = entry
        reflection?.let { reflectionsByEntryId[it.entryId()] = it }
    }

    fun entry(id: String): JournalEntry? = entriesById[id]

    fun reflection(id: String): Reflection? = reflectionsByEntryId[id]

    fun replaceReflection(reflection: Reflection) {
        reflectionsByEntryId[reflection.entryId()] = reflection
    }

    fun replaceEntry(entry: JournalEntry) {
        entriesById[entry.id()] = entry
    }

    fun reflectionsOldestFirst(): List<Reflection> = entriesById.values
        .sortedWith(compareBy<JournalEntry>({ it.localDateTime() }, { it.id() }))
        .mapNotNull { reflectionsByEntryId[it.id()] }

    fun activeReflection(): Reflection? = reflectionsOldestFirst().lastOrNull { it.isValid() }

    private fun JournalEntry.isBefore(other: JournalEntry): Boolean =
        compareValuesBy(this, other, { it.localDateTime() }, { it.id() }) < 0

    private fun JournalEntry.hasSameSnapshot(other: JournalEntry): Boolean =
        id() == other.id() &&
            localDateTime() == other.localDateTime() &&
            updatedAt() == other.updatedAt() &&
            answers().whatWentWell() == other.answers().whatWentWell() &&
            answers().whatWentPoorly() == other.answers().whatWentPoorly() &&
            answers().whatWouldYouDoDifferently() == other.answers().whatWouldYouDoDifferently()
}

internal class ScriptedJournalAiClient(
    actions: List<() -> JournalAiResponse>,
    private val onGenerate: (() -> Unit)? = null,
) : JournalAiClient {
    private val pendingActions = ArrayDeque(actions)

    val requests = mutableListOf<JournalAiRequest>()

    override suspend fun generate(request: JournalAiRequest): JournalAiResponse {
        requests += request
        onGenerate?.invoke()
        return pendingActions.removeFirst().invoke()
    }
}

internal class FixedTokenCounter(
    private val tokenCount: Int = 100,
) : TokenCounter {
    override suspend fun countTokens(payload: String): Int = tokenCount
}

internal class FixedJournalClock(
    private val instant: Instant = Instant.parse("2026-08-01T10:00:00Z"),
) : JournalClock {
    override fun now(): Instant = instant
}

internal class ScriptedJournalIdGenerator(ids: List<String>) : JournalIdGenerator {
    private val pendingIds = ArrayDeque(ids)

    override fun nextId(): String = pendingIds.removeFirst()
}

internal fun journalEntry(
    id: String,
    day: Int,
    clock: JournalClock = FixedJournalClock(),
): JournalEntry = JournalEntry.create(
    { id },
    clock,
    LocalDateTime.of(2026, 8, day, 18, 30),
    JournalAnswers.of("well $day", "poorly $day", "differently $day"),
)

internal fun successfulReflection(
    entry: JournalEntry,
    feedback: String,
    memoryBefore: String,
    memoryAfter: String,
    revision: Int,
    clock: JournalClock = FixedJournalClock(),
): Reflection = Reflection.successful(
    entry.id(),
    feedback,
    memoryBefore,
    memoryAfter,
    revision,
    clock,
)

internal fun validResponse(feedback: String, memory: String): JournalAiResponse.Success =
    JournalAiResponse.Success("{\"feedback\":\"$feedback\",\"memory\":\"$memory\"}")
