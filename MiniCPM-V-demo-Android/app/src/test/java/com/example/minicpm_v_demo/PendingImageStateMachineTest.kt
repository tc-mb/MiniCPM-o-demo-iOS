package com.example.minicpm_v_demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingImageStateMachineTest {

    @Test
    fun completionIsTheOnlyTransitionThatExposesOneHundredPercent() {
        val machine = PendingImageStateMachine()

        val requestId = machine.start()

        assertTrue(machine.state is PendingImageState.Preprocessing)
        assertNull(machine.state.progressPercent)

        assertTrue(machine.complete(requestId))
        assertTrue(machine.state is PendingImageState.Ready)
        assertEquals(100, machine.state.progressPercent)
    }

    @Test
    fun staleCallbacksCannotReplaceTheCurrentRequest() {
        val machine = PendingImageStateMachine()
        val firstRequest = machine.start()
        assertTrue(machine.fail(firstRequest))

        val secondRequest = machine.start()

        assertFalse(machine.complete(firstRequest))
        assertFalse(machine.fail(firstRequest))
        assertEquals(
            PendingImageState.Preprocessing(secondRequest),
            machine.state
        )
    }

    @Test
    fun preprocessingBlocksSendAndMediaSelectionButKeepsTextEditable() {
        val machine = PendingImageStateMachine()
        machine.start()

        val controls = machine.controls(
            modelReady = true,
            engineBusy = false,
            videoProcessing = false,
            hasText = true
        )

        assertTrue(controls.textEnabled)
        assertFalse(controls.sendEnabled)
        assertFalse(controls.mediaEnabled)
        assertFalse(controls.modelSettingsEnabled)
    }

    @Test
    fun readyImageAllowsTextSendButNotReplacement() {
        val machine = PendingImageStateMachine()
        val requestId = machine.start()
        assertTrue(machine.complete(requestId))

        val controls = machine.controls(
            modelReady = true,
            engineBusy = false,
            videoProcessing = false,
            hasText = true
        )

        assertTrue(controls.textEnabled)
        assertTrue(controls.sendEnabled)
        assertFalse(controls.mediaEnabled)
        assertFalse(controls.modelSettingsEnabled)
    }

    @Test
    fun consumingReadyImageReturnsToEmptyAndCanOnlyHappenOnce() {
        val machine = PendingImageStateMachine()
        val requestId = machine.start()
        machine.complete(requestId)

        assertEquals(requestId, machine.consumeReady())
        assertEquals(PendingImageState.Empty, machine.state)
        assertNull(machine.consumeReady())
    }

    @Test
    fun failedRequestReturnsToEmptyAndAllowsRetry() {
        val machine = PendingImageStateMachine()
        val requestId = machine.start()

        assertTrue(machine.fail(requestId))
        assertEquals(PendingImageState.Empty, machine.state)
        assertTrue(machine.controls(
            modelReady = true,
            engineBusy = false,
            videoProcessing = false,
            hasText = false
        ).mediaEnabled)
    }

    @Test
    fun busyEngineDisablesAllInputRegardlessOfAttachmentState() {
        val machine = PendingImageStateMachine()

        val controls = machine.controls(
            modelReady = true,
            engineBusy = true,
            videoProcessing = false,
            hasText = true
        )

        assertFalse(controls.textEnabled)
        assertFalse(controls.sendEnabled)
        assertFalse(controls.mediaEnabled)
        assertFalse(controls.modelSettingsEnabled)
    }

    @Test
    fun userRemovalHidesPendingImageBeforeProcessingJobStops() {
        assertEquals(
            PendingImageCancellationDisplay.HIDDEN,
            PendingImageCancellationPolicy.displayWhileCancelling(
                hasProcessingJob = true,
                mode = PendingImageCancellationMode.USER_REMOVE
            )
        )
    }

    @Test
    fun contextResetShowsClearingOnlyWhileProcessingJobStops() {
        assertEquals(
            PendingImageCancellationDisplay.CLEARING,
            PendingImageCancellationPolicy.displayWhileCancelling(
                hasProcessingJob = true,
                mode = PendingImageCancellationMode.CONTEXT_RESET
            )
        )
        assertEquals(
            PendingImageCancellationDisplay.HIDDEN,
            PendingImageCancellationPolicy.displayWhileCancelling(
                hasProcessingJob = false,
                mode = PendingImageCancellationMode.CONTEXT_RESET
            )
        )
    }
}
