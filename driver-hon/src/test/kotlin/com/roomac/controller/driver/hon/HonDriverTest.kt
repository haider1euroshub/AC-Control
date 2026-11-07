package com.roomac.controller.driver.hon

import com.roomac.controller.domain.model.AcCommand
import com.roomac.controller.domain.model.AcError
import com.roomac.controller.domain.model.CommandResult
import com.roomac.controller.domain.model.PowerState
import com.roomac.controller.protocol.AcDriver
import com.roomac.controller.protocol.DeviceConfig
import com.roomac.controller.protocol.DriverContractTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HonDriverTest : DriverContractTest() {

    override fun createDriver(): AcDriver = HonDriver()

    @Test
    fun connectFailsWithProtocolNotVerified() = runBlocking {
        val driver = HonDriver()
        val res = driver.connect(DeviceConfig(protocolId = "haier-hon", host = "192.168.1.100"))
        assertTrue(res.isFailure)
    }

    @Test
    fun executeCommandReturnsProtocolNotVerifiedError() = runBlocking {
        val driver = HonDriver()
        val result = driver.executeCommand(AcCommand.SetPower(PowerState.ON))
        assertTrue(result is CommandResult.Failed)
        assertTrue((result as CommandResult.Failed).error is AcError.ProtocolNotVerified)
    }
}
