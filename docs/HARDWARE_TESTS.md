# Hardware tests

Reproducible procedures for physical testing (requirement.md §8.2) and the results recorded so far.
A skipped opt-in test is **not** a pass. A handshake or packet exchange is **not** a working control.

Evidence labels: Verified on hardware · Automated test only · Implemented, unverified · Research
only · Unsupported · Unknown.

## Test equipment on record

| Item | Value |
|---|---|
| Haier AC | Haier T3 Plus Inverter `HSU-20HFTEX/013WUSDC(OW)-T3`, device ID `540853BD1D41`, uPlusId `2008…0040`, module firmware `e_4.3.00 / R_6.0.01`, uSS SDK 2.18 |
| Network | Home Wi-Fi 192.168.18.0/24. The AC was at 192.168.18.68 on 2026-11-14 (DHCP; may change). |
| Test PC | Windows 11, same Wi-Fi (192.168.18.139) |
| Android phone | None recorded yet |
| Gree unit | None available |

The Haier local key was exposed in an earlier conversation. Treat it as compromised: it is not in
the repository, its history or any test fixture (scan of 2026-11-14), and it must never be pasted
into chat, logs or files. A new key is issued when Haier re-issues it (for example when the AC is set
up again in HaiSmart; not verified). RoomAC detects a changed key version and renews by itself.

## Procedure 1: Haier, key-free (from a PC)

```powershell
$env:AC_HOST="<AC address>"; $env:AC_DEVICE_ID="540853BD1D41"; $env:AC_SCAN_FROM="<PC address>/24"
.\gradlew.bat :driver-haismart:test --tests "*HardwareSmokeTest*" --rerun-tasks -i
```

Checks subnet discovery, direct discovery and the uSS handshake. It sends no command.

## Procedure 2: Haier account (from a PC)

Run in your **own** PowerShell window. Never paste the password, tokens or keys into chat.

```powershell
$env:HAISMART_USER="<your HaiSmart email or phone>"; $s=Read-Host "HaiSmart password" -AsSecureString; $env:HAISMART_PASSWORD=[Runtime.InteropServices.Marshal]::PtrToStringBSTR([Runtime.InteropServices.Marshal]::SecureStringToBSTR($s)); $env:HAISMART_REGION="92"
$env:HAIER_APP_ID="<haier.appId from local.properties>"; $env:HAIER_APP_KEY="<haier.appKey from local.properties>"
$env:AC_DEVICE_ID="540853BD1D41"; $env:AC_HOST="<AC address>"
.\gradlew.bat :driver-haismart:test --tests '*HaierAccountSmokeTest*' --rerun-tasks -i
```

It signs in, lists the account's devices, fetches the AC's key and proves it with a local status
read. It sends no command and prints neither the key nor any token. The lines to report are
`sign-in:`, `account devices:`, `key fetch:` and `local read`.

## Procedure 3: Haier controls (Android phone)

1. Install `release/RoomAC-<version>-debug.apk` on a phone on the same Wi-Fi as the AC.
2. Add the AC: it should be found automatically. Sign in to HaiSmart when asked.
3. Record each step below with the phone's model and Android version, the app build, the date, and
   whether the AC physically responded (beep, display, louvres) and the app showed the change as
   confirmed (not just "Sending").
4. Reconnect checks:
   - force-stop and reopen the app;
   - switch the AC off at the wall and back on;
   - turn Wi-Fi off and on.
5. Offline check: unplug the router's internet (keep Wi-Fi on) and repeat power and temperature.

## Procedure 4: Gree (from a PC)

```powershell
$env:GREE_SCAN_FROM="<PC address>/24"
.\gradlew.bat :driver-gree:test --tests '*GreeHardwareSmokeTest*' --rerun-tasks -i
```

Scans the network, then for each unit binds (which only returns its key) and reads its status. It
sends no command. Then repeat Procedure 3 with the Gree unit. Note whether the unit had to be set
up in GREE+ first, and whether it works with the phone's internet off.

## Results

### Haier HSU-20HFTEX/013WUSDC(OW)-T3 (540853BD1D41)

| Test | Result | Date | Evidence |
|---|---|---|---|
| Discovery (subnet scan, no address given) | **Pass**: found in 4.1 s | 2026-11-11, 2026-11-14 | Verified on hardware |
| Discovery (direct query to saved address) | **Pass** 5/5 after the retry fix. One failure before the fix, right after a sweep | 2026-11-14 | Verified on hardware |
| Identification | uPlusId matches the classic wall-split profile; firmware reported | 2026-11-11, 2026-11-14 | Verified on hardware |
| uSS handshake for its device ID | **Pass**; local key version 58 | 2026-11-11, 2026-11-14 | Verified on hardware (handshake only) |
| HaiSmart sign-in | Not run: needs Procedure 2 | — | Automated test only |
| Account device list | Not run | — | Automated test only |
| Local-key retrieval | Not run | — | Automated test only |
| Authenticated status read | Not run | — | Automated test only |
| Power | Not run | — | Automated test only |
| Temperature | Not run | — | Automated test only |
| Mode | Not run | — | Automated test only |
| Fan | Not run | — | Automated test only |
| Reconnect after restart | Not run | — | Automated test only |
| Offline LAN operation | Not run | — | Automated test only |

**Overall: Implemented, unverified.** Status reads and controls are unproven.

### Gree

| Test | Result | Date |
|---|---|---|
| Discovery | No Gree unit on the test network (4.1 s scan, 0 found) | 2026-11-14 |
| Everything else | Not run: no hardware | — |

**Overall: Implemented, unverified.**

## Procedure 5: Midea (from a PC)

```powershell
$env:MIDEA_SCAN_FROM="<PC address>/24"
.\gradlew.bat :driver-midea:test --tests '*MideaHardwareSmokeTest*' --rerun-tasks -i
```

Scans both Midea discovery ports and reads the state of every V2 unit (no credential needed). V3
units are only listed: reading them needs a NetHome Plus sign-in in the app (Procedure 3 with a
Midea AC). It sends no command.

### Midea

| Test | Result | Date |
|---|---|---|
| Discovery | No Midea unit on the test network (4.1 s scan, 0 found) | 2026-11-14 |
| Protocol against captured traffic | V2 packet, V3 packet (with its session key), V2/V3 discovery replies and three state replies from msmart-ng's published captures decode to the expected values | 2026-11-14 (automated) |
| Everything else | Not run: no hardware | — |

**Overall: Implemented, unverified.**

### Regression after the 2026-11-14 changes

Haier key-free checks re-run after the sign-in diagnosis, Midea integration and descriptor changes:
subnet scan found 540853BD1D41 at 192.168.18.68 (4.1 s), direct discovery answered, handshake
accepted (key version 58). **Pass.**
