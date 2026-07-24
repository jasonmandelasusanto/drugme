package com.drugme.app.data.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateRepositoryTest {

    @Test
    fun `semantic version comparison handles multi-digit components`() {
        assertTrue(isNewer("0.10.0", "0.9.9"))
        assertTrue(isNewer("1.0.1", "1.0.0"))
        assertFalse(isNewer("1.0.0", "1.0.0"))
        assertFalse(isNewer("1.2.0", "1.10.0"))
    }

    @Test
    fun `pre-release suffix does not distort numeric comparison`() {
        assertTrue(isNewer("2.0.0", "1.9.9"))
        assertFalse(isNewer("1.0.0-beta", "1.0.0"))
    }
}
