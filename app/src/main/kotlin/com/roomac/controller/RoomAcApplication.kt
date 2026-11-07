package com.roomac.controller

import android.app.Application
import com.roomac.controller.diagnostics.DiagnosticsLogger
import com.roomac.controller.driver.haismart.HaiSmartDriver
import com.roomac.controller.driver.hon.HonDriver
import com.roomac.controller.driver.smartair2.SmartAir2Driver
import com.roomac.controller.protocol.DefaultAcRepository
import com.roomac.controller.protocol.DriverRegistry
import com.roomac.controller.storage.EncryptedConfigStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class RoomAcApplication : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var driverRegistry: DriverRegistry
        private set

    lateinit var repository: DefaultAcRepository
        private set

    lateinit var configStorage: EncryptedConfigStorage
        private set

    override fun onCreate() {
        super.onCreate()
        DiagnosticsLogger.i("RoomAcApplication", "Application starting")

        configStorage = EncryptedConfigStorage(this)

        val builtInDrivers = listOf(
            SmartAir2Driver().apply { setDiagnosticsSink(DiagnosticsLogger) },
            HaiSmartDriver().apply { setDiagnosticsSink(DiagnosticsLogger) },
            HonDriver()
        )
        val allDrivers = builtInDrivers + AdditionalDrivers.getDrivers()

        driverRegistry = DriverRegistry(allDrivers)

        repository = DefaultAcRepository(
            driverRegistry = driverRegistry,
            coroutineScope = appScope
        )

        // Load configuration asynchronously off the main thread
        appScope.launch {
            val saved = configStorage.loadConfiguration()
            if (saved.isConfigured) {
                DiagnosticsLogger.i("RoomAcApplication", "Applying restored config for protocol: ${saved.protocolId} at ${saved.host}")
                repository.applyConfiguration(saved)
            } else {
                DiagnosticsLogger.i("RoomAcApplication", "No saved device configuration found; app in unconfigured state")
            }
        }
    }

    companion object {
        fun from(app: Application): RoomAcApplication = app as RoomAcApplication
    }
}
