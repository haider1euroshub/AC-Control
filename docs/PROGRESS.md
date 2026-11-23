# Development plan and progress

Current feature status, flags and owner steps: see `STATUS.md` (updated 2026-11-19).

## Live tracker (requirement.md §18.5, updated 2026-11-14, version 2.5.0)

Status labels: Verified on hardware · Automated test only · Implemented, unverified · Research only ·
Blocked · Unsupported · Unknown. "Tests" are automated unless marked HW (real hardware).

| ID | Requirement | Status | Files | Tests / evidence | Remaining work | Blocker | Next action |
|---|---|---|---|---|---|---|---|
| R-0 | Phase 0 audit and baseline | Done | — | 190 tests passing on arrival; secret scan clean | — | — | — |
| R-HA-1 | Haier discovery (§5.1) | Verified on hardware | `driver-haismart/Udiscovery.kt` | HW: subnet scan and direct query pass (2026-11-14, also after today's changes) | — | — | — |
| R-HA-2 | Haier uSS handshake | Verified on hardware (handshake only) | `HaierUssClient.kt` | HW: accepted, key version 58 | — | — | — |
| R-HA-3 | HaiSmart sign-in (§6) | Implemented, unverified; diagnosed | `cloud/HaierCloudClient.kt`, `cloud/HaierAccount.kt` | Live probe: identity accepted (30032 for a made-up account; 403 for a wrong key). 6 new tests. Two bugs fixed (reason discarded; app refusal cost saved sessions). Region list 11→102 | Owner re-run with the new diagnostics | Owner credentials | Owner runs HAISMART_AUTH_INVESTIGATION.md "Owner action" |
| R-SEC-1 | No vendor-app impersonation in releases (§1.4(8), §4.3, §13) | Implemented | `app/build.gradle.kts`, `app/src/testRelease/.../ReleaseVendorIdentityTest.kt` | `:app:testReleaseUnitTest` 1/1 pass; `:app:assembleRelease` and `:app:lintRelease` pass (2026-11-14) | Owner decision on the Haier and Midea cloud routes | Authorised decision (§13) | Owner reads HAISMART_AUTH_INVESTIGATION.md "Route status" (H9) |
| R-HA-4 | Device list, key retrieval, status read | Automated test only | `cloud/*`, `HaierUssClient.kt` | Simulated services | Live run | R-HA-3 | After R-HA-3 |
| R-HA-5 | Power/temperature/mode/fan on the AC | Automated test only | `HaierUssClient.kt` | Simulated AC | Phone test | Key + phone | HARDWARE_TESTS.md Procedure 3 |
| R-HA-6 | Exposed key rotation | Blocked | — | Key not in repo or history (scan) | Re-pair in HaiSmart to make Haier issue a new key (unverified mechanism); RoomAC renews automatically | Owner action | Optional: owner re-adds the AC in HaiSmart |
| R-GR-1 | Gree driver software | Automated test only | `driver-gree` | 29 tests + opt-in HW test | Hardware | No Gree unit | Test on any Gree unit |
| R-GR-2 | Gree licence lineage (§5.2) | Done | `docs/LICENCES.md` | greeclimate ce5c9e (GPL-3.0); v1 facts confirmed against Apache-2.0 homebridge-gree-air-conditioner ecd0b | Legal review of v2 (GCM) facts | — | Owner review before distribution |
| R-MI-1 | Midea V2/V3 LAN protocol (§5.3) | Implemented, unverified | `driver-midea/*` | 31 tests incl. msmart's captured traffic and simulated V2/V3 modules over sockets | Capability query (0xB5); hardware | No Midea unit (scan found none) | Test on a Midea unit |
| R-MI-2 | Midea V3 token via the user's own NetHome Plus account | Implemented, unverified | `MideaAccount.kt` | 7 tests (signing, hashing, byte orders, refusal, offline, stay-signed-in) | Live run | No V3 unit | With a V3 unit |
| R-AR-1 | Integration contract (§3.4, §4.1) | Done | `ProtocolAdapter.kt` | Descriptor tests for Haier, Gree | — | — | — |
| R-AR-2 | Separate account features (registration ≠ sign-in) | Done | `CloudAccount.kt` | TrustedUrlTest, descriptor tests | — | — | — |
| R-UX-1 | UI redesign, motion, themes, accessibility | Implemented; checked on the emulator | `app/ui/**` | Emulator screenshots (light and dark) | Phone check | Phone | Procedure 3 |
| R-BR-1 | Remaining 10 brands (§5.4–5.13) | Research only | `docs/MANUFACTURER_CAPABILITIES.md` | Records with sources | Captures from real units; LG/Samsung developer access | Hardware, developer accounts | Per brand, see matrix |
| R-DOC | Required documents (§16) | Done | `docs/*`, `requirement.md` | — | Keep updated | — | — |

Requirement history: this log follows several successive specifications. The current one is
`requirement.md`. Older phases below refer to the specs current at the time.

Baseline: docs/REQUIREMENTS.md (Universal Android AC Remote App), plus the research study reviewed
in docs/RESEARCH_FINDINGS.md. The earlier Haier-only spec stays in
`requirement.md` at the project root.

## Phase 1: existing project audit (done 2026-11-12)

The project found was **RoomAC 2.0.0**: Kotlin, Jetpack Compose, Gradle 8.9, AGP 8.5.2, modules
`core-domain`, `core-protocol`, `driver-haismart`, `app`. Build and 106 tests passed on arrival.
Most of it was uncommitted work on top of commit `399997b`.

| Area | State on arrival | Gap against the universal requirements |
|---|---|---|
| Discovery | Haier UDISCOVERY only; broadcast + subnet sweep; rediscovery by device ID | Single brand; no shared discovery manager |
| Identification | Device ID + Haier uPlusId profile | No brand/protocol on devices |
| Auth/provisioning | Haier key typed by the user; Keystore storage | No automatic provisioning path |
| Protocol layer | `LocalAcClient` / `DeviceDiscovery` interfaces, but the coordinator, capability rules and UI were Haier-specific | No adapter or registry |
| Unified controls | Power, mode, temperature, fan, swing, Turbo/Quiet/Sleep/Health/Display, ECO, self-clean; read-back verification | Good basis; kept |
| Multiple ACs | Store, switcher, rename, remove | Connection status only for the selected AC |
| UI | Compose, large controls, plain errors | Haier wording; device IDs and firmware shown in pairing |
| Docs | Haier-only | No compatibility matrix or brand roadmap |

Decision: **extend, not rewrite.** The coordinator, storage, UI and Haier driver were kept, and
brand-specific parts moved behind an adapter interface.

## Phase 2: protocol architecture (done)

- `AcProtocolAdapter`, `ProtocolRegistry`, `NetworkDiscoveryManager`, `CredentialMode`,
  `ProvisionResult` in `core-protocol`.
- `AcBrands` registry with all 13 brands, market priority, integration order and status.
- `PairedDevice` gains `protocolId` and `brand`. 2.0 records migrate to `haier-uss` automatically.
- Coordinator routes through adapters; automatic pairing and one automatic re-pair; guard against a
  command reaching a different AC after a device switch.
- Haier fan-mode rule moved from global validation into the Haier capabilities flag.
- COMPATIBILITY_MATRIX.md created.

## Phase 3: initial brand integrations (in progress)

| Brand | State |
|---|---|
| Haier | Adapter around the existing, unchanged uSS driver. Partially tested on hardware (2.0). |
| Gree | New `driver-gree`: discovery, automatic bind (v1/v2 ciphers), status, commands with read-back. 27 tests incl. an OpenSSL vector and a simulated module. **Not tested on a real unit.** |
| Midea, AUX, TCL, Dawlance, Daikin, Orient, PEL | Not started in code. The next step for each is a discovery capture from a real unit to identify its Wi-Fi module, then an adapter. |

## Phases 4–8

| Phase | Done | Remaining |
|---|---|---|
| 4 Discovery | Network detection, per-brand discovery, merge, de-duplication by device ID, multiple ACs | mDNS/SSDP (when a brand needs it); background rediscovery of non-selected ACs |
| 5 Auth | Keystore storage; automatic Gree pairing and re-pairing; Haier in-app account sign-in and automatic key retrieval (2.2.0) | Account authorisation flows for brands that need a cloud token (Midea v3, possibly others) |
| 6 Local control | Two adapters, unified controls, read-back verification, status sync | Further adapters |
| 7 UI | Brand-neutral pairing with one-tap automatic connect; technical IDs removed from the found list | Per-device live status in the device list; larger-text elderly mode review on a real phone |
| 8 Testing | 141 automated tests | All real-device tests (TEST_REPORT.md) |

## Phase: zero-manual-configuration pass (2026-11-13, version 2.2.0)

Against the 2026-11-13 specification ("RoomAC — Universal Smart AC Remote").

| Spec item | Done |
|---|---|
| §3.4 / §4.2 Automatic credential retrieval (Haier) | New `CredentialMode.ACCOUNT` + `CloudAccountProvider`. `HaierAccount`: in-app HaiSmart sign-in, refresh token stored Keystore-encrypted (password never stored), account device list, AC matched by device ID, key fetched over Haier's MQTT/TLS gateway, proven by a local status read, re-fetched once if the AC rejects it. 26 new tests (independent crypto vectors, simulated service and gateway, MQTT framing). **Not yet run against the live service.** |
| §17 Prohibitions (no IP, MAC, serial, key, protocol entry) | Removed manual IP entry, local-key entry, model/serial/product-code entry and the camera label scanner (and CAMERA permission). IP addresses removed from the main screens and error messages; technical details are behind "Show technical details" and in Troubleshooting. |
| §3.1 / §8.3A Scan on open | First launch opens the search directly. |
| §7 Rooms | `PairedDevice.room`, chosen at pairing and in AC settings, shown in lists and the switcher. |
| §8.3F Settings | New Settings screen: accounts (sign in/out), appearance (system/light/dark), status-update interval, privacy, help, troubleshooting, about. |
| §8.2 Elderly-friendly | Type scale +12 %, larger buttons (48–56 dp), plain-language copy throughout. |
| §11 Security | Account sessions encrypted like keys; log redaction extended to token fields in JSON (tested); HaiSmart app identity kept out of Git (`local.properties` → `BuildConfig`). |

Tests: 166 automated (core-domain 12, core-protocol 25, driver-haismart 85 + 5 opt-in, driver-gree 27,
app 17). The 8 label-parser/key-format tests were removed with that code. Lint clean.

## Phase: persistent Haier authentication and key renewal (2026-11-13, version 2.3.0)

- Cause of "This AC is not in the signed-in HaiSmart account": the app refused to ask Haier's key service for any AC missing from the account's device list. Now the key is requested anyway (the service enforces ownership; the AC proves the key).
- Silent renewal: access token, then refresh token, then (optional, default on, Keystore-encrypted) saved password. The user is asked only when Haier refuses all of these; a refused password is deleted, never retried.
- Key renewal: new key proven on the AC before replacing the old one; "Refreshing AC connection…" state; also triggered from commands; one attempt per success, no cloud loops.
- Multiple HaiSmart accounts, per-AC account link, per-account sign-out.
- AC registration inside RoomAC: not possible without inventing Haier APIs; pairing offers "Sign in with that account" and "Open the HaiSmart app" instead (DISCOVERY_AND_AUTH.md).
- No backend exists or was added: everything runs on the phone.
- Tests: 180 automated, all simulated.

## Phase: 2026-11-14 requirements pass (version 2.4.0)

Against the 2026-11-14 specification ("RoomAC — Complete Product & Development Requirements", given in
the session; not copied into the repo).

- **Audit:** on arrival, build, lint and 180 tests passed (5 hardware tests opt-in). Architecture as
  above. The spec's "redirects to HaiSmart for sign-in" no longer applied: sign-in has been in-app since
  2.2.0. The only hand-off left is adding a *new* AC to an account.
- **Haier registration investigated:** haismart-local (read in full) has no account-creation,
  verification-code, password-reset or device-binding endpoint, and Haier publishes none. Not
  implemented, because that would mean inventing endpoints. The shared `CloudAccountProvider` now
  declares `AccountFeatures` (register / passwordReset / bindDevice) so registration stays a separate
  operation from sign-in. Haier declares all three false. The sign-in form explains why (tested).
- **Capability matrix:** new [MANUFACTURER_CAPABILITIES.md](MANUFACTURER_CAPABILITIES.md) answers the
  ten §4.5 questions for all 13 brands, labelling every answer Code, HW, Lead or Unknown.
- **UI redesign:** design tokens in `Theme.kt`: one brand blue, neutral surfaces, semantic
  success/warning/error colours, corner radii 12/14/20/28 dp, motion tokens that switch to instant when
  Android animations are off. Cards have hairline borders and no shadows. Screen transitions are
  direction-aware. The remote has a single climate panel (power, 76 sp animated temperature, 64 dp
  steppers, "sending" vs "target" state). Mode and fan use large labelled options with icons. The
  dashboard has an empty state. Pairing steps and progress are animated, and found ACs fade in. The
  device list has status dots. Light and dark were checked on the emulator.
- **Not done this pass:** no new manufacturer driver. Every remaining brand first needs a capture from
  a real unit, an account decision, or both (see the matrix).
- Tests: 181 automated, all pass; lint clean.
- **Follow-up spec (same day, registration methods A/B/C):** official websites investigated for every
  brand (MANUFACTURER_CAPABILITIES.md, "Account registration methods"). Haier has no web sign-up for
  HaiSmart accounts: the haier.com/ph sign-up posts to the website-member system, not uplussea.
  Added `AccountFeatures.webRegistration` and `TrustedUrl` (https, declared hosts only, look-alikes
  refused; 5 tests), plus a Custom Tab launcher and a "Create a … account" button shown only when a
  provider declares a verified page. None does yet. Tests: 186, all pass.

## Phase: local-first directive (2026-11-14, same version 2.4.0)

- **Hardware (Haier, key-free):** subnet scan, direct discovery and handshake pass on
  540853BD1D41 (TEST_REPORT.md). A real bug was found and fixed: one lost UDP discovery request made
  the saved-address check report the AC missing. It is now repeated within the timeout (unit test with
  a dropping responder; 5/5 on hardware).
- **Hardware (Gree):** new opt-in `GreeHardwareSmokeTest` (scan, bind, read; no commands). No Gree
  unit is on this network.
- **Architecture:** `ProtocolDescriptor` now states `connection` (LOCAL / CLOUD; all current
  adapters LOCAL) and `externalProvisioning` (the one step only the maker's app can do: Haier, adding a
  new AC to the account in HaiSmart; Gree, first Wi-Fi setup in GREE+). Pairing explains these from the
  adapter instead of Haier-specific code, and searches again automatically when the user returns from
  the maker's app with nothing found yet.
- **Midea:** researched in full (MANUFACTURER_CAPABILITIES.md). MIT, local control, V3 token/key
  from the user's own NetHome Plus account. Not implemented until Haier is verified on hardware.
- Tests: 190 automated, all pass (6 opt-in hardware tests skipped in normal runs); lint clean.

## Phase: spec re-run (2026-11-14, requirement.md unchanged)

- **Phase 0 baseline:** `gradlew test lint assembleDebug`: BUILD SUCCESSFUL. 245 test cases across all
  modules and variants, 0 failed, 8 skipped (opt-in hardware and live-service tests). Lint: no issues.
  No pre-existing failures. Large uncommitted user/agent work tree kept as found; no commits made.
- **Compliance finding (H9):** the Haier and Midea cloud sign-ins use the vendor apps' own app
  identities. That conflicts with §1.4(8) and §4.3. Release builds now leave them out unless the owner
  opts in (R-SEC-1); debug builds are unchanged. R-HA-3, R-HA-4 and R-MI-2 stay as they are for
  research, but none of them may be counted toward a release until §13 sign-off.

## Known issues and limitations

- No physical phone and no Gree unit have been tested. Gree support is implemented, not verified.
- Only the selected AC is connected and polled; the others show "Ready" or "Not connected yet", not live status. Switching is one tap.
- Haier key retrieval uses the HaiSmart app's own app identity against an API Haier has not published for third parties. It is kept out of Git but is inside every APK built with it. Haier could change or block it. Owner to confirm before public distribution (DISCOVERY_AND_AUTH.md).
- Some Gree Wi-Fi modules are reportedly cloud-only; those units cannot be found or controlled locally.
- Gree `TemSen` offset and Heat availability are heuristics until confirmed on hardware.
- The Gree implementation used a GPL-3.0 project as a protocol reference (facts only). Licence review is advised before distribution (THIRD_PARTY_NOTICES.md).
- The Haier account flow (sign-in, renewal, key rotation) has not been run against the live service or a real key change; first real check: the account smoke test (BUILD_AND_INSTALL.md).
- "Stay signed in" stores the HaiSmart password (encrypted). It is the only way to avoid asking again when Haier ends a session, but it is a trade-off the owner may want to default off.
