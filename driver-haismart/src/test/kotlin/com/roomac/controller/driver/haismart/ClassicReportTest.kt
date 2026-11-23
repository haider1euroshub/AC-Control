package com.roomac.controller.driver.haismart

import com.roomac.controller.domain.model.AcCommand
import com.roomac.controller.domain.model.AcFeature
import com.roomac.controller.domain.model.AcMode
import com.roomac.controller.domain.model.EcoLevel
import com.roomac.controller.domain.model.FanSpeed
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassicReportTest {

    @Test
    fun `decodes every confirmed field of a 127-byte report`() {
        val blob = Reports.report(
            Reports.words(power = true, setpoint = 22, vane = 0x0C, mode = 4, fan = 3, turbo = true,
                sleep = true, display = false, horizontal = 7, eco = 6),
            indoorRaw = 53, outdoorRaw = 100
        )
        assertEquals(127, blob.size)
        val s = ClassicReport.decode(blob, nowMs = 1000)!!
        assertTrue(s.power)
        assertEquals(22.0, s.targetTemperatureC, 0.0)
        assertEquals(AcMode.HEAT, s.mode)
        assertEquals(FanSpeed.LOW, s.fanSpeed)
        assertTrue(s.verticalSwing)
        assertEquals(true, s.horizontalSwing)
        assertEquals(26.5, s.indoorTemperatureC!!, 0.0)
        assertEquals(36.0, s.outdoorTemperatureC!!, 0.0)
        assertEquals(true, s.features[AcFeature.TURBO])
        assertEquals(true, s.features[AcFeature.SLEEP])
        assertEquals(false, s.features[AcFeature.QUIET])
        assertEquals(false, s.features[AcFeature.DISPLAY])
        assertEquals(EcoLevel.LEVEL_2, s.ecoLevel)
        assertEquals(false, s.selfCleaning)
        assertEquals(true, s.heatCapable)
        assertTrue(s.writable)
        assertEquals(1000L, s.receivedAtEpochMs)
    }

    @Test
    fun `power is bit 0 only, other flags never read as on`() {
        val s = ClassicReport.decode(Reports.report(Reports.words(power = false, health = true, turbo = true, quiet = true)))!!
        assertFalse(s.power)
    }

    @Test
    fun `parked vane positions are not reported as sweeping`() {
        for (code in listOf(0, 2, 4, 6, 8, 10)) {
            assertFalse("code $code", ClassicReport.decode(Reports.report(Reports.words(vane = code)))!!.verticalSwing)
        }
        for (code in listOf(0, 3, 4, 5, 6)) {
            assertEquals("code $code", false, ClassicReport.decode(Reports.report(Reports.words(horizontal = code)))!!.horizontalSwing)
        }
    }

    @Test
    fun `fan code ignores the special-mode bit above it`() {
        val w = Reports.words(fan = 2)
        w[2] = (w[2].toInt() or 0x08).toByte() // bit 3 of byte 94 belongs to specialMode
        assertEquals(FanSpeed.MEDIUM, ClassicReport.decode(Reports.report(w))!!.fanSpeed)
    }

    @Test
    fun `absent sensors decode as absent, not as -64 degrees`() {
        val s = ClassicReport.decode(Reports.report(indoorRaw = 0, outdoorRaw = 0))!!
        assertNull(s.indoorTemperatureC)
        assertNull(s.outdoorTemperatureC)
    }

    @Test
    fun `cooling-only flag is read from the unit's own report`() {
        assertEquals(false, ClassicReport.decode(Reports.report(coolingOnly = true))!!.heatCapable)
    }

    @Test
    fun `125-byte layout reads sensors two bytes earlier`() {
        val blob = Reports.report(Reports.words(wordCount = 5), indoorRaw = 48, outdoorRaw = 90)
        assertEquals(125, blob.size)
        val s = ClassicReport.decode(blob)!!
        assertEquals(24.0, s.indoorTemperatureC!!, 0.0)
        assertEquals(26.0, s.outdoorTemperatureC!!, 0.0)
        assertTrue(s.writable)
        assertEquals(10, ClassicReport.baseline(blob)!!.size)
    }

    @Test
    fun `unknown layout decodes the core block read-only`() {
        val blob = Reports.report() + ByteArray(4)
        val s = ClassicReport.decode(blob)!!
        assertFalse(s.writable)
        assertTrue(s.power)
        assertNull(s.indoorTemperatureC)
        assertNull(ClassicReport.baseline(blob))
    }

    @Test
    fun `alarm, extended and refusal frames are never decoded as status`() {
        assertNull(ClassicReport.decode(Reports.report(command = byteArrayOf(0x0F, 0x5A))))
        assertNull(ClassicReport.decode(Reports.report(command = byteArrayOf(0x7D, 0x01))))
        assertNull(ClassicReport.decode(Reports.wrap(EppFrame.build(EppFrame.TYPE_REFUSED, byteArrayOf(0, 0)))))
        assertEquals(ClassicReport.Kind.ALARM, ClassicReport.classify(Reports.report(command = byteArrayOf(0x0F, 0x5A))))
    }

    @Test
    fun `group-set seeded from a report reproduces that report's words`() {
        val words = Reports.words(setpoint = 25, mode = 2, fan = 1, quiet = true, eco = 5)
        assertArrayEquals(words, ClassicReport.baseline(Reports.report(words)))
    }
}

class ClassicGroupSetTest {
    private val base = Reports.words(power = true, setpoint = 24, mode = 1, fan = 5, display = true, eco = 7, horizontal = 7)

    private fun decodeAfter(cmd: AcCommand) = ClassicReport.decode(Reports.report(ClassicGroupSet.apply(base, cmd)))!!

    @Test
    fun `each command changes only its own field`() {
        val before = ClassicReport.decode(Reports.report(base))!!
        val commands = listOf(
            AcCommand.SetPower(false), AcCommand.SetTargetTemperature(18.0), AcCommand.SetMode(AcMode.DRY),
            AcCommand.SetFanSpeed(FanSpeed.HIGH), AcCommand.SetVerticalSwing(true), AcCommand.SetHorizontalSwing(false),
            AcCommand.SetFeature(AcFeature.SLEEP, true), AcCommand.SetFeature(AcFeature.DISPLAY, false),
            AcCommand.SetFeature(AcFeature.HEALTH, true), AcCommand.SetEcoLevel(EcoLevel.OFF), AcCommand.StartSelfClean
        )
        for (cmd in commands) {
            val after = decodeAfter(cmd)
            assertTrue("$cmd not applied", cmd.isReflectedIn(after))
            // Everything the command does not own is untouched.
            if (cmd !is AcCommand.SetPower) assertEquals(cmd.toString(), before.power, after.power)
            if (cmd !is AcCommand.SetTargetTemperature) assertEquals(before.targetTemperatureC, after.targetTemperatureC, 0.0)
            if (cmd !is AcCommand.SetMode) assertEquals(before.mode, after.mode)
            if (cmd !is AcCommand.SetFanSpeed) assertEquals(before.fanSpeed, after.fanSpeed)
            if (cmd !is AcCommand.SetEcoLevel) assertEquals(before.ecoLevel, after.ecoLevel)
            if (cmd !is AcCommand.SetHorizontalSwing) assertEquals(before.horizontalSwing, after.horizontalSwing)
        }
    }

    @Test
    fun `turbo and quiet are sent as a consistent pair`() {
        val quietOn = ClassicGroupSet.apply(Reports.words(quiet = true), AcCommand.SetFeature(AcFeature.TURBO, true))
        val s = ClassicReport.decode(Reports.report(quietOn))!!
        assertEquals(true, s.features[AcFeature.TURBO])
        assertEquals(false, s.features[AcFeature.QUIET])
        val back = ClassicReport.decode(Reports.report(ClassicGroupSet.apply(quietOn, AcCommand.SetFeature(AcFeature.QUIET, true))))!!
        assertEquals(false, back.features[AcFeature.TURBO])
        assertEquals(true, back.features[AcFeature.QUIET])
    }

    @Test
    fun `entering fan-only from auto fan selects low fan`() {
        val s = decodeAfter(AcCommand.SetMode(AcMode.FAN))
        assertEquals(AcMode.FAN, s.mode)
        assertEquals(FanSpeed.LOW, s.fanSpeed)
    }

    @Test
    fun `setpoints outside 16-30 or fractional are refused before sending`() {
        assertThrows(GroupSetException::class.java) { ClassicGroupSet.apply(base, AcCommand.SetTargetTemperature(31.0)) }
        assertThrows(GroupSetException::class.java) { ClassicGroupSet.apply(base, AcCommand.SetTargetTemperature(15.0)) }
        assertThrows(GroupSetException::class.java) { ClassicGroupSet.apply(base, AcCommand.SetTargetTemperature(22.5)) }
        assertNotNull(ClassicGroupSet.apply(base, AcCommand.SetTargetTemperature(30.0)))
    }

    @Test
    fun `self-clean field is refused on a block too short to carry it`() {
        assertThrows(GroupSetException::class.java) { ClassicGroupSet.apply(Reports.words(wordCount = 4), AcCommand.StartSelfClean) }
    }
}
