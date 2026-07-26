package com.drugme.app.alarm

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DirectBootReminderStoreTest {

    private lateinit var context: Context
    private lateinit var store: DirectBootReminderStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = DirectBootReminderStore(context)
        store.clear()
    }

    @After
    fun tearDown() = store.clear()

    @Test
    fun `queue is sorted deduplicated and available through device protected storage`() {
        store.replace(listOf(3_000L, 1_000L, 2_000L, 2_000L, -1L))

        assertTrue(context.createDeviceProtectedStorageContext().isDeviceProtectedStorage)
        assertEquals(listOf(1_000L, 2_000L, 3_000L), store.snapshot())
        // A new instance reads the same device-protected file, as it must after process death.
        assertEquals(
            listOf(1_000L, 2_000L, 3_000L),
            DirectBootReminderStore(context).snapshot(),
        )
    }

    @Test
    fun `stale reminders are pruned without consuming a future reminder`() {
        store.replace(listOf(1_000L, 2_000L, 3_000L))

        assertEquals(2_000L, store.nextAtOrAfter(1_500L))
        assertEquals(listOf(2_000L, 3_000L), store.snapshot())
    }

    @Test
    fun `one generic fire consumes every elapsed timestamp and leaves the future`() {
        store.replace(listOf(1_000L, 2_000L, 3_000L))

        assertTrue(store.consumeDue(2_500L))
        assertEquals(listOf(3_000L), store.snapshot())
        assertFalse(store.consumeDue(2_500L))
    }

    @Test
    fun `empty queue has no next reminder`() {
        assertNull(store.nextAtOrAfter(0L))
        assertFalse(store.consumeDue(Long.MAX_VALUE))
    }

    @Suppress("DEPRECATION")
    @Test
    fun `manifest receiver is enabled private and direct boot aware`() {
        val info = context.packageManager.getReceiverInfo(
            ComponentName(context, DirectBootReminderReceiver::class.java),
            0,
        )

        assertTrue(info.enabled)
        assertFalse(info.exported)
        assertTrue(info.directBootAware)

        val matches = context.packageManager.queryBroadcastReceivers(
            Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED).setPackage(context.packageName),
            PackageManager.MATCH_DIRECT_BOOT_AWARE,
        )
        assertTrue(
            matches.any {
                it.activityInfo.name == DirectBootReminderReceiver::class.java.name
            }
        )
    }
}
