package com.drugme.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppNavigationTest {
    @Test
    fun `profile initials use first and last names then email fallback`() {
        assertEquals("JS", profileInitials("Jason Susanto", null))
        assertEquals("MA", profileInitials("Maria", null))
        assertEquals("P", profileInitials(null, "person@example.com"))
        assertNull(profileInitials(null, null))
    }

    @Test
    fun `all top level routes restore to their own selected destination`() {
        TOP_LEVEL_DESTINATIONS.forEach { destination ->
            assertEquals(destination.route, bottomDestinationForRoute(destination.route))
            assertEquals(true, isTopLevelRoute(destination.route))
        }
        assertEquals(Routes.HOME, bottomDestinationForRoute(Routes.SETTINGS))
        assertEquals(false, isTopLevelRoute(Routes.SETTINGS))
    }
}
