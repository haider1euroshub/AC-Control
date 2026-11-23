# Device discovery, authentication and provisioning

## Discovery

`NetworkDiscoveryManager` runs every registered adapter's discovery at the same time when the
pairing screen opens or the user taps "Scan again". Results are merged and keyed by device ID (the
Wi-Fi module MAC), so one physical AC is listed once. One brand's discovery failing does not hide
the others.

| Protocol | Method | Key needed | Identity reported |
|---|---|---|---|
| Haier uSS | "Haier" UDISCOVERY search 0x6915 on UDP 7083: broadcast from source port 7083, plus a 4 ms-paced unicast sweep of the phone's subnet (≤1024 hosts, nearest first) | No | Device ID (MAC), uPlusId product profile, IP, uSS port, firmware, cloud state |
| Gree Wi-Fi | Plain `{"t":"scan"}` to UDP 7000: broadcast plus the same paced unicast sweep. Reply is encrypted with a public generic key (ECB, or GCM on newer modules) | No | MAC, brand, model, name, firmware version |

Reconnection: the coordinator asks the saved address first with the device's own protocol. If that
address no longer answers as the same device ID (DHCP moved it, or another device took it), it
sweeps the subnet for that ID and saves the new address. Network callbacks and app resume trigger a
re-check.

Not implemented yet: mDNS / SSDP. Neither integrated protocol uses them. They will be added with the
first adapter that needs them, through the same `DeviceDiscovery` interface.

## Credential modes

| Mode | Brands | What the user does |
|---|---|---|
| AUTOMATIC | Gree Wi-Fi | Nothing. The app sends the module's bind request on the LAN (the same mechanism the manufacturer's app uses) and stores the per-device key it returns. If the key later stops working (the unit was reset or bound elsewhere), the app re-pairs once by itself. |
| ACCOUNT | Haier uSS | Signs in once to the HaiSmart account the AC was set up with (more accounts can be added). The app fetches each AC's key from the owning account, stores it encrypted, and renews both the account authorisation and the key by itself (see Renewal). |
| NONE | — | For future protocols with no local authentication. |

Identity checks before control: Haier refuses a session that is not for its device ID; Gree replies
are accepted only from the address asked and, when they carry a MAC, only for the expected MAC.

## Haier: automatic key retrieval (`driver-haismart/.../cloud`)

Protocol facts are from haismart-local (MIT), which verified them against the live service. Our
implementation is tested against simulated services only; **it has not yet been run against Haier's
real service** (TEST_REPORT.md, 2026-11-13 addendum).

1. **Sign-in** (once): `POST https://uhome-sea.haieriot.net/uplussea/accounts/v2/login` with the
   email/phone, the password AES-128-CBC encrypted under a random 16-digit key, and that key
   RSA-encrypted with Haier's public key (`sesame`). Every request carries Haier's header envelope,
   signed `SHA-256(path + body-without-whitespace + appId + appKey + timestamp)`, a `zoneInfo` (the
   account country's dialling code, 92 for Pakistan) and a per-install terminal id chosen at
   sign-in, which the issued tokens are bound to. Stored per account (one Keystore-encrypted blob):
   the refresh token, terminal id, region, email/phone, and the password **only if the user left
   "Stay signed in" on**. Several accounts can be linked.
2. **Access token** (about a day): minted from the refresh token
   (`/uplussea/accounts/v1/user/refreshToken`) when needed, held in memory only; a rotated refresh
   token is saved. See "Renewal" below for when the service refuses it.
3. **Device lookup**: `GET /uplussea/devices/v2/user/devices`, used for the name/model and the ID's
   spelling (MAC; case and separators ignored). Since 2.3.0 the key is requested **even if the AC is
   not listed**: the list can spell IDs differently or omit shared devices, and the key service
   applies the account's real permissions. Only if it refuses (with a fresh token) is the AC
   reported as "not in the signed-in HaiSmart account".
4. **Key**: MQTT 3.1.1 over TLS to `gw-sgp.haieriot.net:58702` (`gw-sea` for India accounts):
   subscribe `Client/<id>/Business/Down`, publish a `devLocalkey` request for the device carrying the
   access token, and take the reply whose `sn` matches. `errNo` is checked before the key.
5. **Proof**: the coordinator reads the AC's status over the LAN with the new key before it is saved
   (pairing) or used (reconnect). The key is accepted only if the AC itself decrypts and answers.

Hosts are chosen from the account's country: India (91) uses the `-sea` data centre, every other
country the `-sgp` one, as the HaiSmart app does.

Failures are classified so the user sees the right next step: no answer, timeout, HTTP 5xx/408/429
or a captive portal → "Could not reach the HaiSmart online service" (retried with backoff; the
account is kept); a refusal → "The HaiSmart account did not accept the sign-in".

### Renewal (2.3.0)

**Account authorisation** and the **AC key** are separate credentials with separate renewal:

| Credential | Lifetime (from haismart-local; not measured by us) | Renewed by, without the user |
|---|---|---|
| Access token | About a day | Minted from the refresh token |
| Refresh token | Durable but not eternal; can expire or be superseded by a sign-in elsewhere | A fresh sign-in with the saved password, **only** if "Stay signed in" was on |
| AC local key | Changes when Haier re-issues it (e.g. the AC is set up again); the AC states its key version in every handshake | Fetched again from the owning account |

Key change detection uses only what the AC itself says at the start of each session: a different
key version in its handshake reply (`KeyRotated`), or a handshake reply our key cannot decrypt
(`KeyInvalid`). Timeouts, unreachable hosts, a busy session or a refused connection **never**
trigger renewal (tested).

On a rejected key the coordinator shows "Refreshing AC connection…", fetches a new key, **proves it
on the AC**, and only then replaces the old one; control resumes. A key rejection seen during a
command starts the same renewal. Limits: one automatic renewal per successful connection; if the
freshly issued key is also rejected, automatic attempts stop until the user taps Reconnect. No
internet → retried with backoff, old key kept, no sign-in prompt. Only when the account itself is
refused (refresh refused and no saved password, or the saved password is refused) does the user see
"Sign in again", pre-filled with their email; a refused saved password is deleted at once and never
retried. Renewing one AC never touches another AC's key (tested).

With several accounts, each AC's key comes from the account that owns it; the link is remembered so
that account is asked first next time.

### Registering a new AC to an account

Not implemented, deliberately. Adding an AC to a Haier account (Wi-Fi provisioning of the module and
account binding) runs through Haier's own app and SDK. Neither haismart-local nor any published
Haier API documents it, and the specification forbids inventing endpoints. When an AC is not in any
linked account, pairing explains the two real causes and offers **Sign in with that account**
(another person's or the installer's) and **Open the HaiSmart app** (or its Play Store page) to add
it there once; after that RoomAC does everything else.

**App identity.** Haier's service only accepts requests signed with the HaiSmart app's own app
ID/app key. These are the same for every install and are published in haismart-local; they are not
user secrets. They are kept out of source control (`local.properties` → `BuildConfig`, see
BUILD_AND_INSTALL.md); a build without them shows Haier sign-in as unavailable. This is not an API
Haier has published or authorised for third parties. Haier could change it, block it, or object to
its use. The 2026-11-13 specification (§4.2) requires in-app key retrieval through the HaiSmart
account and names haismart-local as the reference, so it is implemented; the owner should still
confirm this trade-off before distributing the app publicly (e.g. on Google Play).
