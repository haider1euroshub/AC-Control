# ANDROID COMPATIBILITY & PERMISSION AUDIT

**Target SDK:** 34 (Android 14)  
**Min SDK:** 26 (Android 8.0 Oreo)  

---

## 1. Local Network Permission Requirements
- On Android (up to API 34), standard outbound raw TCP socket connections (`java.net.Socket`) to a private local IPv4 address (such as `192.168.x.x` port `56800`) require standard `android.permission.INTERNET` and `android.permission.ACCESS_NETWORK_STATE`.
- Unlike iOS (which introduced a specialized Local Network Privacy prompt in iOS 14), Android does **not** mandate a specialized runtime prompt for direct unicast client TCP socket connections.
- Specialized permissions (`NEARBY_WIFI_DEVICES`) are only mandated when using Wi-Fi Direct (P2P), Wi-Fi Aware, or active local hotspot scanning (`WifiManager.startScan`). Since this application performs unicast TCP socket communication directly to the user-entered IPv4 address, `INTERNET` and `ACCESS_NETWORK_STATE` are sufficient.
- The previous manifest contained `CHANGE_NETWORK_STATE`, `ACCESS_WIFI_STATE`, and `android:usesCleartextTraffic="true"`. Because raw sockets do not consult network security config cleartext rules and network state modification was unused, they have been removed cleanly.

