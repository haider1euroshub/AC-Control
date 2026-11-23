# Licences, attribution and distribution constraints

Checked 2026-11-14. Full attribution texts are in [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md).
This is an engineering inventory, not legal advice.

## Shipped dependencies (inside the APK)

| Dependency | Version | Licence |
|---|---|---|
| AndroidX Core, Activity Compose, Lifecycle (runtime, compose, process, viewmodel-compose) | 1.13.1 / 1.9.1 / 2.8.4 | Apache 2.0 |
| AndroidX Browser (Custom Tabs) | 1.8.0 | Apache 2.0 |
| Jetpack Compose (BOM 2024.06.00: UI, Material 3, Material Icons Extended) | BOM 2024.06.00 | Apache 2.0 |
| Kotlin standard library | 2.0.20 | Apache 2.0 |
| kotlinx.coroutines (core, android) | 1.8.1 | Apache 2.0 |
| kotlinx.serialization JSON | 1.6.3 | Apache 2.0 |

## Test-only dependencies (not shipped)

| Dependency | Version | Licence |
|---|---|---|
| JUnit | 4.13.2 | EPL 1.0 |
| kotlinx-coroutines-test | 1.8.1 | Apache 2.0 |
| org.json (JSON-java) | 20240303 | Public domain (since the 2023 releases) |

## Protocol sources

| Integration | Source | Licence | How used | Distribution constraint |
|---|---|---|---|---|
| Haier uSS + HaiSmart account (`driver-haismart`) | haismart-local (github.com/enapt/haismart-local) | MIT | Kotlin adaptation of protocol code and facts | Keep the MIT notice (done). **Separate risk:** the HaiSmart app identity (app ID/key) and Haier's unpublished API are not licensed to RoomAC; Haier could object or block them. The owner must decide before public release. |
| Gree Wi-Fi (`driver-gree`) | greeclimate (github.com/cmroche/greeclimate), revision `8ce5c9e` (2026-11-13) | **GPL-3.0** | Protocol facts cross-checked only; no code copied | v2 (GCM) facts rest on this source alone. Have it reviewed before distribution. |
| Gree Wi-Fi v1 (`driver-gree`) | homebridge-gree-air-conditioner (github.com/kongkx/homebridge-gree-air-conditioner), revision `72ecd0b` | Apache 2.0 | Independent confirmation on 2026-11-14 of the scan message, UDP 7000, AES-128-ECB generic key (hash-compared) and property names; no code copied | None beyond good practice. Reduces the GPL dependency to the v2 cipher details. |
| Midea V2/V3 (`driver-midea`) | midea-msmart / msmart-ng (github.com/mill1000/midea-msmart), revision `dd09947` | MIT | Kotlin adaptation of protocol code; captured test vectors from its test suite | Keep the MIT notice (done). Same app-identity risk as Haier: the NetHome Plus app key (in `local.properties`, never in Git) and an unpublished API. The library's built-in shared accounts are not used. |

## Secrets that are not licences

- **HaiSmart app identity:** read from git-ignored `local.properties` into `BuildConfig`. It is in
  any APK built with it, never in Git.
- **Release signing:** git-ignored `keystore.properties`. No release-signed build has been made.
