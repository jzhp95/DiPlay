package com.shilapi.xcertplay.update

import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BackgroundUpdateRemovalTest {
    @Test fun previouslyPersistedWorkerFinishesWithoutCheckingOrRetrying() {
        val worker = BackgroundUpdateWorker(RuntimeEnvironment.getApplication(), mock(WorkerParameters::class.java))
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
    }

    @Test fun migrationCancelsBothLegacyWorkNamesWithoutEnqueuingReplacementWork() {
        val manager = mock(WorkManager::class.java)
        BackgroundUpdateScheduler.cancel(manager)
        verify(manager).cancelUniqueWork("diplay-periodic-update-check")
        verify(manager).cancelUniqueWork("diplay-open-update-check")
        verifyNoMoreInteractions(manager)
    }
}
