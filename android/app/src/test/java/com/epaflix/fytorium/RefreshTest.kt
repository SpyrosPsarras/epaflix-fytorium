package com.epaflix.fytorium

import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.ExistingPeriodicWorkPolicy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RefreshTest {
    @Test fun backgroundReadsWaitSixHoursAndRequireNetworkAndBattery() {
        val work = WorkManager.getInstance(RuntimeEnvironment.getApplication())
        val request = CareController.periodicRequest()
        work.enqueueUniquePeriodicWork("synthetic-refresh-test", ExistingPeriodicWorkPolicy.KEEP, request).result.get()
        val info = requireNotNull(work.getWorkInfoById(request.id).get())
        assertEquals(6 * 60 * 60 * 1000L, info.periodicityInfo!!.repeatIntervalMillis)
        assertEquals(6 * 60 * 60 * 1000L, info.initialDelayMillis)
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
        assertTrue(info.constraints.requiresBatteryNotLow())
        work.cancelUniqueWork("synthetic-refresh-test").result.get()
        assertEquals(WorkInfo.State.CANCELLED, requireNotNull(work.getWorkInfoById(request.id).get()).state)
    }
}
