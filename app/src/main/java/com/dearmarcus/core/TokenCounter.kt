package com.dearmarcus.core

/** A fakeable boundary around ML Kit's exact input-token count. */
fun interface TokenCounter {
    suspend fun countTokens(payload: String): Int
}

class TokenCounterUnavailableException : Exception()
