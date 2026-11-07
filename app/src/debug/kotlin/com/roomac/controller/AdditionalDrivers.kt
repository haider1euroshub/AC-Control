package com.roomac.controller

import com.roomac.controller.protocol.AcDriver
import com.roomac.controller.simulator.SimulatedAcDriver

object AdditionalDrivers {
    fun getDrivers(): List<AcDriver> = listOf(SimulatedAcDriver())
}

