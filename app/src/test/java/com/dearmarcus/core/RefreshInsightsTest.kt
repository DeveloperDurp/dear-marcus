package com.dearmarcus.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RefreshInsightsTest {
    @Test
    fun refreshRetriesRawOnlyEntryAfterItsFirstReflectionFails() = runBlocking {
        val store = FakeJournalInsightStore()
        val submit = SubmitJournal(
            store = store,
            reflectionGenerator = ReflectionGenerator(
                ScriptedJournalAiClient(listOf({ JournalAiResponse.Failure })),
                FixedTokenCounter(),
            ),
            idGenerator = ScriptedJournalIdGenerator(listOf("entry-1")),
            clock = FixedJournalClock(),
        )

        val submitted = submit.submit(
            localDateTime = java.time.LocalDateTime.of(2026, 8, 1, 18, 30),
            whatWentWell = "well",
            whatWentPoorly = "poorly",
            whatWouldYouDoDifferently = "differently",
        )

        assertTrue(submitted is SubmitJournalResult.SavedWithoutReflection)
        assertEquals(1, store.entriesOldestFirst().size)
        assertEquals(null, store.reflection("entry-1"))

        val result = RefreshInsights(
            store = store,
            reflectionGenerator = ReflectionGenerator(
                ScriptedJournalAiClient(listOf({ validResponse("feedback", "memory-v1") })),
                FixedTokenCounter(),
            ),
            clock = FixedJournalClock(),
        ).refresh()

        assertEquals(RefreshInsightsResult.Completed(listOf("entry-1")), result)
        assertEquals("memory-v1", store.reflection("entry-1")?.memoryAfter())
    }

    @Test
    fun refreshRegeneratesValidSuffixAfterBackfillingAnEarlierRawOnlyEntry() = runBlocking {
        val store = FakeJournalInsightStore()
        val firstEntry = journalEntry("entry-1", 1)
        val secondEntry = journalEntry("entry-2", 2)
        store.seed(firstEntry)
        store.seed(
            secondEntry,
            successfulReflection(secondEntry, "stale feedback", "", "stale memory", 1),
        )

        val result = RefreshInsights(
            store,
            ReflectionGenerator(
                ScriptedJournalAiClient(
                    listOf(
                        { validResponse("feedback-v2", "memory-v2") },
                        { validResponse("feedback-v3", "memory-v3") },
                    ),
                ),
                FixedTokenCounter(),
            ),
            FixedJournalClock(),
        ).refresh()

        assertEquals(RefreshInsightsResult.Completed(listOf("entry-1", "entry-2")), result)
        assertEquals("", store.reflection("entry-1")?.memoryBefore())
        assertEquals("memory-v2", store.reflection("entry-1")?.memoryAfter())
        assertEquals("memory-v2", store.reflection("entry-2")?.memoryBefore())
        assertEquals("memory-v3", store.activeReflection()?.memoryAfter())
    }

    @Test
    fun refreshesInvalidEntriesChronologicallyUsingFreshPriorMemory() = runBlocking {
        val store = invalidatedThreeDayStore()
        val client = ScriptedJournalAiClient(
            listOf(
                { validResponse("feedback-v4", "memory-v4") },
                { validResponse("feedback-v5", "memory-v5") },
                { validResponse("feedback-v6", "memory-v6") },
            ),
        )
        val refresh = RefreshInsights(
            store,
            ReflectionGenerator(client, FixedTokenCounter()),
            FixedJournalClock(),
        )

        val result = refresh.refresh()

        assertEquals(
            RefreshInsightsResult.Completed(listOf("entry-1", "entry-2", "entry-3")),
            result,
        )
        assertEquals(listOf(4, 5, 6), store.reflectionsOldestFirst().map { it.memoryRevision() })
        assertEquals(
            listOf("", "memory-v4", "memory-v5"),
            store.reflectionsOldestFirst().map { it.memoryBefore() },
        )
        assertEquals("memory-v6", store.activeReflection()?.memoryAfter())
        assertTrue(client.requests[1].prompt.contains("\"memoryBefore\":\"memory-v4\""))
        assertTrue(client.requests[2].prompt.contains("\"memoryBefore\":\"memory-v5\""))
    }

    @Test
    fun refreshStopsAfterFailureKeepsCompletedWorkAndExplicitLaterRefreshResumes() = runBlocking {
        val store = invalidatedThreeDayStore()
        val firstClient = ScriptedJournalAiClient(
            listOf(
                { validResponse("feedback-v4", "memory-v4") },
                { JournalAiResponse.Failure },
            ),
        )

        val firstResult = RefreshInsights(
            store,
            ReflectionGenerator(firstClient, FixedTokenCounter()),
            FixedJournalClock(),
        ).refresh()

        assertEquals(
            RefreshInsightsResult.Stopped(
                refreshedEntryIds = listOf("entry-1"),
                failure = ReflectionFailure.CLIENT_UNAVAILABLE,
            ),
            firstResult,
        )
        assertEquals(2, firstClient.requests.size)
        assertTrue(store.reflection("entry-1")?.isValid() == true)
        assertFalse(store.reflection("entry-2")?.isValid() ?: true)
        assertFalse(store.reflection("entry-3")?.isValid() ?: true)
        assertEquals(3, store.entriesOldestFirst().size)

        val resumedClient = ScriptedJournalAiClient(
            listOf(
                { validResponse("feedback-v5", "memory-v5") },
                { validResponse("feedback-v6", "memory-v6") },
            ),
        )
        val resumedResult = RefreshInsights(
            store,
            ReflectionGenerator(resumedClient, FixedTokenCounter()),
            FixedJournalClock(),
        ).refresh()

        assertEquals(
            RefreshInsightsResult.Completed(listOf("entry-2", "entry-3")),
            resumedResult,
        )
        assertEquals(listOf(4, 5, 6), store.reflectionsOldestFirst().map { it.memoryRevision() })
        assertEquals("memory-v6", store.activeReflection()?.memoryAfter())
    }

    @Test
    fun refreshRechecksAnInvalidRowAndDoesNotInferFromStaleState() = runBlocking {
        val store = FakeJournalInsightStore()
        val entry = journalEntry("entry-1", 1)
        store.seed(
            entry,
            successfulReflection(entry, "old feedback", "", "old memory", 1).invalidated(),
        )
        val currentReflection = successfulReflection(
            entry,
            "current feedback",
            "",
            "current memory",
            2,
        )
        val staleStore = object : JournalInsightStore by store {
            var entryReads = 0

            override suspend fun reflectionFor(entryId: String): Reflection? {
                entryReads += 1
                if (entryReads == 2) store.replaceReflection(currentReflection)
                return store.reflection(entryId)
            }
        }
        val client = ScriptedJournalAiClient(emptyList())

        val result = RefreshInsights(
            staleStore,
            ReflectionGenerator(client, FixedTokenCounter()),
            FixedJournalClock(),
        ).refresh()

        assertEquals(RefreshInsightsResult.NoRefreshRequired, result)
        assertTrue(store.reflection("entry-1")?.isValid() == true)
        assertTrue(client.requests.isEmpty())
    }

    @Test
    fun refreshDoesNotOverwriteAnEntryEditedWhileInferenceWasRunning() = runBlocking {
        val store = FakeJournalInsightStore()
        val entry = journalEntry("entry-1", 1)
        store.seed(
            entry,
            successfulReflection(entry, "old feedback", "", "old memory", 1).invalidated(),
        )
        val client = ScriptedJournalAiClient(
            actions = listOf({ validResponse("new feedback", "new memory") }),
            onGenerate = {
                store.replaceEntry(
                    entry.withAnswers(
                        JournalAnswers.of("edited", "poorly", "differently"),
                        FixedJournalClock(Instant.parse("2026-08-01T11:00:00Z")),
                    ),
                )
            },
        )

        val result = RefreshInsights(
            store,
            ReflectionGenerator(client, FixedTokenCounter()),
            FixedJournalClock(),
        ).refresh()

        assertEquals(
            RefreshInsightsResult.Stopped(emptyList(), ReflectionFailure.ENTRY_CHANGED),
            result,
        )
        assertEquals("edited", store.entry("entry-1")?.answers()?.whatWentWell())
        assertFalse(store.reflection("entry-1")?.isValid() ?: true)
    }

    @Test
    fun cancellationAndRepeatedInterruptionsKeepRawAndInvalidStateUntilAnExplicitResume() = runBlocking {
        val store = invalidatedThreeDayStore()
        val firstInterruptedClient = ScriptedJournalAiClient(
            listOf(
                { validResponse("feedback-v4", "memory-v4") },
                { throw CancellationException("user left the foreground") },
            ),
        )

        val firstCancellation = runCatching {
            RefreshInsights(
                store,
                ReflectionGenerator(firstInterruptedClient, FixedTokenCounter()),
                FixedJournalClock(),
            ).refresh()
        }.exceptionOrNull()

        assertTrue(firstCancellation is CancellationException)
        assertEquals(2, firstInterruptedClient.requests.size)
        assertTrue(store.reflection("entry-1")?.isValid() == true)
        assertFalse(store.reflection("entry-2")?.isValid() ?: true)
        assertFalse(store.reflection("entry-3")?.isValid() ?: true)
        assertEquals(3, store.entriesOldestFirst().size)

        val repeatedInterruptClient = ScriptedJournalAiClient(
            listOf({ throw CancellationException("user left again") }),
        )
        val repeatedCancellation = runCatching {
            RefreshInsights(
                store,
                ReflectionGenerator(repeatedInterruptClient, FixedTokenCounter()),
                FixedJournalClock(),
            ).refresh()
        }.exceptionOrNull()

        assertTrue(repeatedCancellation is CancellationException)
        assertEquals(1, repeatedInterruptClient.requests.size)
        assertFalse(store.reflection("entry-2")?.isValid() ?: true)

        val resumedClient = ScriptedJournalAiClient(
            listOf(
                { validResponse("feedback-v5", "memory-v5") },
                { validResponse("feedback-v6", "memory-v6") },
            ),
        )
        val resumedResult = RefreshInsights(
            store,
            ReflectionGenerator(resumedClient, FixedTokenCounter()),
            FixedJournalClock(),
        ).refresh()

        assertEquals(
            RefreshInsightsResult.Completed(listOf("entry-2", "entry-3")),
            resumedResult,
        )
        assertEquals("memory-v6", store.activeReflection()?.memoryAfter())
    }

    private fun invalidatedThreeDayStore(): FakeJournalInsightStore {
        val store = FakeJournalInsightStore()
        (1..3).forEach { day ->
            val entry = journalEntry("entry-$day", day)
            store.seed(
                entry,
                successfulReflection(
                    entry,
                    "feedback-v$day",
                    if (day == 1) "" else "memory-v${day - 1}",
                    "memory-v$day",
                    day,
                ).invalidated(),
            )
        }
        return store
    }
}
