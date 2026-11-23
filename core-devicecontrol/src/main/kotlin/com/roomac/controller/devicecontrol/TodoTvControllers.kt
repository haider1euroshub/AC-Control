package com.roomac.controller.devicecontrol

import com.roomac.controller.netscan.DeviceType
import com.roomac.controller.netscan.NetworkDevice

/**
 * TODO(samsung-tv): Samsung Tizen TVs are controlled over a WebSocket remote API that needs an on-TV
 * "Allow" prompt and a saved pairing token. Not implemented: no Samsung TV has been available to capture
 * or verify the exchange against, and RoomAC does not ship protocols it cannot verify. Reports
 * "not yet supported" instead of pretending.
 */
class SamsungTvTodoController : DeviceController {
    override val protocol = "Samsung TV remote (TODO)"
    override fun appliesTo(device: NetworkDevice) = device.vendor == "Samsung" && device.type in setOf(DeviceType.TV, DeviceType.MEDIA)
    override suspend fun connect(device: NetworkDevice, credentials: Credentials?) =
        ConnectResult.Unavailable("Samsung TV remote control is not implemented yet (needs the TV's pairing flow, not yet verified).")
}

/**
 * TODO(lg-tv): LG webOS TVs use a WebSocket API that needs an on-TV pairing prompt and a client key.
 * Not implemented for the same reason as Samsung.
 */
class LgTvTodoController : DeviceController {
    override val protocol = "LG webOS remote (TODO)"
    override fun appliesTo(device: NetworkDevice) = device.vendor == "LG" && device.type in setOf(DeviceType.TV, DeviceType.MEDIA)
    override suspend fun connect(device: NetworkDevice, credentials: Credentials?) =
        ConnectResult.Unavailable("LG webOS TV remote control is not implemented yet (needs the TV's pairing flow, not yet verified).")
}

/** The registry with every adapter, most specific protocols first. */
fun defaultControllers(http: LanHttp = RawSocketHttp(), cast: CastChannelFactory = TlsCastChannelFactory()): ControllerRegistry =
    ControllerRegistry(
        listOf(
            RokuEcpController(http),
            GoogleCastController(cast),
            UpnpAvController(http),
            OnvifController(http),
            EsclController(http),
            SamsungTvTodoController(),
            LgTvTodoController()
        )
    )
