# Room AC status

Last updated 2026-11-19 (version 2.5.0). The full requirement tracker is `PROGRESS.md`. This page covers
AC control, the features in flight, every feature flag, and the steps that need the owner's hardware.

## Quality gate (2026-11-19, end of the device-control pass)

| Check | Result |
|---|---|
| `gradlew assembleDebug` | Pass. APK: `app/build/outputs/apk/debug/app-debug.apk` |
| `gradlew cleanTest test` (all 9 test tasks, fresh run) | **354 run, 0 failed, 0 errors, 8 skipped** |
| `gradlew :app:lintDebug` | Pass (warnings only; see "Security notes") |

| Module | Run | Failed | Skipped |
|---|---|---|---|
| app (debug) | 17 | 0 | 0 |
| app (release) | 18 | 0 | 0 |
| core-domain | 12 | 0 | 0 |
| core-protocol | 37 | 0 | 0 |
| core-netscan | 60 | 0 | 0 |
| core-devicecontrol | 31 | 0 | 0 |
| driver-gree | 29 | 0 | 1 |
| driver-haismart | 118 | 0 | 6 |
| driver-midea | 32 | 0 | 1 |

The 8 skips are the opt-in live/hardware tests: they run only with environment variables pointing at a
real AC or account. The app module's tests run once per build type, so 17 of the 354 are counted twice.

## 1. Haier AC control: `DeviceNotInAccount` for the owner account — diagnosis so far

**Symptom:** fetching the AC's key through account <your HaiSmart email> ends in
`AcError.DeviceNotInAccount`.

**What that error means in the code** (`HaierAccount.fetchCredential`): every linked account
returned `NotOwned`. That needs **both**:
- the AC's normalised ID was missing from that account's `GET /uplussea/devices/v2/user/devices` list; and
- the key gateway refused the key twice, the second time with a fresh token.

Other cases give other errors: listed-but-refused gives "did not issue a key", a refused session gives
`AccountRejected`, and no network gives `CloudUnavailable`.

**Checked (2026-11-19):**
- **Signup changes do not touch the control path.** `signupHeaders` (`identify`, `accountToken`,
  `langCode`) are passed only to `checkAvailability`/`sendVerificationCode`/`verifyCode`/`register`.
  `login`, `refresh` and `devices` call `call(...)` without them, and `HaierKeyGateway` (MQTT) has no
  signup code. The login/devices/gateway hosts are unchanged and match the reference implementation.
- **The AC itself is fine.** A live read-only test from this PC (`HardwareSmokeTest`, no command sent):
  - subnet scan found `540853BD1D41@192.168.18.68`;
  - direct discovery reports `cloudState=1000` (the same "cloud connected" value as on 2026-11-11);
  - the uSS handshake was accepted, with key version 73 (50 on 11-11, 58 on 11-14: routine rotation).
- **No evidence about the owner account's device list on disk.** The 2026-11-18 HaiSmart capture's empty device
  list belonged to a different test account (a second test account), not the owner account. The repo's own records
  never show a successful live key fetch for any account, so "it used to work" cannot be confirmed or
  refuted from here.
- **Added so one attempt decides it** (logging only, no behaviour change). Every key fetch now logs:
  - the account's zone and its login/devices/gateway hosts;
  - the normalised device IDs in its list, and whether the wanted AC is `LISTED` or `NOT LISTED`;
  - the gateway's `errNo` on refusal.

  No token or key is logged. Two regression tests pin this, plus one for ID spelling
  (`54:08:53:bd:1d:41` matches `540853BD1D41`).

**Decide with the output of the PC test below:**

| Log shows | Meaning | Action |
|---|---|---|
| `wanted 540853BD1D41 -> NOT LISTED` (list empty or other IDs) | **(a)** The AC is not bound to the owner account any more. Binding changed outside RoomAC; not a code bug. | Re-add the AC to the owner account in HaiSmart (see owner step 1). No code change. |
| `-> LISTED` then `key gateway refused ... errNo=N` twice | **(b)** Listed but the key is refused. | Send the two `errNo` lines; fix token/zone/terminal handling with a regression test. |
| `sign-in: …` fails (wrong country, `30032`, refused) | **(c)** Sign-in or region problem. | Sign in with the country the account was created in. If that fails, send the line. |

The raw device-list body is not logged by design (it holds personal data). The normalised ID list in the
log line answers whether the AC is in it.

## 2. In-app HaiSmart sign-up — live-verified

- Creates a HaiSmart account inside Room AC. A real account was created against the live service on
  2026-11-18, and that run found the required `langCode` header (the fix is in, with a test).
- Errors name the failed step and quote Haier's reason and code. A malformed or taken email is refused
  before anything is sent. All sign-up tests pass in this gate.
- **Flag:** `HAIER_IN_APP_SIGNUP`, default off. Details: `HAIER_SIGNUP_BIND_ENDPOINTS.md`.

## 3. Network devices scanner — unit-tested; NOT YET TESTED ON A REAL NETWORK

- Lists devices on the phone's Wi-Fi with type, IP, vendor and the reason for each type. Sources:
  - mDNS (NsdManager);
  - SSDP/UPnP;
  - **ONVIF WS-Discovery (new: finds cameras)**;
  - a /24 ping-and-port sweep with ARP where Android allows it.

  It holds a multicast lock only while scanning.
- 60 unit tests on fake network responses. They cover every classifier rule, each discovery source,
  merging and failure handling.
- MAC manufacturers: 4,733 prefixes for 26 brands, generated from IEEE registry data in Wireshark's
  `manuf` file (SHA-256 `5735bcae…6237`; `standards-oui.ieee.org` blocks this environment).
- Now also kept per device, for device control: mDNS service ports, the UPnP description URL and ONVIF
  service URLs.
- **Flag:** `NETWORK_SCANNER`, default off.

## 4. Device control: TVs, cameras, scanners — NEW, unit-tested only, NOT YET TESTED ON ANY REAL DEVICE

Tap a device in the scanner list to open its controls. A control appears **only after the device has
answered the protocol that provides it**. Otherwise the screen says "View only — no local control" and
gives the reason. Every adapter reports `hardwareVerified = false`, and the screen says so.

| Device | Protocol (public spec) | Confirmed by | Controls offered | Tests |
|---|---|---|---|---|
| Roku / Roku TV | Roku ECP, HTTP :8060 | `GET /query/device-info` | Play/pause, Home, Back, arrows, OK, volume ±, mute; power off only on Roku TVs | 4 |
| Chromecast / Google TV / Cast speakers | Google Cast (CASTV2), TLS :8009 | `RECEIVER_STATUS` reply | Volume ±/set and mute (not when the device reports fixed volume); stop the app; play/pause only when the app has a media session | 5 |
| DLNA/UPnP TVs and renderers | UPnP AVTransport + RenderingControl (SOAP) | `GetTransportInfo` / `GetVolume` | Play, pause, stop, volume ±/set, mute. No power: UPnP AV has none | 4 |
| Samsung TV | Tizen WebSocket remote | — | **TODO adapter**: shows "not implemented yet" | 1 (shared) |
| LG TV | webOS SSAP | — | **TODO adapter**: shows "not implemented yet" | 1 (shared) |
| ONVIF cameras | ONVIF Profile S (SOAP 1.2, WS-Security digest) | unauthenticated `GetSystemDateAndTime` | Asks for the camera's username/password (kept in memory only); RTSP live view via Android's built-in player; PTZ nudges only if the camera reports PTZ and the profile has a PTZ configuration | 7 |
| Cloud-only cameras | none | — | "View only: probably cloud-only, use its vendor app" | 1 |
| Network scanners / MFPs | eSCL (AirScan/Mopria), `_uscan._tcp` / `_uscans._tcp` | `GET ScannerCapabilities` | Scan the flatbed (whole platen, the scanner's own resolution/colour/format), wait while busy, show the image in the app | 4 |

- Code: `core-devicecontrol/` (pure JVM; each adapter separate) and `app/.../netscan/DeviceControlViewModel.kt`,
  `app/.../ui/netscan/DeviceControlScreen.kt`.
- Known limits:
  - Android's built-in player handles common H.264 RTSP; other codecs will not play.
  - Cast play/pause works only for apps that use the standard media channel (many big apps use their own).
  - Roku may need "Control by mobile apps" enabled on the Roku.
- **Flag:** `DEVICE_CONTROL`, default off; reached from the scanner, so it also needs `NETWORK_SCANNER`.

**Security notes:**
- Device-control HTTP and TLS go only to private LAN addresses (10/8, 172.16/12, 192.168/16, enforced
  and tested), never to names or public IPs.
- Cast devices and eSCL-over-https scanners use self-signed certificates, so their TLS is encrypted but
  **not authenticated**. Lint flags this (`TrustAllX509TrustManager`, warning).
- Camera passwords are never saved or logged. The RTSP address that contains them goes only to the player.

## 5. AC binding — stub, not invented

- `AccountFeatures.bindDevice = false`; `HaierCloudClient.bindDevice()` throws without sending anything
  (the test enforces it).
- The capture rig was re-checked this pass: scripts parse, the frida CLI and server are both 17.26.4,
  and the agent is proven on HaiSmart 5.8.0.
- Steps and the four exact code edits: `HAIER_SIGNUP_BIND_ENDPOINTS.md` → "AC binding".

## Feature flags (all default off; set in the untracked `local.properties`, then rebuild)

| Line | BuildConfig | Effect |
|---|---|---|
| `roomac.haier.inAppSignup=true` | `HAIER_IN_APP_SIGNUP` | "Create a HaiSmart account" on the Haier sign-in screen |
| `roomac.networkScanner=true` | `NETWORK_SCANNER` | Settings → Network devices |
| `roomac.deviceControl=true` | `DEVICE_CONTROL` | Tap a scanned device to control it (needs `networkScanner` too) |
| `roomac.release.vendorAppIdentity=true` | `VENDOR_IDENTITY_OPT_IN` | Release builds only: keep vendor app identities (see `HAISMART_AUTH_INVESTIGATION.md`) |

**The current APK:** `local.properties` has only `roomac.haier.inAppSignup=true`, so this APK has sign-up
on, and the scanner and device control off.

## Owner steps (need your hardware or credentials)

All commands are PowerShell, run from `C:\Users\Haider\Desktop\AC-Control`.

**1. Decide the AC problem (PC on the home Wi-Fi; about 1 minute).** Run in your own window and type the
password at the prompt (it never appears on screen):
```
$env:HAISMART_USER="<your HaiSmart email>"; $s=Read-Host "HaiSmart password" -AsSecureString; $env:HAISMART_PASSWORD=[Runtime.InteropServices.Marshal]::PtrToStringBSTR([Runtime.InteropServices.Marshal]::SecureStringToBSTR($s)); $env:HAISMART_REGION="92"
$env:AC_DEVICE_ID="540853BD1D41"; $env:AC_HOST="192.168.18.68"
.\gradlew.bat :driver-haismart:test --tests '*HaierAccountSmokeTest*' --rerun-tasks -i | Select-String "haier account|sign-in:|account devices:|key fetch:|local read"
```
`HAISMART_REGION` must be the dialling code of the country the account was created in (92 = Pakistan).
Read the result with the table in section 1.
- **If it is (a):** open HaiSmart on your phone, signed in as the owner account. Then add device (+) → air
  conditioner, and follow the app until the AC shows. Then in Room AC open the AC and let it reconnect,
  or re-run the command above: it should end with `key fetch: ok` and `local read … ok`.

Same check from the phone instead (USB debugging on):
```
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" logcat -c
# sign in as the owner account in Room AC and open the AC, then:
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" logcat -d -s AC:I | Select-String "haier account"
```

**2. Install a build with everything on:**
```
Add-Content local.properties "`nroomac.networkScanner=true`nroomac.deviceControl=true"
.\gradlew.bat assembleDebug
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk
```

**3. Scanner on the real network.** Room AC → Settings → Network devices → "See what else is on this
Wi-Fi" → "Scan this Wi-Fi". Expected results:
- the router shows as **Router** ("default gateway");
- Chromecasts or Google TVs show as **TV**, network printers as **Printer**, and ONVIF cameras as **Camera**;
- the AC appears at 192.168.18.68, probably as "Unknown device";
- pull down to rescan; with Wi-Fi off it says "Join a Wi-Fi network first".

**4. Device control (each untested).** Tap a device in the list:
- **Roku:** expect the remote buttons. Press Home: the Roku should go to its home screen.
- **Chromecast:** play something on it first, then press Volume + and Pause.
- **DLNA TV:** Play/Pause/Volume. Many TVs accept these only while playing DLNA media.
- **ONVIF camera:** enter its username and password, then "Show live view". Arrows appear only on PTZ cameras.
- **Scanner/MFP:** put a page on the glass, then "Scan the page on the glass". The image should appear.
- **Samsung/LG TVs:** "not implemented yet". **Cloud cameras:** "use its vendor app". Both are expected.

Report anything that does nothing, along with the `DeviceControl` lines:
`adb logcat -d -s DeviceControl:I NetScan:I`.

**5. Sign-up re-check (optional):** Settings → Haier → Sign in → "Create a HaiSmart account". Use a fresh
email and enter the 6-digit code Haier emails you.

**6. Bind capture (rooted arm64 phone + the AC):** `HAIER_SIGNUP_BIND_ENDPOINTS.md` → "AC binding" →
"Step-by-step capture".

## Backups

A dated zip outside the repo holds the sources, docs, `.git`, `local.properties` (your vendor app keys:
keep it private), today's APK and `C:\Users\Haider\htk-capture`:
`C:\Users\Haider\AC-Control-backup-20261119.zip`.
