# Test report: 2026-11-11

**Build:** `RoomAC-2.0.0-debug.apk` (debug-signed), SHA-256
`e53344944c2b294f3c2bd9654d77ea47a48f9b522ce1d534a41924afd939d6ee`.
**AC:** Haier `HSU-20HFTEX/013WUSDC(OW)-T3`, device ID `540853BD1D41`, module firmware
`e_4.3.00 / R_6.0.01`, at 192.168.18.68 during testing.
**Test hosts:** Windows PC on 192.168.18.139/24 (same Wi-Fi), and an Android 14 emulator
(Pixel 6 image, x86_64) on that PC. **No physical Android phone was available**, and the
AC's **local key was not available**.

## 1. Automated checks: PASS

`./gradlew clean test lintDebug assembleDebug`: BUILD SUCCESSFUL, lint 0 errors / 0 warnings.

| Module | Tests | Result | Covers |
|---|---|---|---|
| core-domain | 20 | PASS | label/barcode parsing and validation, model recognition, capability mapping (heat gated on the unit's report, absent features hidden), command validation (range, step, unsupported mode, fan-mode rules, self-clean preconditions), key format |
| core-protocol | 15 | PASS | every connection state, Wi-Fi off vs not joined, missing key, DHCP rediscovery by device ID, impostor at the saved IP, offline backoff, auth failure, reconnect, unsupported layout, confirmed/rejected/invalid commands, no concurrent sessions |
| driver-haismart | 55 (+4 opt-in hardware, skipped in CI) | PASS | uSS codec incl. a **HELLO_RESP captured from this AC**, documented EPP frames and refusal, the manufacturer's escaping example, AES/MD5 vector computed independently with .NET, wrong/rotated/case-changed key, report decoding (both classic layouts, flags, vane codes, absent sensors, cooling-only flag, alarm/extended exclusion), group-set field isolation, full sessions against a fake AC over real sockets (read, confirm, refusal, ignored write, silence → re-read, wrong key, key rotation, wrong device ID, peer close, closed port, unsupported layout, stale-push seeding), UDISCOVERY parsing of the **anonymised capture from this AC**, subnet math |
| app | 12 | PASS | device persistence across restart, key never stored in plain text, same AC never stored twice, removal erases key, Keystore failure fails closed, key change resets version, corrupt data, error messages carry a next step and no secrets |

## 2. Hardware checks performed

Every check here was key-free and read-only; no control command was sent to the AC.

| Check | Host | Result |
|---|---|---|
| UDISCOVERY query by IP (PowerShell tool and app Kotlin code) | PC | **PASS**: device ID, uPlusId, firmware, port 56800, cloud state 1000 |
| Subnet scan without an IP (app Kotlin code) | PC | **PASS**: AC found in 4.1 s |
| uSS handshake for the device ID (both) | PC | **PASS**: status 1, key version 50 |
| APK installs and launches | Emulator | **PASS**: no crash; empty state shown |
| Pairing: subnet scan | Emulator | Found nothing, **as expected**: the emulator is NATed (10.0.2.x), so it is not on the LAN subnet. The correct guidance was shown. |
| Pairing: manual IP lookup → identify → key-free session check | Emulator → real AC | **PASS**: the APK reached the real AC, identified it (profile, firmware, cloud connected), and its handshake was accepted (key version 50) |
| Wrong local key | Emulator → real AC | **PASS**: "Authentication failed: the local key is not valid"; Continue stays disabled; the key does not appear in logcat |
| Save without key → dashboard | Emulator | **PASS**: shows "Local key required" with the address and an "Enter local key" action; no fake connected state |
| Persistence after force-stop and relaunch | Emulator | **PASS** |
| Help, Diagnostics, Device settings screens | Emulator | **PASS**: no crashes |

An emulator is not a real phone; these results do not replace section 3.

### 2026-11-14 hardware run (from a PC on the same Wi-Fi, 192.168.18.139/24)

| Check | Unit | Result |
|---|---|---|
| Haier subnet scan (no address given) | 540853BD1D41 | **Pass**: found at 192.168.18.68 in 4.1 s |
| Haier direct UDISCOVERY query | 540853BD1D41 | **Failed once** when run right after the sweep (single request lost). Fixed: the query now repeats the request up to 3× within its 2 s timeout. **Pass** 5/5 afterwards, including twice right after a sweep. uPlusId 2008…0040, firmware e_4.3.00 / R_6.0.01. |
| Haier uSS handshake for its device ID | 540853BD1D41 | **Pass**: accepted; the AC reports local key version **58** |
| Haier account sign-in, device list, key fetch, local status read | 540853BD1D41 | **Not run**: needs the owner's HaiSmart credentials (HaierAccountSmokeTest) |
| Haier power / temperature / mode / fan commands | 540853BD1D41 | **Not run**: needs the key; then a phone test (section 3) |
| Gree scan (new GreeHardwareSmokeTest) | none | **No Gree-protocol unit on this network** (4.1 s scan, 0 found). Gree stays unverified. |

## 3. Real-device test matrix (requirement 9.2)

| # | Test | Result | Notes |
|---|---|---|---|
| 1 | Phone and AC on the same Wi-Fi | NOT VERIFIED | No phone available; PC on the same subnet reached the AC |
| 2 | Automatic discovery on open | NOT VERIFIED (app) / PASS (protocol) | Scan code found the AC from the PC; the emulator cannot be on the LAN |
| 3 | Paired AC reconnects after restart | NOT VERIFIED | Persistence PASS on the emulator; reconnection needs the key |
| 4 | Found after IP change | NOT VERIFIED | Covered by automated tests |
| 5 | AC power cycle | NOT VERIFIED | |
| 6 | Wi-Fi lost and restored | NOT VERIFIED | Covered by automated tests |
| 7 | Power on/off | NOT VERIFIED | Needs the local key |
| 8 | Every mode | NOT VERIFIED | Needs the local key |
| 9 | Temperature min/max/normal | NOT VERIFIED | Needs the local key |
| 10 | Every fan speed | NOT VERIFIED | Needs the local key |
| 11 | Vertical and horizontal swing | NOT VERIFIED | Needs the local key |
| 12 | Turbo | NOT VERIFIED | Needs the local key |
| 13 | ECO | NOT VERIFIED | Needs the local key |
| 14 | Sleep | NOT VERIFIED | Needs the local key |
| 15 | Self-clean | NOT VERIFIED | Needs the local key |
| 16 | Other confirmed features (Quiet, Health, Display) | NOT VERIFIED | Needs the local key |
| 17 | Sync after remote-control changes | NOT VERIFIED | Needs the local key |
| 18 | Invalid commands / unsupported functions | PASS (automated) | Not yet on hardware |
| 19 | Background, foreground, process restart | PARTIAL | Restart PASS on the emulator |
| 20 | Local operation with phone internet off | NOT VERIFIED | |

To complete it: get the key (SETUP_GUIDE.md §2), run
`AC_HOST=… AC_DEVICE_ID=540853BD1D41 AC_LOCAL_KEY=… ./gradlew :driver-haismart:test --tests '*HardwareSmokeTest*'`
for a read-only status read, then install the APK on a phone and work through rows 1–20. The
dashboard marks each control "Not yet verified on this AC" until the AC has confirmed it.

## 4. Cloud independence (requirement 9.3)

| Question | Finding |
|---|---|
| Does initial key retrieval need a Haier account / cloud? | **Yes.** The key is issued by Haier's cloud per account. This happens outside the app, once. |
| Does the app make cloud requests during normal control? | **No, by construction:** the only sockets are UDP 7083 and TCP 56800 to LAN addresses; no HTTP client, telemetry or Google services are in the dependency tree (checked). Not yet confirmed with a traffic capture on a phone. |
| Control with phone internet disabled | NOT VERIFIED |
| Does the AC need internet for local control? | NOT VERIFIED here. haismart-local reports the 56800 path is unaffected by cloud loss. The AC was cloud-connected (state 1000) during testing. |

## 5. Known limitations

- Status reading and every control are unverified on the physical AC until the local key is available.
- The app does not fetch the key itself (by design, see PROTOCOL.md); a computer is needed once.
- Control is enabled only for the classic 125/127-byte layouts. If this AC reports another length, it
  shows read-only status and needs a layout capture before control can be enabled.
- Fixed louvre positions, half-degree setpoints, timers and energy telemetry are not offered.
- No production-signed release APK: that needs the owner's keystore (BUILD_AND_INSTALL.md).
- Polling runs only while the app is open; there is no widget or background notification.

---

# Addendum: 2026-11-12 (version 2.1.0, multi-brand)

`gradlew test lintDebug assembleDebug`: BUILD SUCCESSFUL, lint clean.

| Module | Tests | Result | New coverage |
|---|---|---|---|
| core-domain | 20 | PASS | (unchanged) |
| core-protocol | 21 | PASS | Automatic pairing, failed pairing with backoff, one automatic re-pair then stop, unknown protocol sends nothing, multi-brand discovery merge/de-duplication, one failing brand does not hide others |
| driver-haismart | 59 (+4 opt-in hardware, skipped) | PASS | (unchanged behaviour; now behind `HaierUssAdapter`) |
| driver-gree | 27 | PASS | AES-ECB vector computed with OpenSSL; GCM round trip and tamper detection; wrong key; scan replies (ECB, GCM, OEM brand); envelopes; command and status mapping; full sessions against a simulated module: bind v1, fallback to v2, foreign unit refused, status read, confirmed command, acknowledged-but-not-applied → rejected, refusal code, no acknowledgement → verified by read, stale key → key invalid, unreachable |
| app | 14 | PASS | 2.0 device records migrate to Haier; protocol and brand persist |

**Hardware:** nothing new was tested on hardware in this round. No Gree unit and no physical phone
were available. Every Gree result above comes from a simulation and must be repeated on a real unit
(TEST_REPORT §3 matrix, per model, recorded in COMPATIBILITY_MATRIX.md).

---

# Addendum: 2026-11-13 (version 2.2.0, automatic Haier key retrieval)

`gradlew.bat test lintDebug assembleDebug`: BUILD SUCCESSFUL, no compiler warnings, lint "No issues found".
APK: `release/RoomAC-2.2.0-debug.apk`, SHA-256
`d4802b16f33841e49368be90a49a193baaec357c3970e49a403f71f38c439b1e`.

| Module | Tests | Result | New coverage |
|---|---|---|---|
| core-domain | 12 | PASS | (8 label-parser / typed-key tests removed with that code) |
| core-protocol | 25 | PASS | Account mode: no sign-in → "Sign-in needed" and no session opened; key fetched from the account and proven by a local read; refused saved sign-in → sign in again; cloud outage → backoff; stored key used with no cloud call; rejected key re-fetched once, then reported |
| driver-haismart | 85 (+5 opt-in, skipped) | PASS | Request signature, password wrapping and gateway credentials checked against values computed with sha256sum/md5sum/openssl; RSA wrap round trip; key request/reply codec; data-centre routing; sign-in request and headers; failure classification (refused, 401, 5xx, captive portal, no route, malformed); no reply bodies in errors; device-list parsing; account flow against a simulated service and gateway (session keeps no password, survives restart, MAC spelling normalised, foreign AC gets no key request, expired token refreshed once, refused refresh signs out, offline ≠ bad account, gateway refusal/silence, no identity → no traffic, sign-out); MQTT framing over streams (length encoding, CONNACK refusal, SUBACK wait with an early publish buffered, closed stream) |
| driver-gree | 27 | PASS | (unchanged) |
| app | 17 | PASS | Room and theme persist; account sessions encrypted, removed on sign-out, unreadable = signed out, Keystore failure reported; account errors carry a next step and no IP; token fields in JSON redacted from logs |

**Emulator (Android 14, NATed, so no AC reachable):** APK installs and launches with no crash;
first launch opens the automatic search; Settings shows the Haier account as "Not signed in" with
**Sign in** (app identity present in this build); the sign-in screen renders. No sign-in was
attempted.

**Not tested, needs the owner:**
- The Haier account flow against Haier's real service (no account available here). Run the account
  smoke test in BUILD_AND_INSTALL.md, then pair the AC in the app on a real phone.
- Every row of §3 above on a real phone, and any Gree unit.

---

# Addendum: 2026-11-13 (version 2.3.0, persistent Haier authentication and automatic key renewal)

`gradlew.bat test lintDebug assembleDebug`: BUILD SUCCESSFUL, lint "No issues found".
APK: `release/RoomAC-2.3.0-debug.apk`, SHA-256
`209a63902898273c0e169b9ae79ef26a3ef53950919e6d418bc846c0ee62dfac`.

**All results below are simulated** (fake Haier service, fake key gateway, fake AC). Nothing in
this round ran against Haier's real service or a physical AC.

| Module | Tests | Result | New coverage |
|---|---|---|---|
| core-domain | 12 | PASS | (unchanged) |
| core-protocol | 32 | PASS | Key change (version or decrypt) renewed silently, new key proven on the AC before it replaces the old one, control resumes; timeouts / unreachable / busy / refused never trigger renewal; no internet during renewal keeps the old key and backs off without a prompt; account refusal waits for the user, then reconnects after sign-in; key change seen by a command renews too; failed renewal stops (no cloud loop) until the user's Reconnect; renewing one AC leaves another AC's key untouched |
| driver-haismart | 92 (+5 opt-in, skipped) | PASS | Unlisted AC still requested from the key service (ID spelling / sharing); unowned AC reported, never keyed; without "Stay signed in" no password saved; restart mints from refresh token; expired access token renewed; rotated refresh token saved; expired session + saved password signs in again silently; expired session without it asks once with the login pre-filled and re-linking does not duplicate; changed password is dropped and never retried; no internet keeps everything; listed AC whose key is refused is not reported as a sign-in problem; two accounts each serve their own AC, link remembered across restart; per-account sign-out; 2.2.0 session migration |
| driver-gree | 27 | PASS | (unchanged) |
| app | 17 | PASS | (unchanged) |

**Emulator:** installs and launches with no crash; Settings shows the account section; the sign-in
screen shows the "Stay signed in" switch. No sign-in attempted.

**Spec §10 scenarios not testable here** (need the owner's HaiSmart account and AC): real sign-in,
real refresh-token expiry and password change, real key rotation on the AC, phone restart, AC in
another person's account, actual control after renewal. Start with the account smoke test
(BUILD_AND_INSTALL.md), then pair on a phone.
