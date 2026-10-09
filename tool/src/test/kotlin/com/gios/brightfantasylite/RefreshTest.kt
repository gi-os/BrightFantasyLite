package com.gios.brightfantasylite

import com.gios.brightfantasylite.data.Refresh
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RefreshTest {
    @Test
    fun `first open always loads`() {
        assertTrue(Refresh.dueOnOpen(0L, 1_000L))
    }

    @Test
    fun `reopening within a minute reuses the last load`() {
        assertFalse(Refresh.dueOnOpen(100_000L, 130_000L))
        assertTrue(Refresh.dueOnOpen(100_000L, 160_000L))
    }

    @Test
    fun `manual refresh has a floor`() {
        assertFalse(Refresh.manualAllowed(100_000L, 105_000L))
        assertTrue(Refresh.manualAllowed(100_000L, 115_000L))
    }

    @Test
    fun `age reads in words`() {
        assertEquals("just now", Refresh.ago(100_000L, 120_000L))
        assertEquals("5 min ago", Refresh.ago(0L + 1, 300_001L))
        assertEquals("", Refresh.ago(0L, 5L))
    }
}
