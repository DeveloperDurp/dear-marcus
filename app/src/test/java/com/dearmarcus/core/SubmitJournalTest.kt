package com.dearmarcus.core

import com.dearmarcus.ai.AiDownloadState
import com.dearmarcus.ai.AiGenerationResult
import com.dearmarcus.ai.AiReadiness
import com.dearmarcus.ai.OnDeviceAiClient
import com.dearmarcus.ai.OnDeviceJournalAiClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class SubmitJournalTest {
    @Test
    fun submit_validatesAndSavesRawEntryBeforeInvokingTheClient() = runBlocking {
        val store = FakeJournalInsightStore()
        val client = ScriptedJournalAiClient(
            actions = listOf({ validResponse("feedback", "memory-v1") }),
            onGenerate = { store.events += "client" },
        )
        val submit = SubmitJournal(
            store = store,
            reflectionGenerator = ReflectionGenerator(client, FixedTokenCounter()),
            idGenerator = ScriptedJournalIdGenerator(listOf("entry-1")),
            clock = FixedJournalClock(),
        )

        val result = submit.submit(
            localDateTime = LocalDateTime.of(2026, 8, 1, 18, 30),
            whatWentWell = "A calm walk.",
            whatWentPoorly = "I rushed a reply.",
            whatWouldYouDoDifferently = "Pause before replying.",
        )

        assertTrue(result is SubmitJournalResult.Reflected)
        assertEquals(
            listOf("save-entry:entry-1", "client", "save-reflection:entry-1"),
            store.events,
        )
        assertEquals("A calm walk.", store.entry("entry-1")?.answers()?.whatWentWell())
        assertEquals(1, store.reflection("entry-1")?.memoryRevision())
    }

    @Test
    fun submit_rejectsInvalidRawAnswersWithoutSavingOrInvokingAi() = runBlocking {
        val store = FakeJournalInsightStore()
        val client = ScriptedJournalAiClient(actions = emptyList())
        val submit = SubmitJournal(
            store = store,
            reflectionGenerator = ReflectionGenerator(client, FixedTokenCounter()),
            idGenerator = ScriptedJournalIdGenerator(listOf("entry-1")),
            clock = FixedJournalClock(),
        )

        val failure = runCatching {
            submit.submit(
                LocalDateTime.of(2026, 8, 1, 18, 30),
                "",
                "I rushed a reply.",
                "Pause before replying.",
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(store.entriesOldestFirst().isEmpty())
        assertTrue(client.requests.isEmpty())
    }

    @Test
    fun threeSuccessfulDays_storeV1V2V3WithChronologicalMemory() = runBlocking {
        val store = FakeJournalInsightStore()
        val client = ScriptedJournalAiClient(
            listOf(
                { validResponse("feedback-v1", "memory-v1") },
                { validResponse("feedback-v2", "memory-v2") },
                { validResponse("feedback-v3", "memory-v3") },
            ),
        )
        val submit = SubmitJournal(
            store,
            ReflectionGenerator(client, FixedTokenCounter()),
            ScriptedJournalIdGenerator(listOf("entry-1", "entry-2", "entry-3")),
            FixedJournalClock(),
        )

        (1..3).forEach { day ->
            submit.submit(
                LocalDateTime.of(2026, 8, day, 18, 30),
                "well $day",
                "poorly $day",
                "differently $day",
            )
        }

        assertEquals(listOf(1, 2, 3), store.reflectionsOldestFirst().map { it.memoryRevision() })
        assertEquals(
            listOf("", "memory-v1", "memory-v2"),
            store.reflectionsOldestFirst().map { it.memoryBefore() },
        )
        assertEquals("memory-v3", store.activeReflection()?.memoryAfter())
        assertTrue(client.requests[1].prompt.contains("\"memoryBefore\":\"memory-v1\""))
        assertTrue(client.requests[2].prompt.contains("\"memoryBefore\":\"memory-v2\""))
    }

    @Test
    fun malformedFailedUnavailableOverBudgetAndMisleadingOutputKeepRawAndPriorMemory() = runBlocking {
        val unavailableClient = UnavailableOnDeviceAiClient()
        val cases = listOf(
            FailureCase(
                "malformed",
                ReflectionFailure.INVALID_OUTPUT,
                ReflectionGenerator(
                    ScriptedJournalAiClient(listOf({ JournalAiResponse.Success("not json") })),
                    FixedTokenCounter(),
                ),
            ),
            FailureCase(
                "failed",
                ReflectionFailure.CLIENT_UNAVAILABLE,
                ReflectionGenerator(
                    ScriptedJournalAiClient(listOf({ JournalAiResponse.Failure })),
                    FixedTokenCounter(),
                ),
            ),
            FailureCase(
                "unavailable",
                ReflectionFailure.CLIENT_UNAVAILABLE,
                ReflectionGenerator(OnDeviceJournalAiClient(unavailableClient), FixedTokenCounter()),
            ),
            FailureCase(
                "over-budget",
                ReflectionFailure.INPUT_TOO_LARGE,
                ReflectionGenerator(
                    ScriptedJournalAiClient(listOf({ validResponse("unused", "unused") })),
                    FixedTokenCounter(4_000),
                ),
            ),
            FailureCase(
                "misleading-success",
                ReflectionFailure.INVALID_OUTPUT,
                ReflectionGenerator(
                    ScriptedJournalAiClient(
                        listOf(
                            {
                                JournalAiResponse.Success(
                                    "{\"feedback\":\"feedback\",\"memory\":\"memory\",\"status\":\"success\"}",
                                )
                            },
                        ),
                    ),
                    FixedTokenCounter(),
                ),
            ),
        )

        cases.forEach { case ->
            val store = FakeJournalInsightStore()
            val priorEntry = journalEntry("prior-${case.name}", 1)
            val priorReflection = successfulReflection(
                priorEntry,
                "prior feedback",
                "",
                "memory-v1",
                1,
            )
            store.seed(priorEntry, priorReflection)
            val submit = SubmitJournal(
                store,
                case.generator,
                ScriptedJournalIdGenerator(listOf("new-${case.name}")),
                FixedJournalClock(),
            )

            val result = submit.submit(
                LocalDateTime.of(2026, 8, 2, 18, 30),
                "well",
                "poorly",
                "differently",
            )

            assertTrue("${case.name} must not claim reflection success", result is SubmitJournalResult.SavedWithoutReflection)
            assertEquals(case.failure, (result as SubmitJournalResult.SavedWithoutReflection).failure)
            assertEquals("well", store.entry("new-${case.name}")?.answers()?.whatWentWell())
            assertEquals(priorReflection, store.activeReflection())
            assertEquals(1, store.reflectionsOldestFirst().size)
        }
        assertEquals(1, unavailableClient.generateCalls)
    }

    @Test
    fun savedWithoutReflection_failureMessages_distinguishRetryableFailuresFromInputTooLarge() = runBlocking {
        val retryableCases = listOf(
            ReflectionFailure.CLIENT_UNAVAILABLE to
                "On-device reflection is unavailable. Your entry remains saved; retry the saved entry from Settings while the app remains foregrounded.",
            ReflectionFailure.INVALID_OUTPUT to
                "On-device reflection returned unusable output. Your entry remains saved; retry the saved entry from Settings while the app remains foregrounded.",
        )

        retryableCases.forEach { (failure, expectedMessage) ->
            val submit = SubmitJournal(
                store = FakeJournalInsightStore(),
                reflectionGenerator = when (failure) {
                    ReflectionFailure.CLIENT_UNAVAILABLE -> ReflectionGenerator(
                        ScriptedJournalAiClient(listOf({ JournalAiResponse.Failure })),
                        FixedTokenCounter(),
                    )
                    ReflectionFailure.INVALID_OUTPUT -> ReflectionGenerator(
                        ScriptedJournalAiClient(listOf({ JournalAiResponse.Success("not json") })),
                        FixedTokenCounter(),
                    )
                    else -> error("Unexpected failure case: $failure")
                },
                idGenerator = ScriptedJournalIdGenerator(listOf("entry-${failure}")),
                clock = FixedJournalClock(),
            )

            val result = submit.submit(
                localDateTime = LocalDateTime.of(2026, 8, 1, 18, 30),
                whatWentWell = "well",
                whatWentPoorly = "poorly",
                whatWouldYouDoDifferently = "differently",
            )

            val savedWithoutReflection = result as SubmitJournalResult.SavedWithoutReflection
            assertEquals("${failure.name} should remain saved without reflection", failure, savedWithoutReflection.failure)
            assertEquals(expectedMessage, savedWithoutReflection.failure.userMessage)
            assertTrue(
                "${failure.name} should still guide users to Settings",
                savedWithoutReflection.failure.userMessage.contains("Settings"),
            )
        }

        val entryChangedEntry = JournalEntry.create(
            { "entry-${ReflectionFailure.ENTRY_CHANGED}" },
            FixedJournalClock(),
            LocalDateTime.of(2026, 8, 3, 18, 30),
            JournalAnswers.of("well", "poorly", "differently"),
        )
        val entryChangedStore = FakeJournalInsightStoreAllowingOverwrite(
            entryChangedEntry,
            successfulReflection(
                entryChangedEntry,
                "prior feedback",
                "memory-before",
                "memory-after",
                1,
            ),
        )
        val entryChangedSubmit = SubmitJournal(
            store = entryChangedStore,
            reflectionGenerator = ReflectionGenerator(
                ScriptedJournalAiClient(listOf({ validResponse("entry changed feedback", "entry changed memory") })),
                FixedTokenCounter(),
            ),
            idGenerator = ScriptedJournalIdGenerator(listOf("entry-${ReflectionFailure.ENTRY_CHANGED}")),
            clock = FixedJournalClock(),
        )

        val entryChangedResult = entryChangedSubmit.submit(
            localDateTime = LocalDateTime.of(2026, 8, 3, 18, 30),
            whatWentWell = "well",
            whatWentPoorly = "poorly",
            whatWouldYouDoDifferently = "differently",
        )

        val entryChangedNoReflection = entryChangedResult as SubmitJournalResult.SavedWithoutReflection
        assertEquals(
            ReflectionFailure.ENTRY_CHANGED,
            entryChangedNoReflection.failure,
        )
        assertEquals(
            "The entry changed before feedback could be saved. Your latest answers remain saved; retry the saved entry from Settings while the app remains foregrounded.",
            entryChangedNoReflection.failure.userMessage,
        )
        assertTrue(
            "ENTRY_CHANGED should still guide users to Settings",
            entryChangedNoReflection.failure.userMessage.contains("Settings"),
        )

        val submit = SubmitJournal(
            store = FakeJournalInsightStore(),
            reflectionGenerator = ReflectionGenerator(
                ScriptedJournalAiClient(actions = emptyList()),
                FixedTokenCounter(4_000),
            ),
            idGenerator = ScriptedJournalIdGenerator(listOf("entry-large")),
            clock = FixedJournalClock(),
        )

        val result = submit.submit(
            localDateTime = LocalDateTime.of(2026, 8, 2, 18, 30),
            whatWentWell = "well",
            whatWentPoorly = "poorly",
            whatWouldYouDoDifferently = "differently",
        )

        assertEquals(SubmitJournalResult.SavedWithoutReflection::class, result::class)
        val savedWithoutReflection = result as SubmitJournalResult.SavedWithoutReflection
        assertEquals(ReflectionFailure.INPUT_TOO_LARGE, savedWithoutReflection.failure)
        assertEquals(
            "Reflection was not generated because the on-device input is too large. Edit the entry to shorten it, then save again.",
            savedWithoutReflection.failure.userMessage,
        )
        assertFalse(
            "Input size failures should not send users to Settings",
            savedWithoutReflection.failure.userMessage.contains("Settings"),
        )
    }

    @Test
    fun overBudgetReflection_keepsTheFullPersistedAnswer() = runBlocking {
        val fullAnswer = "a".repeat(600)
        val store = FakeJournalInsightStore()
        val client = ScriptedJournalAiClient(actions = emptyList())
        val submit = SubmitJournal(
            store = store,
            reflectionGenerator = ReflectionGenerator(client, FixedTokenCounter(4_000)),
            idGenerator = ScriptedJournalIdGenerator(listOf("entry-1")),
            clock = FixedJournalClock(),
        )

        val result = submit.submit(
            localDateTime = LocalDateTime.of(2026, 8, 1, 18, 30),
            whatWentWell = fullAnswer,
            whatWentPoorly = "poorly",
            whatWouldYouDoDifferently = "differently",
        )

        assertEquals(
            SubmitJournalResult.SavedWithoutReflection(
                store.entry("entry-1")!!,
                ReflectionFailure.INPUT_TOO_LARGE,
            ),
            result,
        )
        assertEquals(fullAnswer, store.entry("entry-1")?.answers()?.whatWentWell())
        assertTrue(client.requests.isEmpty())
    }

    private data class FailureCase(
        val name: String,
        val failure: ReflectionFailure,
        val generator: ReflectionGenerator,
    )

    private class FakeJournalInsightStoreAllowingOverwrite(
        private val entry: JournalEntry,
        private val reflection: Reflection,
    ) : JournalInsightStore {
        private val delegate = FakeJournalInsightStore().apply {
            seed(entry, reflection)
        }

        override suspend fun saveEntry(entry: JournalEntry) {
            delegate.replaceEntry(entry)
        }

        override suspend fun entriesOldestFirst(): List<JournalEntry> = delegate.entriesOldestFirst()

        override suspend fun reflectionFor(entryId: String): Reflection? = delegate.reflectionFor(entryId)

        override suspend fun invalidateReflectionsAtOrAfter(entry: JournalEntry) {
            delegate.invalidateReflectionsAtOrAfter(entry)
        }

        override suspend fun latestValidReflectionBefore(entry: JournalEntry): Reflection? =
            delegate.latestValidReflectionBefore(entry)

        override suspend fun highestMemoryRevision(): Int = delegate.highestMemoryRevision()

        override suspend fun saveReflectionIfEntryUnchanged(
            entry: JournalEntry,
            reflection: Reflection,
        ): Boolean = delegate.saveReflectionIfEntryUnchanged(entry, reflection)
    }

    private class UnavailableOnDeviceAiClient : OnDeviceAiClient {
        var generateCalls = 0

        override suspend fun checkAvailability(): AiReadiness = AiReadiness.Unavailable

        override fun startUserInitiatedDownload(): Flow<AiDownloadState> = emptyFlow()

        override suspend fun generate(prompt: String): AiGenerationResult {
            generateCalls += 1
            return AiGenerationResult.NotReady(AiReadiness.Unavailable)
        }
    }
}
