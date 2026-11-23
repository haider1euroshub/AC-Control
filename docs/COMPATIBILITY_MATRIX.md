# AC compatibility matrix

Per-brand account and pairing answers: [MANUFACTURER_CAPABILITIES.md](MANUFACTURER_CAPABILITIES.md).

Model-level records, as required by docs/REQUIREMENTS.md §8.1. A manufacturer or protocol being
integrated does **not** mean any model of it is supported. Status values (§8.2): Not tested,
In investigation, Partially tested, Verified, Issue identified.

**No model is "Verified" yet.** No physical phone test and no control command on real hardware has
been carried out (see TEST_REPORT.md).

## Model records

| Manufacturer | Model | Protocol | Discovery method | Authentication method | Credential provisioning method | Local communication method | Supported controls (in code) | Test status | Notes |
|---|---|---|---|---|---|---|---|---|---|
| Haier | HSU-20HFTEX/013WUSDC(OW)-T3 (device ID 540853BD1D41, module e_4.3.00 / R_6.0.01) | `haier-uss` | UDISCOVERY, UDP 7083 (broadcast + subnet sweep) | uSS handshake bound to device ID, AES-128 with per-device local key | **Automatic from the HaiSmart account** (one-time in-app sign-in; key fetched over Haier's MQTT/TLS gateway, matched by device ID, proven by a local read). Implemented 2026-11-13; not yet run against the live service. | TCP 56800, short sessions, read-back verification | Power, mode, 16–30 °C, fan, V/H swing, Turbo, Quiet, Sleep, Health, Display, ECO L1–L3, self-clean | **Partially tested** | Discovery, identity and key-free handshake confirmed on this unit 2026-11-11 from a PC and an emulator. Status read and every control not yet tested (local key not available). |
| Haier | Other models with uPlusId 2008…0040 | `haier-uss` | as above | as above | as above | as above | as above | Not tested | Same published profile; expected to behave the same, unproven. |
| Haier | Any other uPlusId / layout | `haier-uss` | as above | as above | as above | as above | Read-only unless the report is a classic 125/127-byte layout | Not tested | Unknown layouts are shown read-only by design. |
| Gree | (no model tested) | `gree-wifi` | `{"t":"scan"}` UDP 7000 (broadcast + subnet sweep) | Per-device key; AES-128-ECB (v1) or AES-128-GCM (v2) | **Automatic**: LAN bind returns the device key; no account, no user input | UDP 7000 JSON status/cmd; read-back verification | Power, mode (incl. Heat, unverified), 16–30 °C, fan Auto/Low/Medium/High, V/H swing, Turbo, Quiet, Sleep, Health, Display, Energy saving — each only if the unit reports the property | Not tested | Implemented 2026-11-12 against the documented protocol and a simulated module. Needs a real Gree unit. |
| Gree-protocol OEM units (brand reported by the module) | (none tested) | `gree-wifi` | as Gree | as Gree | as Gree | as Gree | as Gree | Not tested | The app shows the brand the module reports. Do **not** assume a given brand/model uses a Gree module until a scan shows it. |

## Brand roadmap status

| Order | Brand | Market priority | Adapter | Status | Research leads (unverified; source: feasibility study, see RESEARCH_FINDINGS.md) |
|---|---|---|---|---|---|
| 1 | Haier | 1 | `haier-uss` | Partially tested | In-app account sign-in and automatic key retrieval implemented in 2.2.0 (not yet tested live). |
| 2 | Gree | 2 | `gree-wifi` | Implemented, not verified on hardware | Some newer Gree modules are reportedly cloud-only (no UDP 7000) and will not be found. OEM brands named: Tosot, Toyotomi, Cooper & Hunter, Tadiran, Heiwa, Ekokai, Wilfa. |
| 3 | Midea | 6 | none | In investigation | Midea LAN protocol v1–v3 (AES); v3 needs a token + key from the Midea cloud (same login question as Haier). Ports unconfirmed: study says 6666; community code uses UDP 6445 discovery / TCP 6444 control. Capture first. |
| 4 | AUX | 7 | none | In investigation | Study: "likely" a Midea module. Unconfirmed; capture a discovery scan from a real unit. |
| 5 | TCL | 8 | none | In investigation | Study: many TCL units are Midea OEM. Unconfirmed; capture first. |
| 6 | Dawlance | 3 | none | In investigation | Arçelik ecosystem (hOn / HomeWhiz); community integration controls over Bluetooth or the cloud depending on model, so a local Wi-Fi path is not established. Capture first. |
| 7 | Daikin | 9 | none | In investigation | BRP069/BRP072 Wi-Fi adapters; discovery on UDP 30050; control reportedly HTTP (HTTPS + key on some adapters). Study says no key unless a password was set. Capture first. |
| 8 | Orient | 4 | none | In investigation | Study: smart lineup unclear, possibly none or a Midea-derived module. Confirm a Wi-Fi model exists. |
| 9 | PEL | 5 | none | In investigation | Study: possibly rebadged Fujitsu/Carrier tech, likely no Wi-Fi by default. Confirm a Wi-Fi model exists. |
| 10 | Panasonic | 10 | none | Not started | Comfort Cloud; study mentions local control on some units with a token but did not research it. |
| 11 | LG | 11 | none | Not started | ThinQ, reportedly cloud-only with no local API. |
| 12 | Samsung | 12 | none | Not started | SmartThings, reportedly cloud-only with no local API. |
| 13 | Kenwood | 13 | none | Not started | Study only notes the brand appears in price lists. |

The same order and status live in code in `core-domain/.../AcBrands.kt`.

**Research source:** the feasibility study (the "Deep Research PDF" of REQUIREMENTS.md §14) was
reviewed on 2026-11-12. Its claims are unverifiable from the PDF alone, so they appear above only as
leads; details and conflicts with verified results are in RESEARCH_FINDINGS.md.

## How to add a model record

1. Run the app's discovery (or `tools/` probes) and save the anonymised reply to `evidence/network/`.
2. Pair and run through TEST_REPORT.md §3. Every control the AC confirms is recorded on the device
   ("Controls verified on this unit" in Device settings).
3. Add a row above with the exact model from the rating label and the controls actually verified.
