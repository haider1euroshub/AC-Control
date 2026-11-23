package com.roomac.controller.domain

import com.roomac.controller.domain.model.AcCommand
import com.roomac.controller.domain.model.AcError
import com.roomac.controller.domain.model.AcFeature
import com.roomac.controller.domain.model.AcMode
import com.roomac.controller.domain.model.AcStatus
import com.roomac.controller.domain.model.CapabilityResolver
import com.roomac.controller.domain.model.EcoLevel
import com.roomac.controller.domain.model.FanSpeed
import com.roomac.controller.domain.model.ModelProfiles
import com.roomac.controller.domain.model.ModelRecognition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

fun status(
    power: Boolean = true, mode: AcMode = AcMode.COOL, temp: Double = 24.0, fan: FanSpeed = FanSpeed.AUTO,
    heatCapable: Boolean? = true, writable: Boolean = true, sleep: Boolean = false, selfCleaning: Boolean? = false,
    horizontal: Boolean? = false, eco: EcoLevel? = EcoLevel.OFF
) = AcStatus(
    power = power, mode = mode, rawModeCode = mode.stdCode, targetTemperatureC = temp, fanSpeed = fan,
    rawFanCode = fan.stdCode, verticalSwing = false, horizontalSwing = horizontal, indoorTemperatureC = 27.0,
    outdoorTemperatureC = 33.0,
    features = mapOf(AcFeature.TURBO to false, AcFeature.QUIET to false, AcFeature.SLEEP to sleep,
        AcFeature.HEALTH to false, AcFeature.DISPLAY to true),
    ecoLevel = eco, selfCleaning = selfCleaning, heatCapable = heatCapable, errorCode = 0,
    writable = writable, reportLength = 127, receivedAtEpochMs = 0
)

class ModelProfileTest {

    @Test
    fun `model recognition`() {
        assertEquals(ModelRecognition.ExactTarget, ModelProfiles.recognizeModel(" hsu-20hftex/013wusdc(ow)-t3 "))
        assertTrue(ModelProfiles.recognizeModel("HSU-20HFTEX/013WUSDC(W)-T3") is ModelRecognition.TargetFamily)
        assertTrue(ModelProfiles.recognizeModel("HSU-24HFAB/013WUSDC(W)-T3") is ModelRecognition.OtherHaier)
        assertTrue(ModelProfiles.recognizeModel("XYZ") is ModelRecognition.Unrecognized)
        assertFalse(ModelProfiles.recognizeModel("").isAcceptable)
    }

    @Test
    fun `target AC uplusId selects the shared wall-split profile`() {
        assertNotNull(ModelProfiles.forUplusId("2008610800820324021200118012560000000000000000000000000000000040"))
        assertNull(ModelProfiles.forUplusId("00000000000000008080000000041410"))
    }

    @Test
    fun `heat is offered only when the unit reports a heat pump`() {
        val p = ModelProfiles.SHARED_WALL_SPLIT
        assertTrue(AcMode.HEAT in CapabilityResolver.resolve(p, status(heatCapable = true))!!.modes)
        assertFalse(AcMode.HEAT in CapabilityResolver.resolve(p, status(heatCapable = false))!!.modes)
    }

    @Test
    fun `nothing is controllable before the unit has reported`() {
        assertNull(CapabilityResolver.resolve(ModelProfiles.SHARED_WALL_SPLIT, null))
    }

    @Test
    fun `features absent from the report are not offered`() {
        val s = status(horizontal = null, eco = null, selfCleaning = null).copy(features = emptyMap())
        val caps = CapabilityResolver.resolve(ModelProfiles.SHARED_WALL_SPLIT, s)!!
        assertTrue(caps.features.isEmpty())
        assertFalse(caps.horizontalSwing)
        assertTrue(caps.ecoLevels.isEmpty())
        assertFalse(caps.selfClean)
    }

    @Test
    fun `unknown profile offers only the core block`() {
        val caps = CapabilityResolver.resolve(null, status())!!
        assertTrue(caps.features.isEmpty())
        assertFalse(caps.selfClean)
        assertTrue(caps.verticalSwing)
    }
}

class CommandValidationTest {
    private val caps = CapabilityResolver.resolve(ModelProfiles.SHARED_WALL_SPLIT, status())!!

    @Test
    fun `temperature range and step`() {
        assertNull(CapabilityResolver.validate(AcCommand.SetTargetTemperature(16.0), caps, status()))
        assertNull(CapabilityResolver.validate(AcCommand.SetTargetTemperature(30.0), caps, status()))
        assertTrue(CapabilityResolver.validate(AcCommand.SetTargetTemperature(31.0), caps, status()) is AcError.InvalidValue)
        assertTrue(CapabilityResolver.validate(AcCommand.SetTargetTemperature(15.0), caps, status()) is AcError.InvalidValue)
        assertTrue(CapabilityResolver.validate(AcCommand.SetTargetTemperature(22.5), caps, status()) is AcError.InvalidValue)
    }

    @Test
    fun `unsupported mode is refused`() {
        val coolOnly = CapabilityResolver.resolve(ModelProfiles.SHARED_WALL_SPLIT, status(heatCapable = false))!!
        assertTrue(CapabilityResolver.validate(AcCommand.SetMode(AcMode.HEAT), coolOnly, status()) is AcError.NotSupported)
    }

    @Test
    fun `auto fan in fan mode is refused`() {
        assertTrue(CapabilityResolver.validate(AcCommand.SetFanSpeed(FanSpeed.AUTO), caps, status(mode = AcMode.FAN)) is AcError.InvalidValue)
        assertNull(CapabilityResolver.validate(AcCommand.SetFanSpeed(FanSpeed.LOW), caps, status(mode = AcMode.FAN)))
    }

    @Test
    fun `self-clean preconditions from the model's own modifiers`() {
        assertNull(CapabilityResolver.validate(AcCommand.StartSelfClean, caps, status()))
        assertTrue(CapabilityResolver.validate(AcCommand.StartSelfClean, caps, status(power = false)) is AcError.InvalidValue)
        assertTrue(CapabilityResolver.validate(AcCommand.StartSelfClean, caps, status(mode = AcMode.AUTO)) is AcError.InvalidValue)
        assertTrue(CapabilityResolver.validate(AcCommand.StartSelfClean, caps, status(sleep = true)) is AcError.InvalidValue)
        assertTrue(CapabilityResolver.validate(AcCommand.StartSelfClean, caps, status(selfCleaning = true)) is AcError.InvalidValue)
    }

    @Test
    fun `nothing is writable on an unrecognised layout`() {
        val s = status(writable = false)
        val c = CapabilityResolver.resolve(ModelProfiles.SHARED_WALL_SPLIT, s)!!
        assertTrue(CapabilityResolver.validate(AcCommand.SetPower(false), c, s) is AcError.UnsupportedLayout)
    }

    @Test
    fun `command read-back check`() {
        assertTrue(AcCommand.SetPower(true).isReflectedIn(status(power = true)))
        assertFalse(AcCommand.SetTargetTemperature(20.0).isReflectedIn(status(temp = 24.0)))
        assertTrue(AcCommand.SetEcoLevel(EcoLevel.OFF).isReflectedIn(status()))
    }
}

