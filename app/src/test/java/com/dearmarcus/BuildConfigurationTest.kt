package com.dearmarcus

import org.junit.Assert.assertEquals
import org.junit.Test

class BuildConfigurationTest {
    @Test
    fun applicationId_isDearMarcus() {
        assertEquals("com.dearmarcus", BuildConfig.APPLICATION_ID)
    }
}
