package com.bjorntech.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManagerTest {

    @Test
    fun newerVersionsAreDetected() {
        assertTrue(UpdateManager.isNewer("1.4", "1.3"))
        assertTrue(UpdateManager.isNewer("1.10", "1.9"))   // numeric, not string compare
        assertTrue(UpdateManager.isNewer("2.0", "1.99"))
        assertTrue(UpdateManager.isNewer("1.4.1", "1.4"))
    }

    @Test
    fun sameOrOlderVersionsAreNot() {
        assertFalse(UpdateManager.isNewer("1.4", "1.4"))
        assertFalse(UpdateManager.isNewer("1.4", "1.4.0"))
        assertFalse(UpdateManager.isNewer("1.3", "1.4"))
        assertFalse(UpdateManager.isNewer("1.9", "1.10"))
    }
}
