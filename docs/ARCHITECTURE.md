# Architecture

Multi-brand since 2.1; automatic manufacturer-account key retrieval since 2.2. The 2.0 Haier structure is kept; brand-specific code moved behind a
protocol adapter interface so new manufacturers are added without changing the core.

```
app (Android)                        core-protocol (pure Kotlin)                 brand adapters (pure Kotlin)
┌──────────────────────────┐         ┌───────────────────────────────────┐      ┌──────────────────────────────┐
│ Compose UI + ViewModels  │────────▶│ AcConnectionCoordinator           │      │ driver-haismart              │
│  Dashboard, Pairing,     │commands │  states, single-session lock,     │      │  HaierUssAdapter             │
│  Devices, Settings, Help,│◀────────│  polling, rediscovery, auto-pair, │─────▶│   UdiscoveryScanner (UDP 7083)│
│  Sign-in, Diagnostics    │ state   │  read-back command verification   │      │   HaierUssClient (TCP 56800) │
├──────────────────────────┤         │ ProtocolRegistry ─ AcProtocolAdapter     ├──────────────────────────────┤
│ PairedDeviceStore        │◀───────▶│ NetworkDiscoveryManager           │      │ driver-gree                  │
│  JSON + Keystore AES-GCM │         │ DeviceStore / NetworkMonitor      │      │  GreeAdapter (auto pairing)  │
│ AndroidNetworkMonitor    │────────▶│ SubnetMath                        │      │   GreeDiscovery, GreeClient  │
│ DiagnosticsLogger        │         └───────────────────────────────────┘      │   (UDP 7000, AES ECB/GCM)    │
└──────────────────────────┘                                                    └──────────────────────────────┘
      core-domain: AcStatus, AcCommand, AcCapabilities, AcError, AcBrands (brand registry), ProtocolIds,
                   ModelProfiles (Haier), CapabilityResolver, PairedDevice
      driver-haismart/cloud: HaierAccount (CloudAccountProvider), HaierCloudClient (HTTPS sign-in,
                   token refresh, device list), HaierKeyGateway (MQTT 3.1.1 over TLS, per-device key)
```

## Requirement components (REQUIREMENTS.md §5) → code

| Component | Where |
|---|---|
| Network Discovery Manager | `AndroidNetworkMonitor` (Wi-Fi state, subnet) + `NetworkDiscoveryManager` (runs every adapter's discovery in parallel, merges, de-duplicates by device ID) |
| Brand and Protocol Registry | `AcBrands` (13 brands, priorities, status) + `ProtocolRegistry` (protocol id → adapter) |
| Device Identification Service | Each adapter's `DeviceDiscovery` returns brand, model, name, protocol and device ID; Haier adds its product profile (`ModelProfiles`) |
| Authentication and Provisioning Manager | `AcProtocolAdapter.descriptor.credentialMode` + `provision()`; `CloudAccountProvider` for brands whose key comes from the manufacturer account (`HaierAccount`); the coordinator provisions, and re-provisions once, for AUTOMATIC and ACCOUNT brands |
| Protocol Adapter Layer | `AcProtocolAdapter` implementations: `HaierUssAdapter`, `GreeAdapter` |
| Unified AC Device Interface | `LocalAcClient` + `AcCommand` / `AcStatus` / `AcCapabilities` |
| Device Manager | `PairedDeviceStore` (many ACs, names, protocol, brand) + Devices screens |
| Secure Credential Store | `AndroidKeyStoreCipher` (AES-256-GCM, non-exportable Keystore key) for AC keys (`PairedDeviceStore`) and account sessions (`EncryptedAccountSessions`) |
| Connection Manager | `AcConnectionCoordinator` |
| Remote Control UI | `ui/dashboard` (controls shown only from `AcCapabilities`) |

## Adding a brand

1. New Gradle module `driver-<brand>` depending on `core-protocol`.
2. Implement `DeviceDiscovery`, `LocalAcClient` and `AcProtocolAdapter` (choose a `CredentialMode`).
   If the key comes from the manufacturer's cloud, implement `CloudAccountProvider`, set
   `accountProviderId`, and add the provider to `RoomAcApplication.accounts`. The sign-in UI is generic.
3. Add a constant to `ProtocolIds` (never change existing ids: they are stored with devices) and the
   id to the brand in `AcBrands`.
4. Register the adapter in `RoomAcApplication`.
5. Add tests and a row in COMPATIBILITY_MATRIX.md. No UI or coordinator change is needed.

## Connection lifecycle

`AcConnectionCoordinator` runs one loop for the selected device:

1. No device → **No AC added**. Unknown protocol id → **Unsupported** (nothing sent).
2. Wi-Fi off or not joined → **Wi-Fi unavailable** (re-checked on network callbacks and every 5 s).
3. Credential: AUTOMATIC or ACCOUNT and missing → obtained after the AC is found (step 4): from the AC
   itself, or from the signed-in account (not signed in → **Sign-in needed**, waits for the user;
   service unreachable → **Authentication failed** with backoff). A stored key is used without any
   cloud call, so control works offline. Keys are stored encrypted and never shown.
4. Not connected → **Searching**: query the saved address with the device's own protocol; if it no
   longer answers as this device ID, sweep the subnet and save the new address → **AC found** →
   **Authenticating** → **Connected locally**, or **Authentication failed** / **Unsupported** /
   **Offline** with exponential backoff (2 s doubling to 60 s).
5. Connected → poll every N seconds (5–60, default 10). A failed poll → **Reconnecting** → step 4.
6. AUTOMATIC and ACCOUNT brands whose stored key is rejected (unit reset or re-bound) get a fresh key once
   automatically; if that also fails the user sees **Authentication failed**.

Every network operation takes the same lock, so the app never opens two sessions at once. A command
is refused if the selected AC changed while it waited for the lock, so it cannot reach another unit.
Polling pauses when the app is in the background.

## Integration contract (requirement.md §4.1)

Every adapter declares its requirements in `ProtocolDescriptor` (`core-protocol/.../ProtocolAdapter.kt`).
The app reads these declarations instead of branching on brand names.

| Field | Meaning | Haier uSS | Gree Wi-Fi |
|---|---|---|---|
| `id`, `defaultBrand` | Stable protocol id stored with each device; brand assumed when the unit does not say | `haier-uss`, haier | `gree-wifi`, gree |
| `models` | Targeted models / protocol versions, as evidence allows | uSS + classic layout (uPlusId 2008…0040) | Modules answering UDP 7000, v1/v2 ciphers |
| `connection` | `LOCAL` or `CLOUD` (for routine commands) | LOCAL | LOCAL |
| `credentialMode` | `NONE`, `AUTOMATIC` (from the AC on the LAN) or `ACCOUNT` (from the user's manufacturer account via `CloudAccountProvider`) | ACCOUNT | AUTOMATIC |
| `accountProviderId` | Which `CloudAccountProvider` issues the credential | haier-sea | — |
| `externalProvisioning` | The one step only the maker's app can do (app, step, whether it is only first Wi-Fi setup) | HaiSmart: add the AC to the account | GREE+: first Wi-Fi setup |
| `evidence` | requirement.md §8.2 label; only "Verified on hardware" may be presented as working | Implemented, unverified | Implemented, unverified |
| `limitations` | Plain-language limits, shown in the AC's settings | 3 items | 2 items |
| `licence` | Licence and distribution constraint of the protocol source | MIT (haismart-local) | GPL-3.0 reference, review |

Midea registers two adapters with the same contract: `midea-v2` (LOCAL, AUTOMATIC, no account) and
`midea-v3` (LOCAL, ACCOUNT via `midea-nethome`), both "Implemented, unverified", MIT (msmart-ng). One
`MideaDiscovery` scan reports both generations; the V3 adapter's discovery answers direct queries only,
so no unit is listed twice.

Account-level abilities are declared separately by each `CloudAccountProvider.features`
(`AccountFeatures`: register, passwordReset, bindDevice, webRegistration). Registration, password
reset, onboarding an appliance and routine sign-in are separate capabilities. Official web sign-up
pages open only in a Custom Tab and only on hosts that pass `TrustedUrl`.

Lifecycle stages (requirement.md §4.3) map as follows:

| Stage | Where it happens |
|---|---|
| Discover | `NetworkDiscoveryManager` → each adapter's `DeviceDiscovery` |
| Identify | the adapter's `DeviceDiscovery` result |
| Choose connection | `descriptor.connection` |
| Account and provisioning requirements | `credentialMode`, `accountProviderId`, `externalProvisioning` |
| Authenticate | `CloudAccountProvider.signIn` |
| Get credential | `provision()` |
| Validate | proven by a status read before saving (`AcConnectionCoordinator.autoPair`) |
| Expose controls | `capabilities()` |
| Persist | `PairedDeviceStore` / `EncryptedAccountSessions` |
| Reconnect and renew | the coordinator's loop |

The direct discovery query to a saved address is repeated within its timeout, so one lost packet
does not mark a saved AC offline.
