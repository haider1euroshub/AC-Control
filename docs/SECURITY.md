# Security implementation notes

| Requirement (REQUIREMENTS.md §6) | Implementation |
|---|---|
| Protect stored credentials | Each device's key, and each manufacturer account session (refresh token + the terminal id it is bound to), is encrypted with AES-256-GCM under a non-exportable Android Keystore key (`AndroidKeyStoreCipher`). If the Keystore cannot decrypt it, it is treated as missing / signed out; there is never a plain-text fallback. Keys are deleted with their device. |
| Account passwords | Never logged. Stored only if the user leaves "Stay signed in" on (the default, explained next to the switch): then inside the Keystore-encrypted account blob, used only to sign in to Haier again when the refresh token is refused, deleted at once if Haier refuses it, and deleted on sign-out. With the switch off it is used for one request and forgotten. On the wire the password is AES-128-CBC encrypted under a fresh random key, which is itself RSA-encrypted with Haier's published public key, inside HTTPS. The stored account label is masked (`so•••@example.com`). |
| No secrets in source or Git | No account credentials, tokens or per-device keys are in the code. The HaiSmart **app identity** (app ID / app key every HaiSmart install signs requests with; not user-specific, published in haismart-local) is read from the git-ignored `local.properties` into `BuildConfig`, so it is not in the repository. It is, however, inside any APK built with it. `keystore.properties` is git-ignored. |
| Public protocol constants in code | Gree `GENERIC_KEY_V1/V2`, Haier's RSA public key for sign-in and the `haier_sdk` salt for the key gateway's connect password are protocol constants shared by every install. They protect nothing user-specific, so they are in the code. |
| Transport security | Account calls use HTTPS with the platform's certificate checks. The key gateway (MQTT 3.1.1, port 58702) uses TLS with certificate **and host name** verification (`endpointIdentificationAlgorithm = HTTPS`). |
| Not in logs, analytics, crash reports, UI | No analytics or crash-reporting SDKs. `DiagnosticsLogger` redacts 32/64-hex strings and `password/token/refreshToken/accessToken/sesame/localKey` values, including JSON forms (tested). Cloud errors carry only the service's status code and message, never reply bodies (tested). Keys are never shown on screen and are never typed by the user. |
| No unnecessary transmission | Everyday control is local only. The cloud is contacted only to sign in and to fetch a Haier AC's key when it is first connected or its saved key is rejected. A stored key is used without any cloud call (tested). |
| Minimal permissions | INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE. No location, camera or nearby-devices permission. |
| Backups | `allowBackup=false`, and data-extraction rules exclude app data. |
| Only authorised devices | A Haier key is requested for the device ID found on the LAN; Haier's key service only issues keys the account may use, and every key is proven on that AC (session bound to its device ID) before it is saved, so no key can be applied to another AC (tested). Gree bind works only from the same LAN as the AC. Every session is bound to the device ID. |
| Manufacturer web pages | Official sign-up pages open only in a Custom Tab (system browser engine). RoomAC cannot read or inject into them, and Google/Apple sign-in works as those providers require. URLs must be https on a declared host with no user-info or non-default port (`TrustedUrl`, tested), checked both when declared and when opened. No provider declares one yet. |
| Exposed key (2026-11-14) | A Haier local key was exposed in an earlier conversation. It is treated as compromised. Scan result: it is not in the repository, Git history or test fixtures; fixtures use synthetic patterns only. Never paste keys, tokens or passwords into chat; hardware tests read credentials from the local environment and print neither keys nor tokens. |
| Sign-out | Settings → Manufacturer accounts → Sign out (per account) deletes its tokens, login and any saved password. Already-fetched AC keys stay so local control continues; removing an AC deletes its key. |

Known gaps: no traffic capture on a phone has confirmed the cloud-use claims (TEST_REPORT.md §4).
Using the HaiSmart app identity means this app talks to Haier's service the way the HaiSmart app
does; Haier has not published or authorised this API, and could change or block it (see
DISCOVERY_AND_AUTH.md).

## Vendor app identities (release blocker, 2026-11-14)

The Haier (HaiSmart) and Midea (NetHome Plus) sign-ins send those apps' own client identities, which come
from community projects. requirement.md §1.4(8) and §4.3 forbid impersonating a vendor app. Release
builds therefore compile with blank identities unless `roomac.release.vendorAppIdentity=true` is set in
the untracked local.properties. `ReleaseVendorIdentityTest` (testRelease) enforces this. Debug builds
still contain the identities for the owner's own diagnosis. Until an authorised decision-maker signs off
under §13, this stays open as a release blocker. See HAISMART_AUTH_INVESTIGATION.md, H9.
