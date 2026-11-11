package com.opirl.app.ui

import com.opirl.app.engine.obs.ObsOutput
import com.opirl.app.engine.obs.ObsState
import kotlin.test.Test
import kotlin.test.assertEquals

private fun obsStatus(state: ObsState) = obsStatus(state, "live", "rec", "OBS offline")

class ObsStatusTest {
    @Test
    fun namesTheSceneAndTheRunningOutputs() {
        assertEquals("OBS offline", obsStatus(ObsState(error = "Wrong password", scene = "Main")))
        assertEquals("OBS Main", obsStatus(ObsState(connected = true, scene = "Main", stream = ObsOutput.STARTING)))
        assertEquals(
            "OBS Main, live, rec",
            obsStatus(ObsState(connected = true, scene = "Main", stream = ObsOutput.STARTED, record = ObsOutput.STARTED)),
        )
        assertEquals("OBS", obsStatus(ObsState(connected = true)))
    }
}
