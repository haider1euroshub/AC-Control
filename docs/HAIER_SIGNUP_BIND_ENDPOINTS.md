# HaiSmart account creation (sign-up) endpoints

| Feature | Status | Flag |
|---|---|---|
| In-app sign-up | **Live-verified** (2026-11-18, real account created against the live SE-Asia service) | `HAIER_IN_APP_SIGNUP` ← `roomac.haier.inAppSignup=true`, default **off** |
| AC binding | **Stub, not captured.** `AccountFeatures.bindDevice = false`; `HaierCloudClient.bindDevice()` throws | none (nothing to enable) |

Sign-up error handling (2026-11-19): every failure names the failed step and quotes Haier's own
`retInfo` + `retCode` (e.g. *"Haier did not send a verification code: langCode cannot be empty, code
10001."*); `30035` "send too often" gets its own message (use the newest code, wait before asking
again); a taken address is refused before any code is sent; a malformed email is refused locally
without contacting Haier; if the account is created but the follow-up sign-in fails, the user is told
the account exists and to sign in from Settings. Covered by `HaierCloudTest`/`HaierAccountTest`.

Protocol facts captured 2026-11-18 by intercepting the HaiSmart app (`com.haier.uhome.uplus.seasia`,
v5.8.0) against the live SE-Asia service, on a rooted API 34 emulator with Frida SSL-unpinning + a
system-trusted MITM CA. Bodies below are the real decrypted traffic (a Singapore / zone 65 account).

This fills the gap noted in `HaierCloudClient.kt` / `HaierAccount.kt`: account creation previously had
"no published or verified interface" (`AccountFeatures(register = false, …)`). It now does.

**Implementation status (2026-11-18):** sign-up is implemented end-to-end against these endpoints —
`HaierCloudClient.checkAvailability/sendVerificationCode/verifyCode/register`, wired through
`HaierAccount.sendVerificationCode()/register()` and an in-app UI (`ui/account/SignUp.kt`, no HaiSmart
redirect). It is behind the build flag **`HAIER_IN_APP_SIGNUP`** (`local.properties` →
`roomac.haier.inAppSignup=true`; forced off in release builds that omit the vendor identity). When on,
`AccountFeatures.register = true` and the "Create a HaiSmart account" button appears on the sign-in
screen; when off, the existing "create it in the HaiSmart app" help is shown instead.

**Verified live end-to-end on 2026-11-18**: a brand-new account was created through RoomAC's own signup
screen (no HaiSmart app) against the live service with RoomAC's own app identity — availability check →
send code → verify code → register → auto sign-in, all succeeding, with the password/sesame crypto
reused from login unchanged. That run is what surfaced the required `langCode` header (the
email-verifications endpoint answers `retCode 10001 "langCode cannot be empty"` without it). The flag
still defaults off (opt-in per build) but the flow itself is now field-proven.

AC binding (adding an AC to the account) is **not yet captured** — see "AC binding" at the end.

## Summary

Sign-up is five calls on the same `uplussea` service that login already uses, in order:

1. `register/check`   — is this email/phone free?
2. `email-verifications` — send the OTP to the email
3. `mail/code/verify` — submit the OTP → returns a **one-time `code` token**
4. `mail/register`    — create the account (password + sesame + the `code` token)
5. `ums/register`     — register the install for push (optional for a headless client)

…followed by the ordinary `accounts/v2/login`. The password/sesame wrapping is **byte-for-byte the
same** as login (see "Crypto" below), so `HaierPasswordCipher.encrypt()` is reused unchanged.

## Hosts & zones

Same split as the rest of the client (`HaierDomains.forZone`):

| Zone | uhome host |
|------|-----------|
| India (91) | `uhome-sea.haieriot.net` |
| all others (e.g. Singapore 65) | `uhome-sgp.haieriot.net` |

Push registration (step 5) uses the UMS host: `uws-sgp.haieriot.net` (`uws-sea` for India).

## Request envelope (all steps)

Every call carries the same signed device-center header envelope as `HaierCloudClient.headers()`,
with `accessToken`/`accountToken` **empty** (these are pre-auth). Captured values:

```
appId:        <haier.appId from local.properties>          # live HaiSmart 5.8.0 appId (appKey is NOT sent; only used in sign)
appVersion:   5.8.0
clientId:     <32-hex per-install id>     # 32-hex per-install terminal id (newClientId format)
timestamp:    1794987072864               # ms; feeds the sign
sign:         SHA-256( path + body-without-whitespace + appId + appKey + timestamp )  # HaierSigning.sign
zoneInfo:     65                          # dialling code of the account's country
langCode/langcode: en                     # REQUIRED on the sign-up calls — email-verifications answers
                                          # retCode 10001 "langCode cannot be empty" without it (verified
                                          # live 2026-11-18 by driving RoomAC's own signup). Both the camel
                                          # and lower-case spellings are sent, as the app does.
identify:     1                           # present on these account calls (not in the current client envelope)
accessToken:                              # empty pre-auth
accountToken:                             # empty pre-auth (header the current client doesn't send yet)
Content-Type: application/json;charset=UTF-8
User-Agent:   okhttp/4.12.0
```

Note vs the current `headers()`: add `identify: 1` and an (empty) `accountToken`, and bump
`appVersion` to the build you emulate. `langCode` appears both camel and lower case.

## The calls

### 1. Check availability
```
POST https://uhome-sgp.haieriot.net/uplussea/accounts/v1/user/register/check
req : {"phone":"<email-or-phone>","countryCode":""}      # field is named "phone" even for an email
resp: {"data":{"available":true},"retCode":"00000","retInfo":"Operation succeeded"}
```
`available:false` means the account already exists (sign in instead).

### 2. Send the verification code
```
POST https://uhome-sgp.haieriot.net/uplussea/accounts/v2/email-verifications
req : {"email":"<email>","scenario":"registration"}
resp: {"data":{"delay":"60"},"retCode":"00000","retInfo":"Operation succeeded"}
```
`delay` is the resend cool-down (seconds). The OTP (6 digits) arrives by email.

### 3. Verify the code → get the one-time `code` token  ★ token chain starts here
```
POST https://uhome-sgp.haieriot.net/uplussea/accounts/v1/mail/code/verify
req : {"email":"<email>","verificationCode":"<6-digit OTP>","countryCode":"65","scenario":"registration"}
resp: {"data":{"code":"nNXdKDDz"},"retCode":"00000","retInfo":"Operation succeeded"}
```
`data.code` (e.g. `nNXdKDDz`) is a short-lived, single-use token that proves the email was verified.
It is **not** the OTP; it is consumed by step 4.

### 4. Create the account  ★ token chain ends here
```
POST https://uhome-sgp.haieriot.net/uplussea/accounts/v2/mail/register
req : {"email":"<email>",
       "password":"<Base64 AES-128-CBC(pw)>",
       "code":"nNXdKDDz",                 # the token from step 3
       "sesame":"<Base64 RSA-1024(key)>"}
resp: {"data":{"success":true},"retCode":"00000","retInfo":"Operation succeeded"}
```

### 5. Register the install for push (optional)
```
POST https://uws-sgp.haieriot.net/ums/v3/account/register
req : {"ability":["openPage","notification","dialog","toast","openApp","deliverData","action",
                  "openLink","makeVideoCall","callApi"],
       "channel":2,"clientType":2,"mfrsMsgOpen":"0","msgVersion":"33",
       "pushId":"<FCM token>"}
resp: {"retCode":"00000","retInfo":"success"}
```
This is the UMS (message) backend, not account creation. A headless controller that never receives
push can skip it; account creation in step 4 already succeeded.

### 6. Sign in (unchanged, existing verified path)
```
POST https://uhome-sgp.haieriot.net/uplussea/accounts/v2/login
req : {"username":"<email>","password":"<Base64 AES-128-CBC(pw)>","sesame":"<Base64 RSA-1024(key)>"}
```

## The `code` token chain

```
email-verifications (send)        verificationCode (6-digit OTP, from the user's inbox)
        │                                   │
        └─────────── user enters OTP ───────┘
                            │
                     mail/code/verify  ──►  data.code = "nNXdKDDz"   (one-time, short-lived)
                            │
                     mail/register.code = "nNXdKDDz"  ──►  success:true
```

## Crypto — reuses the existing login wrapping as-is (verified)

The `password` and `sesame` fields in `mail/register` are produced by the **same** scheme as login,
confirmed by structure against the auto-login captured in the same session:

| Field | Register | Login | Scheme (`HaierPasswordCipher`) |
|-------|----------|-------|--------------------------------|
| `password` | 24 b64 chars → **16 bytes** (1 AES block) | same | Base64( AES-128-CBC/PKCS5( pw ), key=IV=random 16-digit ASCII ) |
| `sesame`   | 172 b64 chars → **128 bytes** (RSA-1024) | same | Base64( RSA-1024/ECB/PKCS1( that 16-digit key ), service public key ) |

So registering needs **no new crypto and no key extraction**. Build the body as:

```kotlin
val (pw, sesame) = HaierPasswordCipher.encrypt(password, HaierPasswordCipher.randomKey(random))
val body = buildJsonObject {
    put("email", email); put("password", pw); put("code", verifyToken); put("sesame", sesame)
}.toString()
// POST domains.uhome + "/uplussea/accounts/v2/mail/register", signed envelope, accessToken=""
```

The service RSA public key is already embedded (`HaierPasswordCipher.PUBLIC_KEY_B64`); the cipher hook
observed **zero** `javax.crypto.Cipher` events during capture, consistent with the password being
wrapped by this app-level routine before the HTTP layer — i.e. nothing additional to reverse.

## Paths (constants)

```
/uplussea/accounts/v1/user/register/check
/uplussea/accounts/v2/email-verifications
/uplussea/accounts/v1/mail/code/verify
/uplussea/accounts/v2/mail/register
/ums/v3/account/register            (host: uws-*)
/uplussea/accounts/v2/login         (existing: HaierCloudClient.LOGIN_PATH)
```

## AC binding (adding a device to the account) — TODO, not captured

**Status:** `AccountFeatures.bindDevice = false`. `HaierCloudClient.bindDevice(accessToken, deviceId)`
exists only as a marked stub that throws — **no endpoint is invented**. It stays this way until the
cloud bind call is captured.

**Why it could not be captured yet.** Binding is the last step of pairing, and pairing begins with a
*local* step the app does over the air to the AC itself (join the AC's SoftAP hotspot / UDP broadcast
to hand it Wi-Fi credentials). An emulator's Wi-Fi is a NAT'd virtual adapter: it is not on the home
LAN's broadcast domain and cannot join the AC's hotspot, so discovery never completes and the cloud
bind call that would follow never fires. The 2026-11-18 emulator pairing capture
(`C:\Users\Haider\haismart-acpair.log`) confirms this: it stops at product search
(`/uplussea/devices/v3/prods/global/search`), and `extract-bind-candidates.ps1` finds 0 unknown calls in it.

**What is already wired (local side).** `UdiscoveryScanner` (`Udiscovery.kt`) finds an AC on the LAN
without any key or cloud, and reports its `cloudState` TLV (whether it is already bound). `UssCodec` /
`BizCrypto` / `UssConnection` already speak the local uSS protocol to a bound AC. So the pieces that
observe and talk to the AC locally exist; the **only** missing piece is the one cloud HTTP call that
associates a discovered `deviceId` with the signed-in account.

### The capture rig (verified 2026-11-19, no phone attached)

All on this PC, outside the repo:

| Item | Path | Checked |
|---|---|---|
| Phone capture script | `C:\Users\Haider\htk-capture\htk-log-phone.ps1` | Parses; no-phone path exits cleanly. Two bugs fixed 2026-11-19 (below) |
| Frida logger agent | `C:\Users\Haider\htk-capture\htk_logger_agent.js` | Hooked OkHttp + Cipher in HaiSmart 5.8.0 on 2026-11-18 (signup capture) |
| SSL unpinning script | `C:\Users\Haider\Downloads\multiple-unpinning.js` | Present |
| frida CLI | `C:\Users\Haider\AppData\Local\Python\pythoncore-3.14-64\Scripts\frida.exe` | 17.26.4 |
| frida-server (phone) | `C:\Users\Haider\Downloads\frida-server-17.26.4-android-arm64` | Present; same version as the CLI (required) |
| adb | `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe` | Present |
| Bind-call extractor | `tools\haier-capture\extract-bind-candidates.ps1` (this repo) | Tested on the 2026-11-18 logs |

Fixed in `htk-log-phone.ps1` on 2026-11-19 (originals kept as `*.bak-20261119`):
1. **It could not run at all under Windows PowerShell 5.1.** The em dashes in its messages, in a UTF-8 file
   without a BOM, are read as Windows-1252 and include a curly-quote character that ends the
   string, so the script failed to parse. All non-ASCII characters were replaced (also in `htk-log.ps1`
   and `htk-capture.ps1`).
2. With no phone (or one line from `adb devices`), `-match` returned a boolean and the script tried the
   device "False". It now stops with "No real phone found". It also stops if the phone's ABI is not
   arm64 instead of continuing.
3. Added: it prints every HaiSmart process, and `-AppPid <pid>` attaches to a specific one.

### Step-by-step capture (needs a rooted arm64 phone + the real AC)

1. **Phone prep.** Root with Magisk. Install HaiSmart (`com.haier.uhome.uplus.seasia`) and sign in to the
   account the AC should join. Enable Developer options → USB debugging, plug in, and accept the RSA
   prompt. Check with `& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices`: the phone shows as `device`.
2. **Reset the AC** so it is unbound and in pairing mode (remove it from HaiSmart if it is listed, then
   use the AC's Wi-Fi reset, usually holding the remote's Wi-Fi/"Health" key per the manual until it beeps).
   The phone must be on the 2.4 GHz home Wi-Fi.
3. **Start the logger** in PowerShell on this PC:
   ```
   powershell -ExecutionPolicy Bypass -File C:\Users\Haider\htk-capture\htk-log-phone.ps1 C:\Users\Haider\haismart-bind.log
   ```
   Approve the Magisk "su" prompt on the phone when it appears. Wait for
   `[htk] PAIR THE AC NOW` and for the `okhttp RealInterceptorChain hooked` line.
4. **Pair in HaiSmart:** Add device → air conditioner → follow the app until the AC shows as added.
   Then press Ctrl+C in PowerShell.
5. **Extract the bind call:**
   ```
   powershell -ExecutionPolicy Bypass -File C:\Users\Haider\Desktop\AC-Control\tools\haier-capture\extract-bind-candidates.ps1 C:\Users\Haider\haismart-bind.log -Json C:\Users\Haider\haismart-bind-candidates.json
   ```
   Calls marked `>>>` are new device/bind/gateway calls, in capture order. The bind call is expected to
   be a POST under `/uplussea/devices/**` (or a `gw-*` host) carrying the AC's deviceId and a
   provisioning token. Note whether the body is encrypted (cipher events appear in the raw log around it),
   and whether a follow-up reply returns the AC's local key (compare with `HaierKeyGateway`).
6. **Implement:** fill `HaierCloudClient.bindDevice()`
   (`driver-haismart/src/main/kotlin/com/roomac/controller/driver/haismart/cloud/HaierCloudClient.kt`,
   the `TODO(bind)` KDoc lists the 4 edits): add `BIND_PATH` to the companion object, build the body
   exactly as captured, send it via `call(...)`, add a `HaierCloudTest` case from the captured
   request/response, then set `AccountFeatures(bindDevice = true)` in `HaierAccount`.

**If nothing new shows up:** the bind call may run in another HaiSmart process. Re-run step 3 with
`-AppPid <pid>` using a pid from the printed process list (pair again after resetting the AC).
Alternatively, the bind may not use OkHttp; then the cipher events and the raw log are the next place
to look.

**No rooted phone?** Repackage the HaiSmart APK with frida-gadget (`objection patchapk`, or `apktool` +
`libfrida-gadget.so` + gadget config), sign it, install it, and load the same agent. This is more fragile
(you have to repack and re-sign, and redo it on every app update) but needs no root.
