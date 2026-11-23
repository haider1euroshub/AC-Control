# Manufacturer capability matrix

Answers the ten questions of the 2026-11-14 requirements (§4.5) for each of the 13 manufacturers.
Written 2026-11-14.

**How to read this.** Each answer says where it comes from:

- **Code:** implemented in RoomAC (tested only against simulations unless it says otherwise).
- **HW:** observed on a real AC.
- **Lead:** from a public community project or forum. Not verified by us, and it may not apply to
  every model or region.
- **Unknown:** not established.

A "Lead" is not support. **No manufacturer is "Verified".** No control command has been confirmed on
real hardware yet (TEST_REPORT.md).

## Summary

| Brand | In RoomAC today | In-app sign-in | In-app account creation | Manufacturer app still needed for |
|---|---|---|---|---|
| Haier | `haier-uss` adapter, partially tested on HW | **Yes** (Code; not yet run live) | **No:** no verified interface | Adding a *new* AC to an account (Wi-Fi setup + binding), creating an account, password reset |
| Gree | `gree-wifi` adapter | Not needed: no account | Not needed | Initial Wi-Fi setup of the module (Lead: Gree+ app or the module's AP mode); not handled in RoomAC |
| Midea | `midea-v2` + `midea-v3` adapters, implemented, unverified (no unit available) | V3 only: NetHome Plus (Code; not run live). V2: no account | No | Unknown
| AUX | None | Not built | Unknown | Unknown |
| TCL | None | Not built | Unknown | Unknown |
| Dawlance | None | Not built | Unknown | Unknown |
| Orient | None | Not built | Unknown | Unknown |
| PEL | None | Not built | Unknown | Unknown |
| Daikin | None | Not needed for older adapters (Lead) | Not needed (Lead) | Unknown |
| Panasonic | None | Not built | Unknown | Unknown |
| LG | None | Not built | No: LG accounts are created at LG (Lead) | Unknown |
| Samsung | None | Not built | No: Samsung accounts are created at Samsung (Lead) | Unknown |
| Kenwood | None | Not built | Unknown | Unknown |

## Account registration methods (checked 2026-11-14)

The three registration methods of the 2026-11-14 requirements (§4.2), each investigated separately.

- **A, native:** a RoomAC form submits to a verified manufacturer interface.
- **B, official web page:** the manufacturer's own sign-up page, shown in a Custom Tab inside RoomAC.
- **C, authorised linking:** OAuth, an official SDK or account sharing.

A web page counts for B only if the account it creates is shown to be the account the AC service
uses. A shop, warranty or website-member account does not count.

| Brand | A, native | B, official web page | C, authorised linking | Evidence |
|---|---|---|---|---|
| Haier (HaiSmart) | Not available: no verified interface | **None for HaiSmart accounts.** haier.com/pk and /in have no sign-up (`/pk/login/`, `/in/login/` return 404); the HaiSmart page only says to download the app. haier.com/ph/register/ exists, but its script posts to `www.haier.com/ids/ph/do_regUser.jsp`, the website-member system, not the `uhome-sea` uplussea service HaiSmart and RoomAC sign in to. No page links the two. | No published OAuth or SDK. Google/Facebook HaiSmart sign-up has no documented third-party flow. | haier.com/pk/service-support/haismart-app/; haier.com/ph/register/ + hw2019_IDS_register.js; haismart-local |
| Gree | Not needed for local control (no account) | None found: "open the GREE+ app ... to sign up" | None found | greecomfort.com/news-and-events/the-gree-wifi-app/ |
| Midea | Unknown | Not established (manual too large to fetch; to recheck) | Unknown | — |
| Panasonic | Unknown | Not established (official page returned 403 to our fetch; to recheck in a browser) | Lead: Panasonic ID with Apple/Google | — |
| LG | Unknown | Not established (article text did not load) | **ThinQ Connect PAT** (official; user-created on LG's site) | thinq.developer.lge.com |
| Daikin | Unknown | **No standalone page:** Daikin ID is created from "any application ... login page ... register here"; email-link verification | Daikin ID supports Google/Apple/Facebook | id.daikin.eu/info/lv/faq/faq2.html |
| TCL | Unknown | None found: "download the TCL Home app" | App login with Apple/Google/Facebook | support.tcl.com/en_CA/creating-an-app-based-tcl-home-account |
| Samsung | Not available | **Yes:** account.samsung.com, "Create account"; email verification; "Your SmartThings account is synced with your Samsung account" | **SmartThings OAuth** (needs a registered developer app) | samsung.com/us/support/answer/ANS10001482/ |
| Dawlance, Orient, PEL, AUX, Kenwood | Unknown | Not researched beyond app names (HomeWhiz / AC Freedom) | Unknown | — |

**What RoomAC implements.** `AccountFeatures.webRegistration` lets a provider declare a verified
official sign-up page. The sign-in form then shows "Create a … account", which opens the page in a
Custom Tab. The URL must be https on a declared host; look-alike hosts, user-info tricks and other
ports are refused (TrustedUrlTest). A Custom Tab is used instead of a WebView, so RoomAC cannot read
or inject into the page, and Google/Apple buttons work as those providers require.

No provider declares a page yet:

- **Haier:** has none.
- **Samsung:** has one, but RoomAC has no Samsung integration. A sign-up button with nothing to sign
  in to would be a superficial feature.

## Per-manufacturer answers

Questions: (1) account required · (2) in-app account creation · (3) in-app login · (4) manufacturer
browser authorisation · (5) official app needed for first pairing · (6) discovery without the
official app · (7) local credential retrieval · (8) automatic credential refresh · (9) local control
offline after setup · (10) verified models and protocols.

### Haier (HaiSmart, SE-Asia service)

1. **Yes.** The AC's local key is issued to the account the AC was set up with (Code, from
   haismart-local).
2. **No** (see "Account registration methods" above for methods A, B and C). haismart-local (MIT, read in full on 2026-11-14) has no account creation,
   verification-code or password-reset endpoint. No published Haier API offers one. Building one
   would mean inventing endpoints, which the requirements forbid. The sign-in form explains this
   (`HaierAccount.noRegistrationHelp`).
3. **Yes** (Code): email/phone + password, country chosen by the user, password encrypted before
   sending, stored only with "Stay signed in". Not yet run against the live service.
4. **No.** The login is a signed API call, not a browser flow.
5. **Yes, for adding a new AC to an account.** Wi-Fi provisioning and binding have no verified
   interface. RoomAC explains this and offers "Add the AC in HaiSmart" (opened only when the user
   taps it). That is the minimum unavoidable step, once per AC. ACs already in an account need nothing
   from HaiSmart.
6. **Yes** (Code + HW): UDISCOVERY on UDP 7083, no key needed.
7. **Yes** (Code): `devLocalkey` over Haier's MQTT/TLS gateway, proven on the AC before saving.
   Not yet run live.
8. **Yes** (Code): access token from the refresh token; a fresh sign-in with the saved password if
   the user chose that; the local key is re-fetched when the AC reports a new key version or rejects
   the key.
9. **Yes, after setup** (Code): local TCP 56800 with the stored key. Internet is needed again only
   when the key changes.
10. HSU-20HFTEX/013WUSDC(OW)-T3: discovery and a key-free handshake confirmed (HW). Status read and
    controls not yet confirmed (no key available at the time).

**Distribution risk:** sign-in uses the HaiSmart app's own app identity against an unpublished API.
Haier could block it. The owner should confirm before public release (DISCOVERY_AND_AUTH.md).

### Gree (and Gree-module OEM brands)

1. **No** for local control (Code).
2. Not applicable.
3. Not applicable.
4. No.
5. **Initial Wi-Fi setup only** (Lead): the module must first join the home Wi-Fi, which the Gree+
   app or the module's own access-point mode does. RoomAC does not implement Wi-Fi provisioning.
   Once on the network, RoomAC binds by itself.
6. **Yes** (Code): `{"t":"scan"}` on UDP 7000.
7. **Yes** (Code): the LAN bind returns the device key, no account.
8. **Yes** (Code): automatic re-bind once if the key stops working.
9. **Yes** (Code).
10. None verified. Some newer Gree modules are reportedly cloud-only (Lead) and will not be found.

### Midea (also used by some other brands)

1. **Depends on module version** (Lead, msmart-ng): V3 devices need a token and key that come from
   the Midea cloud. V1/V2 devices need none.
2. Unknown.
3. **Possible** (Lead): msmart-ng logs in to the Midea cloud (NetHome Plus / SmartHome / MSmartHome
   accounts) to fetch the V3 token and key. By default it uses a built-in shared account, which
   RoomAC must not copy. Using the user's own account needs verification.
4. Unknown.
5. Unknown.
6. **Yes** (Lead): LAN discovery is documented by msmart-ng.
7. **Yes for V3, through the cloud** (Lead).
8. Unknown. msmart-ng advises saving the token and key in case the cloud becomes unavailable.
9. **Yes** (Lead): "works locally with no internet connection required".
10. None. **Next step:** capture a discovery reply from a real unit, then port the V2/V3 protocol
    into a `driver-midea` adapter.

#### Midea: detailed research (2026-11-14, msmart-ng / midea-ac-py)

Source: github.com/mill1000/midea-msmart (PyPI `msmart-ng`) and github.com/mill1000/midea-ac-py (the
Home Assistant integration). Both are **MIT** licensed (copyright Tucker Kern, Mac_Zhou, NeoAcheron and
others). They can be ported with attribution in THIRD_PARTY_NOTICES.md. Community code, not a Midea API.
Midea publishes no developer program for this.

| Topic | Finding |
|---|---|
| Device types | Air conditioners only: 0xAC and 0xCC (commercial). V1 (XML) modules are not supported. |
| Discovery | UDP broadcast and unicast to ports **6445** and **20086**, a fixed 72-byte request, each sent 3×. The reply header gives the version (`5A5A` = V2, `8370` = V3). An AES-ECB block (fixed key) carries IP, port, SN and name (`net_ac_XXXX`, from which the type is read). No model field; a model hint is in `sn[9:17]`. |
| Local control | TCP **6444**. V2: 40-byte header, AES-ECB payload under MD5(fixed sign key), MD5 signature; **no credential**. V3: `8370` frames wrapping V2 packets; a handshake with a 64-byte **token** and 32-byte **key** derives a session key (AES-CBC, SHA-256), re-done every 12 h with the same token and key. |
| Credential source (V3) | Midea cloud `getToken` by `udpid` (SHA-256 of the device ID, folded). Login is NetHome Plus (`mapp.appsmb.com`, app ID 1017) using the app's own app key. Works with the **user's own** NetHome Plus account (`--account/--password`). msmart-ng defaults to **built-in shared accounts** (mailinator addresses); RoomAC must not use those. |
| Cloud use | Only token/key retrieval (and optional plugin download). **All control is local.** |
| Credential lifetime | Not stated. The READMEs imply it is long-lived ("once retrieved and saved, no further cloud connection is required"). Re-pairing behaviour is unknown. |
| Offline | Yes after setup ("no internet connection is required to control your device"). V2 never needs the cloud. |
| Apps that indicate support | NetHome Plus, SmartHome/MSmartHome, Midea Air, Artic King, Toshiba AC NA, 美的美居 |
| OEM brands listed by midea-ac-py | Carrier, Comfee, Electrolux, Toshiba, Kenmore, Mr. Cool, Senville, Pioneer, Inventor, and some LG and Samsung models, among others. **Orient, PEL, Dawlance and TCL are not listed.** Midea OEM status for those stays unverified until a scan shows a Midea module. |
| Same issue as Haier | Cloud access uses a manufacturer app's embedded app ID/key, not a published API. The owner must accept the same distribution risk. |

**Implemented 2026-11-14 (`driver-midea`), status: Implemented, unverified.**

- **Adapters:** `midea-v2` (no credential, automatic) and `midea-v3` (token and key from the user's
  own NetHome Plus account via `MideaAccount`). One scan reports both generations.
- **Controls:** power, mode, 17–30 °C, fan, vertical swing, turbo, sleep and eco (energy saving),
  each written as a full state built from a fresh read and confirmed only by reading back.
- **Tested** against msmart's published captured traffic: a V2 packet, a V3 packet, V2/V3 discovery
  replies and three state replies all decode to the expected values. Simulated V2 and V3 modules over
  real sockets cover handshake, wrong key, read-back, ignored write and unreachable. A simulated
  NetHome Plus covers signing, password hashing, both udpid byte orders, refusal, no internet and
  stay-signed-in.
- **Not implemented:** the capability query (0xB5), so offered controls may exceed a given model
  (each stays marked unverified); °F units are read-only; display toggle; V1 (XML) modules.
- **Hardware:** no Midea unit available (scan 2026-11-14 found none).

### AUX

1. **Unknown for local control.** AUX units use the AC Freedom app, which is built on Broadlink
   (Lead). Community LAN control (broadlink_ac_mqtt) authenticates with the Broadlink LAN handshake,
   not an account (Lead).
2. Unknown.
3. Not built. A community AUX Cloud integration exists (Lead).
4. Unknown.
5. Unknown.
6. **Likely** (Lead): Broadlink LAN discovery.
7. Unknown.
8. Unknown.
9. **Some units only** (Lead): "some newer AUX air conditioners do not have local control
   possibility enabled".
10. None.

### TCL

1. Unknown.
2. Unknown.
3. Not built. Community integrations impersonate the TCL Home app against TCL's cloud (Lead); RoomAC
   will not do that without verification.
4. Unknown.
5. Unknown.
6. **Some units** (Lead): a community integration does local LAN control over BroadLink/DNA framing
   (tested by its author on TCL XA71I) and mentions "accountless local pairing".
7. Unknown.
8. Unknown.
9. Some units (Lead).
10. None. Some TCL units are reportedly Midea OEM (Lead, from the feasibility study).

### Dawlance

1. Unknown.
2. Unknown.
3. Not built.
4. Unknown.
5. Unknown.
6. Unknown.
7. Unknown.
8. Unknown.
9. Unknown.
10. None. Wi-Fi models use the HomeWhiz app (Arçelik group), and a "Dawlance Connect" app is also
    mentioned (Lead, retailer listings). No local Wi-Fi protocol is established. A capture from a real
    unit is the first step.

### Orient (Pakistan)

All ten: **Unknown.** An "Orient AC remote" app exists (Lead). It is not established which Wi-Fi
module or protocol it uses. First step: confirm a Wi-Fi model and capture its discovery traffic.

### PEL

All ten: **Unknown.** No public information found on a PEL Wi-Fi AC protocol. First step: confirm a
Wi-Fi model exists and capture its traffic.

### Daikin

1. **Not for older adapters** (Lead): BRP069 adapters expose a local HTTP API with no
   authentication.
2. Not applicable.
3. Not applicable for local control.
4. No.
5. Unknown.
6. **Yes** (Lead): UDP discovery (port 30050 per the feasibility study).
7. **BRP072C needs a 13-digit key printed on the adapter** to register a client UUID (Lead). The
   requirements forbid asking users for keys, so these adapters would need a decision: reading the
   label with the camera, or not supporting them.
8. Not applicable once a UUID is registered (Lead).
9. **Yes** (Lead).
10. None.

### Panasonic

1. **Yes** (Lead): Comfort Cloud account.
2. Unknown.
3. **Possible** (Lead): community libraries log in with the Comfort Cloud email and password; 2FA
   must be set up in the Panasonic app first.
4. Unknown.
5. Likely (Lead).
6. **No local discovery known** (Lead).
7. Not applicable: control goes through the cloud.
8. Unknown.
9. **No** (Lead): "relies on the cloud and therefore does not provide full local control".
10. None.

### LG

1. **Yes** (Lead): LG ThinQ account.
2. No: accounts are created at LG.
3. **Officially, via ThinQ Connect** (Lead): LG publishes the ThinQ Connect API. Personal use
   authenticates with a Personal Access Token created by the user at connect-pat.lgthinq.com.
   Partner access needs LG's approval.
4. **Yes:** token creation happens on LG's site.
5. Yes (Lead): appliances are registered in the ThinQ app.
6. No local API known (Lead).
7. Not applicable: cloud control.
8. Unknown.
9. **No** (Lead): cloud only.
10. None.

### Samsung

1. **Yes** (Lead): Samsung account through SmartThings.
2. No: accounts are created at Samsung.
3. **Officially, via SmartThings OAuth** (Lead): a registered SmartThings app with client ID and
   OAuth flow. Personal Access Tokens are no longer recommended since late 2024.
4. **Yes:** OAuth runs in Samsung's browser flow.
5. Yes (Lead): appliances are added in the SmartThings app.
6. No local API known (Lead).
7. Not applicable: cloud control.
8. Yes, OAuth refresh tokens (Lead).
9. **No** (Lead): cloud only.
10. None. Needs a registered SmartThings developer app.

### Kenwood (Pakistan)

1. Unknown.
2. Unknown.
3. Not built.
4. Unknown.
5. Unknown.
6. **Possibly** (Lead): Kenwood eSmart ACs pair with the **AC Freedom** app (retailer review), the
   same Broadlink-based app as AUX. If confirmed, an AUX/Broadlink adapter could also cover Kenwood.
7. Unknown.
8. Unknown.
9. Unknown.
10. None.

## Sources for the leads

- haismart-local (MIT): https://github.com/enapt/haismart-local
- msmart-ng: https://pypi.org/project/msmart-ng/ ; https://github.com/mill1000/midea-msmart
- Broadlink / AC Freedom (AUX, Kenwood and others):
  https://community.home-assistant.io/t/broadlink-ac-integration-ac-freedom-aux-dunham-rcool-akai-rinnai-kenwood-tornado-ballu/342789
- TCL: https://community.home-assistant.io/t/tcl-intelligent-ac-custom-integration-for-home-assistant-hacs/1011527
- Daikin: https://www.openhab.org/addons/bindings/daikin/ ; https://github.com/daimik/Daikin-BRP069C4x-Local-API
- Panasonic: https://pypi.org/project/aio-panasonic-comfort-cloud/
- LG ThinQ Connect: https://thinq.developer.lge.com/en/cloud/docs/thinq-connect ; PAT:
  https://thinq.developer.lge.com/en/cloud/docs/thinq-connect/PAT-en
- Samsung SmartThings: https://developer.smartthings.com/docs/getting-started/authorization-and-permissions
- Dawlance HomeWhiz (retailer listing): https://hadielectronics.com.pk/product/dawlance-chrome-pro-t3-inverter-30-home-whiz-1-5-ton-split-ac/
- Kenwood eSmart / AC Freedom (retailer review): https://www.aysonline.pk/kenwood-esmart-ac-review/

## Recommended integration order (by what can be verified fastest)

1. **Haier:** run the account smoke test against the live service with the owner's account, then the
   on-device test matrix.
2. **Gree:** test on any real Gree unit.
3. **Midea:** largest local-control footprint (also some TCL/OEM units). Needs a capture and the
   owner's own Midea-app account.
4. **AUX + Kenwood:** a shared Broadlink/AC Freedom adapter, if a capture confirms LAN control.
5. **Daikin:** decide how BRP072C label keys are handled.
6. **Panasonic, LG, Samsung:** cloud-only. Each needs a cloud adapter type that RoomAC does not have
   yet, and LG/Samsung need developer registration.
7. **Dawlance, Orient, PEL:** start with a capture from a real unit.
