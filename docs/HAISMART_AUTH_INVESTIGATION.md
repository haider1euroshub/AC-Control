# HaiSmart sign-in investigation (requirement.md §6)

**Known failure (reported by the owner):** the account smoke test printed
`haier account: sign-in failed (REJECTED)` / `sign-in: The HaiSmart account did not accept the sign-in.`
The same email and password work in the official HaiSmart app.

No password, app key, token or device key appears in this file, and none was used in any attempt
below. The probes use a made-up account on the reserved `example.invalid` domain.

## Attempt log

| ID | Hypothesis | Evidence inspected | Change | Test | Result (sanitised) | Interpretation | Next step |
|---|---|---|---|---|---|---|---|
| H1 | The failure reason was discarded, so "REJECTED" cannot be diagnosed | `HaierAccount.signIn` logged only `e.kind`; `HaierCloudClient.call` already puts HTTP status + retCode + retInfo (no bodies) in the message | Log `kind: message`; keep `retCode` on the exception | Unit test: the logged failure contains `30032` and never the password | Pass | **Confirmed.** The owner's run could not show *why* Haier refused. | Owner re-runs once with the new build of the test (H2/H3 then readable). |
| H2 | The app identity was typed incorrectly into environment variables | The smoke test read `HAIER_APP_ID`/`HAIER_APP_KEY` only from the environment; the instructions asked the owner to copy them by hand from `local.properties` | Smoke test now reads them from `local.properties` itself (env still overrides) and prints which source was used, never the values | — | — | **Unresolved; risk removed.** A manual copy error can no longer happen. | See H3 for how it would show. |
| H3 | A wrong app identity and a wrong account produce distinguishable replies | Live probe against `uhome-sea…/uplussea/accounts/v2/login`, made-up account, zone 92, 2026-11-14 (`HaierLoginProbeTest`) | Probe added (opt-in, `HAIER_LOGIN_PROBE=1`) | Two requests | Configured identity → `HTTP 200 retCode 30032 Account is not registered`. Wrong app key → `HTTP 403, not JSON`. | **Confirmed.** The configured identity is accepted by Haier (its signature passes). The two cases were both reported as "REJECTED". | Classify them separately (H4). |
| H4 | 30032 means "no account in this region", not necessarily a wrong password | haismart-local `countries.py` and `config_flow.py`: "an account that exists but was registered under a different region comes back as retCode 30032 'Account is not registered'"; its UI maps 30032 to a region message | New `AcError.AccountNotInRegion`; the sign-in form now names the country as the likely cause | Unit tests | Pass | **Plausible cause of the owner's failure.** The account may have been created under another country in HaiSmart. | Owner re-runs once, choosing the country from the HaiSmart app (see "Owner action"). |
| H5 | Our country list could not express the owner's account country | Only 11 countries were offered; haismart-local lists 102 | Full list (102 dialling codes, Pakistan first) | Compiles; existing tests | Pass | **Confirmed gap**, now closed. | — |
| H6 | Endpoint, headers, body or crypto differ from the working reference | haismart-local `cloud.py` (current `main`) login: host `uhome-sea.haieriot.net`, path `/uplussea/accounts/v2/login`, headers (appId, appVersion 5.5.0, apiVersion v1, clientId = per-install 32-hex, sequenceId = timestamp+"000010", sign, timestamp in ms, language en-us, timezone 8, zoneInfo = country code), body `username/password/sesame`, AES-128-CBC key=iv=16 random digits, RSA-PKCS1 v1.5 | None needed | Field-by-field comparison; existing deterministic crypto and signing vectors | Identical | **Weakened.** Request construction matches the reference, and H3 shows the signature is accepted. | — |
| H7 | A wrong app identity would wrongly cost users their saved sign-in | `HaierAccount.fetchCredential` treated any REJECTED during renewal as an account refusal | New `Kind.APP_REFUSED` for HTTP 401/403 without JSON: never marks an account for sign-in and never deletes a saved password; user sees `ServiceRefusedApp` | Unit test (refresh and re-login both 403 → account and password kept) | Pass | **Confirmed bug, fixed.** It would also have hit every user if Haier ever blocked the app identity. | — |
| H9 | The route itself is permitted by the spec | requirement.md §1.4(8), §4.3, §6.4 steps 5 and 8; `HaierCloudClient` sends the HaiSmart app's appId/appKey (from haismart-local) | Release builds omit vendor app identities unless the owner opts in | `:app:testReleaseUnitTest` (ReleaseVendorIdentityTest), `:app:assembleRelease`, `:app:lintRelease` | Pass, 2026-11-14 | **Confirmed conflict.** This is a private API reached by posing as the vendor app, not a supported third-party route. | Owner decision (Route status). |
| H8 | The owner's account uses Google/Facebook sign-in (no password) | The HaiSmart sign-in form explains this case; haismart-local states such accounts cannot sign in with a password | None | — | — | **Unresolved.** The owner says the same email and password work in HaiSmart, which suggests a password account. | — |

## Route status

- The sign-in route reproduces the HaiSmart app's own private API. It is not a published
  third-party interface, so Haier's acceptance of the app identity is a compatibility risk. It is
  accepted as of 2026-11-14 (H3).
- No official third-party authorisation route (OAuth, SDK) exists for HaiSmart. See
  MANUFACTURER_CAPABILITIES.md.
- **Spec conflict (H9, 2026-11-14).** The route sends the HaiSmart app's own appId/appKey, so RoomAC
  presents itself to Haier as the HaiSmart app. requirement.md §1.4(8) ("MUST NOT impersonate a vendor
  app or bypass provider controls") and §4.3 rule this out, and §6.4 step 8 says a route that needs
  proprietary client identity is to be classified as blocked. Midea's NetHome Plus sign-in has the same
  problem. **Status of this route for distribution: Not supported (excluded for legal and security
  reasons) until an authorised decision-maker accepts it in writing (§13).**
  - Release builds now leave both identities out (`app/build.gradle.kts`; enforced by
    `app/src/testRelease/.../ReleaseVendorIdentityTest.kt`). Sign-in then shows as unavailable, and
    local discovery, the handshake and control with an existing key keep working. The owner can opt in
    with `roomac.release.vendorAppIdentity=true` in local.properties.
  - Debug builds keep the route so the owner can finish the diagnosis below. That is a research use and
    does not count as acceptance.
  - Capturing the official app's traffic to recover more of its private client identity or signing
    is outside this spec and is not part of the plan.
  - Legitimate ways forward: Haier opens a partner/OAuth programme; or a per-device key comes from a
    route the user controls, for example a local key exchange the AC itself offers (none known yet).
- Local discovery and the uSS handshake were not touched by this work. They remain covered by
  `UdiscoveryTest`, `HaierUssClientTest` and the opt-in `HardwareSmokeTest` (passing on the real
  AC, 2026-11-14).

## Owner action (one run)

Run in your own PowerShell window. The app identity is now read automatically, and the password is
typed into a hidden prompt.

```powershell
$env:HAISMART_USER="<your HaiSmart email or phone>"; $s=Read-Host "HaiSmart password" -AsSecureString; $env:HAISMART_PASSWORD=[Runtime.InteropServices.Marshal]::PtrToStringBSTR([Runtime.InteropServices.Marshal]::SecureStringToBSTR($s))
$env:HAISMART_REGION="92"; $env:AC_DEVICE_ID="540853BD1D41"; $env:AC_HOST="192.168.18.68"
.\gradlew.bat :driver-haismart:test --tests '*HaierAccountSmokeTest*' --rerun-tasks -i
```

Report the lines starting `app identity:`, `haier account: sign-in failed` (if any), `sign-in:`,
`account devices:`, `key fetch:` and `local read`. These lines contain no secrets.

- If the reason is `retCode 30032`: set `HAISMART_REGION` to the dialling code of the country
  chosen when the HaiSmart account was created. That is shown in the HaiSmart app's account or
  region settings; it is not necessarily Pakistan. Then run once more.
- Do not retry more than that without new information, to avoid locking the account.
