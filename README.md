# Room AC: local Wi-Fi control for air conditioners

An Android app that finds air conditioners on the home Wi-Fi, pairs with them, and controls them
**directly over the local network**. Everyday control needs no cloud connection. Built for simple,
elderly-friendly use.

> **Status (2026-11-13, version 2.3.0).** Zero manual technical setup: the app searches the Wi-Fi on
> open, the user taps an AC, and the connection is set up automatically. Two integrations:
>
> - **Haier** (uSS): one-time in-app sign-in to the HaiSmart account (more accounts can be added); the
>   app then keeps the sign-in alive and fetches and renews each AC's key by itself.
>   Discovery, identity and handshake checked on a real AC
>   (`HSU-20HFTEX/013WUSDC(OW)-T3`). **The account sign-in/key fetch has not yet been run against
>   Haier's live service, and status/control are not yet verified on hardware.**
> - **Gree** and Gree-module OEM units: fully automatic LAN pairing (no key, no account). Tested
>   against a simulated module only. **Not yet tested on a real unit.**
>
> Midea, AUX, TCL, Dawlance, Daikin, Orient, PEL, Panasonic, LG, Samsung and Kenwood are on the
> roadmap. See [docs/COMPATIBILITY_MATRIX.md](docs/COMPATIBILITY_MATRIX.md). No model is marked
> "Verified" yet.

## What it does

| Step | How |
|---|---|
| **Discovery** | Each brand's own LAN search, run in parallel (Haier UDP 7083, Gree UDP 7000): broadcast plus a sweep of the phone's subnet. No IP address needed. One AC is listed once. |
| **Identification** | Brand, model and name where the unit reports them; device ID (Wi-Fi MAC) as identity. Devices are found again after DHCP changes. |
| **Authentication** | Gree: automatic LAN bind. Haier: one-time sign-in to the HaiSmart account, then each AC's key is fetched from the account automatically, matched by device ID and proven by a local read. Keys are stored encrypted and renewed automatically if the AC rejects them. |
| **Local control** | Every command is **confirmed by reading the AC's status back**. Controls appear only when the unit reports them. |

## Project layout

| Module | Role |
|---|---|
| `core-domain` | Models: status, commands, capabilities, errors; **brand registry** (`AcBrands`); Haier model profiles |
| `core-protocol` | **Adapter interface + registry**, multi-brand discovery manager, connection coordinator (states, single-session lock, polling, rediscovery, automatic provisioning, verification); `CloudAccountProvider` |
| `driver-haismart` | Haier uSS adapter; `cloud/`: HaiSmart account sign-in and key retrieval |
| `driver-gree` | Gree Wi-Fi adapter |
| `app` | Android: Compose UI (remote, pairing, sign-in, devices, settings), Keystore-encrypted storage, network monitoring |

## Documentation

- [docs/REQUIREMENTS.md](docs/REQUIREMENTS.md): requirements baseline
- [docs/PROGRESS.md](docs/PROGRESS.md): audit summary, phase plan, progress, known issues
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): layers, components, how to add a brand
- [docs/COMPATIBILITY_MATRIX.md](docs/COMPATIBILITY_MATRIX.md): model-level records and brand roadmap
- [docs/RESEARCH_FINDINGS.md](docs/RESEARCH_FINDINGS.md): review of the feasibility study, per-brand leads and conflicts
- [docs/DISCOVERY_AND_AUTH.md](docs/DISCOVERY_AND_AUTH.md): discovery, authentication, provisioning
- [docs/PROTOCOL.md](docs/PROTOCOL.md) (Haier) and [docs/PROTOCOL_GREE.md](docs/PROTOCOL_GREE.md)
- [docs/SECURITY.md](docs/SECURITY.md): security implementation notes
- [docs/SETUP_GUIDE.md](docs/SETUP_GUIDE.md), [docs/BUILD_AND_INSTALL.md](docs/BUILD_AND_INSTALL.md)
- [docs/TEST_REPORT.md](docs/TEST_REPORT.md), [docs/AUDIT.md](docs/AUDIT.md)
- [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)
