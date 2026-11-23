# Build and install

## Requirements

| Tool | Version |
|---|---|
| JDK | 17 (tested with Eclipse Temurin 17) |
| Gradle | 8.9 (wrapper included) |
| Android Gradle Plugin | 8.5.2 |
| Kotlin | 2.0.20 |
| Android SDK | Platform 34, Build-Tools 34.0.0 |
| Device | Android 8.0 (API 26) or newer, with Wi-Fi |

Point Gradle at the SDK, and optionally give it the Haier app identity, with an untracked
`local.properties`. Without `haier.*` the app builds and works, but Haier sign-in shows as
unavailable, so Haier ACs cannot be connected:

```
sdk.dir=C\:/Users/<you>/AppData/Local/Android/Sdk

# Haier sign-in. The HaiSmart app identity Haier's service requires; the same for every install
# and published in haismart-local (cloud.py, SEA_APP_CREDENTIALS). Never commit these.
haier.appId=<app id>
haier.appKey=<app key>
```

### Feature flags (all default off)

Set in the same untracked `local.properties`, then rebuild. See `docs/STATUS.md` for what each does.

```
roomac.haier.inAppSignup=true             # In-app HaiSmart account creation (live-verified 2026-11-18)
roomac.networkScanner=true                # "Network devices" LAN scanner (Settings → Network devices)
roomac.deviceControl=true                 # Tap a scanned device to control it (TVs, cameras, scanners; needs networkScanner)
roomac.release.vendorAppIdentity=true     # Release builds only: keep the vendor app identities (see HAISMART_AUTH_INVESTIGATION.md)
```

In-app sign-up also needs `haier.appId`/`haier.appKey`; release builds force it off unless
`roomac.release.vendorAppIdentity=true`.

## Build

```bash
./gradlew test lintDebug assembleDebug          # Windows: gradlew.bat ...
```

- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`. A copy is placed in `release/` (APKs are
  git-ignored).
- Application ID of the debug build: `com.roomac.controller.debug`.

### Release APK

Release signing is read from an untracked `keystore.properties` in the project root:

```
storeFile=/path/to/your-release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Then `./gradlew assembleRelease` gives `app/build/outputs/apk/release/app-release.apk`. Without
that file the release build is unsigned and cannot be installed. No production signing key is
included, and none should be committed.

## Install

USB (enable Developer options → USB debugging on the phone):

```bash
adb install -r release/RoomAC-2.2.0-debug.apk
```

Or copy the APK to the phone and open it (allow "Install unknown apps" for the file manager or
browser when Android asks).

## Hardware smoke test (optional, from a computer on the same network)

Read-only; it never sends a control command.

```bash
AC_HOST=192.168.18.68 AC_DEVICE_ID=540853BD1D41 AC_SCAN_FROM=192.168.18.139/24 \
  ./gradlew :driver-haismart:test --tests '*HardwareSmokeTest*' -i
# add AC_LOCAL_KEY=<key> to also read status
```

PowerShell equivalents with no build needed: `tools/probe_discovery.ps1 -Target <ip>` and
`tools/probe_handshake.ps1 -Target <ip> -DeviceId <id>`.

## Haier account smoke test (optional, uses your own HaiSmart account)

Read-only: signs in, lists the account's ACs, fetches the key for one AC and proves it with a local
status read. It prints no key or token.

```powershell
$env:HAISMART_USER="you@example.com"; $env:HAISMART_PASSWORD=Read-Host -Prompt "HaiSmart password"
$env:HAISMART_REGION="92"; $env:HAIER_APP_ID="<as in local.properties>"; $env:HAIER_APP_KEY="<as in local.properties>"
$env:AC_DEVICE_ID="540853BD1D41"; $env:AC_HOST="192.168.18.68"
.\gradlew.bat :driver-haismart:test --tests '*HaierAccountSmokeTest*' -i
```

On Windows, use `gradlew.bat` from PowerShell; the POSIX `gradlew` script fails to start under Git
Bash on some machines ("Could not find or load main class org.gradle.wrapper.GradleWrapperMain").
