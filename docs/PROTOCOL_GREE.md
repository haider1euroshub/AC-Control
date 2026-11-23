# Gree Wi-Fi local protocol (`gree-wifi`)

Independent Kotlin implementation in `driver-gree/`. The protocol facts were cross-checked against
greeclimate (GPL-3.0, facts only, see THIRD_PARTY_NOTICES.md). **Not yet tested on a real Gree unit.**

## Transport

JSON over UDP 7000. Each request gets one reply from the same address. The client waits 2.5 s and
tries twice.

Outer envelope: `{"cid":"app","i":0|1,"t":"pack","uid":0,"tcid":<mac>,"pack":<base64>[,"tag":<base64>]}`.
`i=1` marks scan/bind (generic key); `i=0` marks status/cmd (device key).

## Encryption

| Version | Cipher | Generic key | Notes |
|---|---|---|---|
| v1 | AES-128-ECB, PKCS#7 | `a3K8Bx%2r8Y7#xDh` | Vector checked against OpenSSL in tests |
| v2 | AES-128-GCM, nonce `54 40 78 44 49 67 5a 51 6c 5e 63 13`, AAD `qualcomm-test` | `{yxAHAY_Lm6pbC/<` | 16-byte tag sent separately in `"tag"` |

Bind tries v1 first, then v2. The stored credential is `"<version>:<device key>"`.

## Messages

| Step | Inner pack sent | Reply |
|---|---|---|
| Scan | (plain) `{"t":"scan"}` | `{"t":"dev","mac","brand","model","name","ver",...}` |
| Bind | `{"t":"bind","mac":<mac>,"uid":0}` | `{"t":"bindok","mac","key","r":200}` |
| Status | `{"t":"status","mac":<mac>,"cols":[...]}` | `{"t":"dat","cols":[...],"dat":[...]}` |
| Command | `{"t":"cmd","mac":<mac>,"opt":[...],"p":[...]}` | `{"t":"res","opt":[...],"val":[...],"r":200}` |

## Property mapping

| App control | Property | Values |
|---|---|---|
| Power | `Pow` | 0/1 |
| Mode | `Mod` | 0 auto, 1 cool, 2 dry, 3 fan, 4 heat |
| Setpoint | `SetTem` (+ `TemUn`=0, `TemRec`=0 always sent with it) | °C, 16–30 |
| Fan | `WdSpd` | 0 auto, 1 low, 3 medium, 5 high (2 and 4 are read as "other speed") |
| Up/down swing | `SwUpDn` | set 1/0; read 1 and 7–11 as swinging |
| Left/right swing | `SwingLfRig` | 1/0 |
| Turbo / Health / Display / Energy saving | `Tur` / `Health` / `Lig` / `SvSt` | 0/1 |
| Quiet | `Quiet` | set 2/0; read non-zero as on |
| Sleep | `SwhSlp` + `SlpMod` | both 0/1 |
| Room temperature | `TemSen` | 0 = no reading; ≥40 → minus 40; otherwise as is (heuristic, unverified) |

Controls are offered only for properties the unit returned with a numeric value. ECO levels and
self-clean are Haier-only and are never sent to a Gree unit.

## Verification

The module's `res` reply only means the command was received. A command is **Confirmed** only when a
status read afterwards shows it (one retry after 0.7 s). `r` ≠ 200 → **Rejected**. No reply to a
status request while the unit still answers a scan → the key is no longer valid → automatic re-pair.

## Open questions for hardware testing

- Which `cols` real units return, and whether unsupported ones come back as `""` or are omitted.
- Whether `TemSen` uses the +40 offset on the tested model.
- Whether Heat is refused by cooling-only units (it is offered, marked unverified).
