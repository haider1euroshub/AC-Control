# RoomAC --- Complete Engineering Requirements Specification

**File:** `requirement.md`\
**Product:** RoomAC\
**Platform:** Android\
**Specification status:** Authoritative implementation specification\
**Audience:** Android/Kotlin engineers, software architects, IoT
protocol engineers, security engineers, QA engineers, and autonomous
coding agents\
**Primary instruction:** Inspect the existing repository, preserve
working functionality, implement what can be implemented, verify it, and
continue through all phases. A plan or audit alone is not completion.

------------------------------------------------------------------------

## Document control

This document defines the intended product, engineering constraints,
research obligations, implementation phases, verification requirements,
and final reporting format for the existing RoomAC Android project.

The repository is the source of truth for current implementation state.
Historical context in this document is a starting point, not proof that
a feature still works. The coding agent must inspect actual source,
build configuration, tests, Git state, and device behavior before making
claims.

### Normative language

The terms **MUST**, **MUST NOT**, **SHOULD**, **SHOULD NOT**, and
**MAY** express requirement strength:

-   **MUST / MUST NOT:** mandatory acceptance requirement.
-   **SHOULD / SHOULD NOT:** expected default; deviations require a
    documented technical reason.
-   **MAY:** optional capability, subject to feasibility and evidence.

### Evidence and status discipline

Never turn an assumption, research lead, mock result, successful build,
or protocol handshake into a claim of verified device compatibility.

Use these statuses consistently:

  -----------------------------------------------------------------------
  Status                              Meaning
  ----------------------------------- -----------------------------------
  Not investigated                    No meaningful investigation has
                                      been completed.

  Research in progress                Investigation is active; evidence
                                      is incomplete.

  Research complete                   Relevant sources and feasibility
                                      have been assessed and documented.

  Implementation in progress          Code changes are underway.

  Implemented                         Code exists and relevant automated
                                      tests pass; hardware verification
                                      may still be outstanding.

  Implemented, unverified             Implementation exists but the
                                      required end-to-end or hardware
                                      behavior has not been demonstrated.

  Partially supported                 Only a documented subset of models,
                                      functions, or connection methods
                                      works.

  Verified on hardware                The exact model/firmware and listed
                                      operations have been exercised on
                                      physical hardware with recorded
                                      evidence.

  Blocked                             Reasonable applicable routes were
                                      attempted or assessed and a
                                      specific external dependency
                                      prevents further progress.

  Failed                              An implementation or test was
                                      attempted and did not meet its
                                      acceptance criteria.

  Not supported                       Evidence shows there is no
                                      currently viable supported route,
                                      or the route is intentionally
                                      excluded for legal/security
                                      reasons.
  -----------------------------------------------------------------------

A requirement can have a different status at research, implementation,
automated-test, and hardware-verification levels. Record those
dimensions separately where necessary.

------------------------------------------------------------------------

# 1. Executive product definition

## 1.1 Product identity

**RoomAC** is an Android universal air-conditioner controller. Its
purpose is to discover, pair with, authenticate to, and control
compatible air conditioners from multiple manufacturers through one
consistent application.

The intended result is that a user can operate supported AC functions
without routinely switching to a separate manufacturer application.
RoomAC should conceal protocol and networking complexity wherever
technically, legally, and securely possible.

RoomAC is an integration application, not a promise that every AC from
every listed manufacturer is compatible. Compatibility is model-,
firmware-, region-, protocol-, and sometimes account-dependent.

## 1.2 Problem statement

AC manufacturers use different local protocols, cloud services, account
systems, commissioning procedures, and feature sets. This fragments the
user experience. RoomAC aims to provide a common device lifecycle and
control interface while isolating manufacturer-specific behavior in
dedicated drivers.

## 1.3 Intended users

-   People who own one or more compatible Wi-Fi-connected air
    conditioners.
-   Users who want a single control interface for supported devices from
    different manufacturers.
-   Users who prefer local control when it is available and secure.
-   Users who need a guided setup experience rather than manual network
    or protocol configuration.

The app MUST NOT assume that every user has technical networking
knowledge.

## 1.4 Target experience

The intended normal flow is:

1.  The user opens RoomAC.
2.  The user selects **Add AC**.
3.  RoomAC requests only the Android permissions needed for the selected
    discovery/provisioning path and explains why.
4.  RoomAC searches for compatible devices using bounded, user-initiated
    discovery.
5.  It identifies the brand and model where evidence allows; otherwise
    it presents a clear candidate with uncertainty disclosed.
6.  The user selects the intended AC.
7.  RoomAC performs the applicable pairing, provisioning, account
    authorization, or local authentication.
8.  If vendor authorization is required, RoomAC uses a supported native
    authorization flow, OAuth, or secure browser-based flow as
    permitted. It MUST NOT impersonate a vendor app or bypass provider
    controls.
9.  If the manufacturer's app is genuinely required for initial
    commissioning, RoomAC explains the exact reason and gives the
    shortest safe hand-off procedure.
10. RoomAC confirms the connection by performing an actual supported
    read or other meaningful verification---not merely by showing a
    success animation.
11. The user reaches one unified control screen.
12. The user can use only the controls that the exact device/model
    exposes and that RoomAC has implemented.
13. RoomAC securely saves the device configuration and
    credentials/tokens as applicable.
14. RoomAC reconnects when appropriate and recovers gracefully from app
    restarts, network changes, and temporary outages.

## 1.5 Core product principles

1.  **Unified control:** common controls and consistent device
    management across drivers.
2.  **Automatic discovery:** discover compatible devices without asking
    users for IP addresses, MAC addresses, ports, keys, tokens, or
    packet details.
3.  **Minimal configuration:** technical settings remain internal.
4.  **Legitimate in-app authentication:** native or browser
    authorization where permitted and supported.
5.  **Local-first connectivity:** prefer secure local control when
    reliable and supported.
6.  **Secure credential management:** isolate secrets by vendor and
    device; protect them using Android security facilities.
7.  **Graceful cloud fallback:** use an authorized cloud route when
    local control is unavailable and the vendor permits it.
8.  **Honest compatibility:** publish only model-specific,
    evidence-backed support claims.
9.  **Modular drivers:** brand integrations must not require rewriting
    the main app.
10. **Continuous execution:** a blocker for one manufacturer must not
    stop independent work on other manufacturers or shared
    functionality.

## 1.6 Product boundaries and non-goals

-   RoomAC does not guarantee support for every model sold by a listed
    manufacturer.
-   RoomAC MUST NOT bypass authentication, access controls, encryption,
    rate limits, or vendor restrictions.
-   RoomAC MUST NOT use shared built-in vendor accounts or credentials
    belonging to third parties.
-   RoomAC MUST NOT require users to enter low-level network or
    cryptographic configuration during ordinary onboarding.
-   Infrared control is a fallback route, not the default assumption. It
    may require an IR-equipped phone or an external accessory/bridge.
-   A manufacturer's ecosystem support, Matter support, or OEM
    relationship does not establish compatibility for a particular AC
    model.
-   RoomAC MUST NOT claim remote internet control unless an authorized
    and supported route exists.

## 1.7 Measurable product-level acceptance criteria

The application is acceptable for a given verified model only when all
applicable criteria below are demonstrated:

-   The app installs, launches, and navigates without a blocking crash.
-   The user can start discovery and receive a clear result or a
    meaningful empty/error state.
-   The exact device can be selected and paired/authenticated through a
    documented flow.
-   A real device-state read succeeds.
-   Each displayed control maps to a supported command for that device.
-   At least one command is sent and its outcome is verified through a
    state read, device feedback, or another reliable acknowledgement.
-   Reconnection and failure behavior are tested.
-   Credentials and secrets are not exposed in logs, source control, or
    user-facing diagnostics.
-   The model, firmware, connection route, tested functions, test date,
    and evidence are recorded.
-   Unsupported functions are hidden or explicitly disabled with an
    explanation.

------------------------------------------------------------------------

# 2. Product scope and functional requirements

## 2.1 Device management

RoomAC MUST provide the following capabilities where the device and
driver support them:

  -----------------------------------------------------------------------
  ID                      Requirement             Acceptance evidence
  ----------------------- ----------------------- -----------------------
  DEV-001                 Add AC                  User can start the
                                                  add-device flow from
                                                  the app.

  DEV-002                 Discover AC             A bounded scan returns
                                                  candidates or a clear
                                                  empty state.

  DEV-003                 Identify device         Brand/model
                                                  identification includes
                                                  confidence/evidence
                                                  where uncertain.

  DEV-004                 Select device           User can select one
                                                  candidate without
                                                  entering technical
                                                  identifiers.

  DEV-005                 Pair/provision          Required pairing or
                                                  Wi-Fi commissioning is
                                                  guided and recoverable.

  DEV-006                 Authenticate            Required authorization
                                                  is completed through a
                                                  legitimate supported
                                                  flow.

  DEV-007                 Save device             Device identity and
                                                  non-secret metadata
                                                  persist across app
                                                  restarts.

  DEV-008                 Securely save secrets   Tokens/keys are stored
                                                  using approved secure
                                                  storage.

  DEV-009                 Rename device           User can assign a
                                                  friendly room/name
                                                  label.

  DEV-010                 Remove device           Device metadata and
                                                  associated secrets are
                                                  removed or revoked
                                                  where supported.

  DEV-011                 Reconnect               App can reconnect after
                                                  temporary disconnection
                                                  using bounded retries.

  DEV-012                 Multiple devices        User can switch among
                                                  saved ACs.

  DEV-013                 Connection status       Current status and last
                                                  successful update are
                                                  visible.

  DEV-014                 Device details          User can inspect brand,
                                                  model, supported
                                                  features, and
                                                  connection method at an
                                                  appropriate level.

  DEV-015                 Capability display      UI reflects actual
                                                  driver-reported
                                                  capabilities, not a
                                                  universal assumed
                                                  feature list.
  -----------------------------------------------------------------------

The app MUST distinguish a saved device from a currently connected
device. A saved record does not prove that the AC is online.

## 2.2 AC controls

The common domain model and UI SHOULD support the following functions,
but each is exposed only if the exact model/driver supports it:

-   Power on/off.
-   Target temperature adjustment.
-   Cool mode.
-   Heat mode.
-   Auto mode.
-   Dry mode.
-   Fan-only mode.
-   Fan speed, including auto where supported.
-   Swing.
-   Vertical louver position.
-   Horizontal louver position.
-   Current room temperature.
-   Target temperature.
-   Manufacturer-specific functions when practical and safe.

### Control rules

-   The driver MUST define valid temperature range, increment, units,
    and supported modes for each model family.
-   Do not assume every AC supports heating, horizontal swing, every fan
    speed, or room-temperature reporting.
-   Do not show an unsupported control as interactive.
-   If a control is temporarily unavailable, distinguish that from
    permanently unsupported capability.
-   Validate commands in the domain layer before sending them.
-   Avoid sending duplicate commands when an identical command is
    already pending or recently acknowledged, unless the protocol
    requires it.
-   Clearly distinguish **requested state**, **reported state**, and
    **unconfirmed/optimistic state**.
-   Do not present an optimistic UI update as a confirmed physical AC
    state.
-   If a command is accepted by a cloud API but the physical device has
    not yet reflected it, communicate the appropriate
    pending/unconfirmed state.

## 2.3 Device and connection states

Use a typed state model rather than scattered booleans. At minimum,
represent:

-   Disconnected.
-   Connecting.
-   Discovering.
-   Pairing.
-   Authenticating.
-   Connected.
-   Offline.
-   Unsupported.
-   Error.

Represent last successful update time and stale-state information
separately. Avoid contradictory combinations such as `isConnected=true`
while the connection state is `Offline`.

## 2.4 User experience requirements

The UI MUST include:

-   Simple onboarding.
-   Add-device entry point.
-   Permission rationale and denial recovery.
-   Discovery progress and cancellation.
-   Empty discovery state with useful troubleshooting.
-   Candidate/device selection.
-   Pairing/authentication progress.
-   Clear success confirmation based on actual verification.
-   Retry and recovery options.
-   Human-readable error explanations.
-   Saved-device list and active-device switching.
-   Unified control screen.
-   Offline and stale-state behavior.
-   Unsupported capability presentation.

Technical diagnostics MAY be available in a clearly separated
troubleshooting view, but MUST NOT be required for ordinary operation.

------------------------------------------------------------------------

# 3. Repository inspection and architecture requirements

## 3.1 Repository-first rule

Before changing code, inspect the actual project. Do not infer that
historical module names, Gradle configuration, or implementation status
are still current.

Known modules from project history:

-   `app`
-   `core-domain`
-   `core-protocol`
-   `driver-haismart`
-   `driver-gree`

These names are historical context. Confirm the actual settings, module
directories, package names, source sets, build variants, dependency
versions, and test structure.

## 3.2 Preservation rules

-   Do not rewrite the app from scratch without a documented, compelling
    reason.
-   Preserve existing working discovery, handshake, parsing, UI, and
    persistence behavior.
-   Do not delete existing work merely because it is unfamiliar.
-   Inspect `git status`, current branch, and recent commits before
    editing.
-   Do not overwrite user changes.
-   Do not create Git commits unless explicitly requested.
-   Prefer focused changes and tests.
-   Record pre-existing failures separately from regressions introduced
    by the agent.
-   Keep credentials, generated files, local configuration, and build
    artifacts out of source control as appropriate.

## 3.3 Target architectural responsibilities

### App module

Owns Android presentation and platform integration:

-   UI and navigation.
-   Onboarding.
-   Android permission requests.
-   Device discovery screens.
-   Device selection and pairing screens.
-   Vendor authentication screens/hand-offs.
-   Unified AC controls.
-   Device management.
-   User-facing error presentation.
-   Lifecycle-aware observation of domain state.
-   Android-specific adapters where appropriate.

The app layer SHOULD depend on domain abstractions, not directly on
vendor packet formats or vendor-specific API details.

### `core-domain`

Owns platform-independent product concepts:

-   Device identity and metadata.
-   Model and firmware descriptors where known.
-   Capabilities and capability constraints.
-   AC state.
-   Commands and command validation.
-   Driver interfaces.
-   Connection lifecycle/state.
-   Pairing and authentication result models.
-   Support evidence and compatibility metadata.
-   Typed domain errors.
-   Device repository/use-case contracts.
-   Multi-device identity and isolation rules.

Domain models SHOULD be immutable where practical. Do not leak raw
vendor JSON, packet bytes, sockets, Android UI types, or vendor SDK
types into the common domain API.

### `core-protocol`

Owns reusable transport/protocol infrastructure:

-   Discovery abstractions.
-   Local transport abstractions.
-   TCP/UDP helpers.
-   mDNS/DNS-SD or other shared discovery adapters where applicable.
-   Protocol parsing and serialization utilities.
-   Timeouts and cancellation.
-   Common retry/backoff primitives.
-   Shared network utilities.
-   Typed low-level protocol failures.
-   Test fixtures and fake transports.

Do not force genuinely different vendor protocols into one misleading
generic packet abstraction. Share infrastructure, not false protocol
assumptions.

### Brand drivers

Each manufacturer integration MUST be isolated behind a stable driver
contract. Drivers own vendor-specific:

-   Discovery interpretation.
-   Device identification.
-   Pairing and commissioning.
-   Authentication and token/session handling.
-   Transport/protocol implementation.
-   State parsing.
-   Capability mapping.
-   Command serialization and dispatch.
-   Reconnection strategy.
-   Vendor-specific error mapping.
-   Model/firmware compatibility rules.

For every driver document:

-   Supported brand and exact model families.
-   Firmware versions tested or known to matter.
-   Discovery method.
-   Connection type (local/cloud/Bluetooth/IR/other).
-   Authentication method.
-   Credential requirements.
-   Supported capabilities.
-   Unsupported capabilities.
-   Known limitations.
-   Evidence level.
-   Unit/integration/UI/hardware test status.
-   Third-party source and license obligations.

## 3.4 Suggested driver contract

Inspect existing interfaces first and evolve them rather than
duplicating them. The logical contract should cover operations
equivalent to:

1.  Determine whether a candidate is relevant to the driver.
2.  Discover candidates.
3.  Identify a candidate and report identification confidence.
4.  Pair/provision where required.
5.  Authenticate/authorize where required.
6.  Connect.
7.  Read device information.
8.  Read current AC state.
9.  Report model-specific capabilities and constraints.
10. Validate and send supported commands.
11. Verify resulting state where possible.
12. Reconnect.
13. Disconnect.
14. Remove/revoke credentials where supported.

Operations SHOULD be cancellable, bounded by explicit timeouts, and
return typed outcomes. Avoid blocking the Android main thread. Ensure
driver lifecycle operations are safe under app lifecycle changes and
repeated calls.

## 3.5 Multi-device isolation

Every persisted device MUST have a stable internal identity independent
of a transient IP address. Credentials and sessions MUST be scoped to
the relevant vendor account/device and MUST NOT be accidentally reused
across devices or brands.

A driver instance or session manager MUST avoid cross-device state
leakage. Switching active devices must not redirect pending commands to
the wrong AC.

------------------------------------------------------------------------

# 4. Connectivity methods to investigate

Do not assume all manufacturers or model generations share one protocol.
Research and select connectivity per model family, not just per brand.

For each method, record source, exact model coverage, required setup,
security properties, limitations, feasibility, and evidence.

## 4.1 Local Wi-Fi / LAN

Investigate where relevant:

-   UDP broadcast and multicast discovery.
-   Vendor-specific UDP discovery.
-   mDNS and DNS-SD.
-   SSDP where applicable.
-   TCP and UDP transports.
-   Local pairing and authorization.
-   Key exchange and encryption.
-   Device information retrieval.
-   State retrieval.
-   Command execution.
-   Acknowledgement/confirmation semantics.
-   Reconnection and device restart behavior.
-   Offline operation.
-   Router/client isolation behavior.
-   Subnet and network-change handling.

Advantages may include lower latency, reduced cloud dependency, improved
privacy, and continued operation during vendor-cloud outages. These
benefits MUST be described as conditional, not guaranteed.

Limitations include same-network requirements, router isolation,
firmware differences, local authentication, encryption, and
vendor-specific protocol changes.

The app MUST request Android local-network-related permissions
applicable to the target Android versions and discovery mechanism.
Verify permission behavior against actual target SDK/API level. Do not
assume a successful scan on one Android version proves permission
correctness on all supported versions.

## 4.2 Official cloud APIs and SDKs

Investigate:

-   Official developer programs and documentation.
-   Official Android SDKs.
-   OAuth or other supported authorization.
-   Device listing and account linking.
-   Cloud state reads and commands.
-   Region-specific endpoints.
-   Rate limits and quotas.
-   API versioning and deprecation.
-   Terms of service and redistribution restrictions.
-   Whether an API is available to ordinary third-party applications.

Prefer official documented APIs where available. Do not label an
undocumented endpoint official.

## 4.3 Vendor account authentication

Assess:

-   Native supported SDK authentication.
-   OAuth authorization-code flows and PKCE where applicable.
-   Browser-based authorization / Android Custom Tabs.
-   Redirect/deep-link handling.
-   Token acquisition, secure persistence, refresh, and revocation.
-   MFA and CAPTCHA handling.
-   Account region and endpoint selection.
-   Session expiry and logout.
-   Provider restrictions on third-party clients.

Do not imitate a vendor login screen, collect vendor passwords when an
authorization flow exists, defeat CAPTCHA/MFA, or bypass app/device
authorization. If a provider disallows third-party access, document the
limitation.

## 4.4 Bluetooth provisioning

Investigate:

-   BLE advertising and discovery.
-   Pairing/bonding requirements.
-   Wi-Fi credential provisioning.
-   Device commissioning protocol.
-   Android Bluetooth permissions by API level.
-   Permission denial and Bluetooth-disabled states.
-   Provisioning timeout and recovery.
-   Whether Bluetooth is only for initial provisioning or also for
    ongoing control.

Do not infer that Bluetooth provisioning exists because a vendor app
uses Bluetooth for some other device family.

## 4.5 Matter

For each exact AC model, investigate:

-   Whether that AC itself is Matter-capable or exposes AC control
    through a certified bridge/controller.
-   Matter commissioning requirements.
-   Android Matter ecosystem/API requirements.
-   Device cluster and exposed HVAC capabilities.
-   Certification and interoperability.
-   Supported firmware and region.
-   Whether third-party Android apps can legitimately commission/control
    it.

Do not infer AC Matter support from a manufacturer's unrelated
Matter-enabled products.

## 4.6 Open-source and community integrations

Potential sources include protocol libraries, Home Assistant
integrations, community reverse-engineering projects, and independent
implementations.

For each source, record:

-   Repository URL.
-   Exact revision, release, or commit inspected.
-   License and license text.
-   Maintenance/activity status and date checked.
-   Supported models and firmware.
-   Local/cloud requirements.
-   Authentication approach.
-   Security assumptions.
-   Code reuse feasibility.
-   Attribution, source-disclosure, notice, and distribution
    obligations.
-   Whether it is used only as a research reference or incorporated into
    RoomAC.

Treat community findings as unverified until independently validated. Do
not copy code before reviewing its license and obligations.

## 4.7 Infrared

Investigate:

-   Built-in Android IR blaster availability and API limitations.
-   External USB/Bluetooth/network IR accessories.
-   IR bridges.
-   Legitimate command databases and their licensing.
-   Model/remote-code mapping.
-   Whether the app receives any feedback from the AC.

IR often provides one-way control without reliable state feedback. The
UI MUST communicate that limitation and must not claim a command was
physically confirmed when no feedback path exists. Treat IR as a
fallback, not the default.

## 4.8 Required connectivity comparison

Create and maintain a comparison for every relevant brand/model family:

  --------------------------------------------------------------------------------------------------------------------------------------------------
  Method         Availability         Exact models  User setup        Security/authentication   Limitations                Feasibility   Evidence
  -------------- -------------------- ------------- ----------------- ------------------------- -------------------------- ------------- -----------
  Local          Unknown until        Record exact  Usually same      Document protocol and key Router/firmware/protocol   Assess        Link/test
  Wi-Fi/LAN      researched           models        network; detail   handling                  dependent                                
                                                    actual steps                                                                         

  Official cloud Unknown until        Record exact  Account           Official auth and token   API access, quotas, region Assess        Link/test
  API            researched           models        authorization     handling                                                           

  Bluetooth      Unknown until        Record exact  Pair/commission   BLE security and          May be setup-only          Assess        Link/test
  provisioning   researched           models                          credential transfer                                                

  Matter         Unknown until        Exact         Commissioning     Matter security model     Certification and feature  Assess        Link/test
                 researched           certified                                                 exposure                                 
                                      models only                                                                                        

  Community      Unknown until        Exact model   Varies            Audit implementation      License and                Assess        Link/test
  protocol       researched           claims                                                    reverse-engineering risk                 

  IR             Hardware-dependent   Remote-code   IR                Usually no account;       Feedback often absent      Fallback      Link/test
                                      coverage      hardware/bridge   evaluate accessory                                                 
  --------------------------------------------------------------------------------------------------------------------------------------------------

Do not fill unknown cells with guesses.

------------------------------------------------------------------------

# 5. Brand-by-brand investigation and implementation requirements

All 13 brands below require their own investigation record and
compatibility status. A brand name alone is not a support claim. For
each, investigate official apps, official APIs, Android SDKs, account
authorization, LAN protocols, Bluetooth provisioning, Matter, community
integrations, model generations, firmware differences, authentication,
control capabilities, hardware verification, licensing, recommended
route, fallback, limitations, and remaining research.

## 5.1 Haier

Existing context:

-   Test product: **Haier T3 Plus Inverter**.
-   Model: `HSU-20HFTEX/013WUSDC(OW)-T3`.
-   Product code: `AACGVXE00`.
-   Device ID: `540853BD1D41`.
-   Previously observed local TCP port: `56800`.
-   Previously observed AC address: `192.168.18.68`.
-   Previously observed computer address: `192.168.18.139/24`.
-   Previously observed gateway: `192.168.18.1`.
-   Local discovery previously worked.
-   Local handshake previously succeeded.
-   AC reported key version `58`.

These IP addresses are historical observations only. They MUST NOT be
hardcoded or treated as stable configuration.

### Required Haier work

-   Preserve and regression-test working local discovery.
-   Preserve and regression-test the successful local handshake.
-   Inspect the existing `driver-haismart` implementation and its actual
    Gradle/configuration path.
-   Investigate HaiSmart account sign-in rejection as specified in
    Section 6.
-   Determine whether account authorization is required for this exact
    device and route.
-   Investigate device listing, device identity mapping, key retrieval,
    status read, authorized command execution, reconnection, and
    credential removal.
-   Validate model-specific supported capabilities.
-   Test the exact physical model where available.
-   Consider re-pairing or secret rotation because a real device key was
    exposed during an earlier debugging conversation. Treat that key as
    compromised. Never reproduce it in code, documentation, logs,
    fixtures, or reports. Rotate/re-pair where supported.
-   Do not alter working local discovery merely to experiment with
    unrelated cloud sign-in.

The community project `https://github.com/enapt/haismart-local` MAY be
inspected as an unofficial Home Assistant/community reference. Inspect
its current source and license before reuse. It is not an official Haier
API.

### Haier acceptance evidence

Record separately whether discovery, handshake, authentication, device
listing, key retrieval, state read, command execution, and reconnection
have passed. A handshake alone is not a completed integration.

## 5.2 Gree

Existing context:

-   A Gree driver has been started.
-   No physical Gree AC has been available for verification.
-   Previous research referenced GPL-3.0 code.

Required work:

-   Inspect the existing driver and identify its source lineage.
-   Locate and review the exact upstream repository, revision, and
    license.
-   Do not silently incorporate GPL-3.0 or other reciprocal-license code
    into a proprietary release.
-   Complete all work possible without hardware: parsers, serializers,
    command mapping, protocol fixtures, driver registration, fake
    transport tests, error handling, and simulated integration tests.
-   Document exact supported model claims and firmware uncertainty.
-   Keep hardware verification pending until a physical unit is tested.
-   If code reuse is not legally suitable, isolate the reference and
    evaluate a clean independent implementation based on legitimately
    available protocol information.

## 5.3 Midea

Investigate protocol generations separately. Historical research
suggests some V2 devices and V3 devices may use different local
authentication requirements; some V3 devices may require the user's own
NetHome Plus account token or key. These are research leads, not
universal rules.

Required work:

-   Identify exact model and protocol generation.
-   Investigate local discovery, authentication, encryption, state and
    command formats.
-   Investigate NetHome Plus account authorization and token/key
    acquisition where legitimately supported.
-   Determine whether cloud access is official/documented or
    community-derived.
-   Never use shared built-in accounts or hardcoded third-party
    credentials.
-   Record model-specific capability and firmware coverage.

## 5.4 Daikin

Investigate official Daikin applications, official APIs/SDKs, account
and region requirements, local LAN interfaces, discovery,
authentication, model generations, firmware, community integrations, and
supported HVAC functions. Distinguish documented official interfaces
from undocumented or community-derived methods. Verify exact model
families before implementation claims.

## 5.5 LG

Investigate LG ThinQ-related official authorization and developer
options, supported cloud routes, local-control availability,
region/model coverage, account linking, token lifecycle, and relevant
community integrations. Do not assume ThinQ access is available to
arbitrary third-party clients. Record restrictions and exact model
coverage.

## 5.6 Samsung

Investigate SmartThings and any officially supported device-control
routes, including supported HVAC capabilities, account authorization,
device discovery, cloud dependency, region, API terms, and rate limits.
Do not assume every Samsung AC exposes all controls through SmartThings.
Record the exact capabilities returned for each tested model.

## 5.7 Dawlance

Investigate exact Dawlance AC models, their official apps/services,
connectivity hardware, OEM possibilities, account requirements, and
independent protocol evidence. An OEM relationship or similar-looking
app does not prove protocol compatibility. Verify each candidate model
family independently.

## 5.8 Orient

Investigate exact Orient model families, official connectivity options,
app/account requirements, LAN discovery, local protocol evidence, cloud
services, Bluetooth commissioning, firmware differences, and community
references. Avoid extrapolating from another manufacturer's product.

## 5.9 PEL

Investigate exact PEL models, official applications and services,
available network hardware, local/cloud control, commissioning, protocol
evidence, and feasible fallbacks. Document whether Wi-Fi is built-in,
optional, or absent for each investigated model.

## 5.10 AUX

Investigate AUX local protocols, official services, model/firmware
generations, account authentication, local discovery, community
implementations, and licensing. Validate protocol similarity claims with
evidence before sharing a driver with another brand.

## 5.11 TCL

Investigate TCL AC model generations, official connectivity options,
account and regional requirements, local/cloud protocols, Bluetooth
provisioning, community integrations, and exposed controls. Do not
assume that all TCL devices use the same smart-home platform.

## 5.12 Panasonic

Investigate official Panasonic APIs/SDKs, supported ecosystems, model
generations, local network methods, account requirements, regional
variations, commissioning, and supported HVAC capabilities. Record
official documentation and applicable terms.

## 5.13 Kenwood

Investigate exact Kenwood AC models, official apps/services, model
generations, connectivity hardware, potential OEM relationships, and
independent protocol evidence. Do not assume shared protocol
compatibility from an OEM relationship, app resemblance, or product
similarity.

## 5.14 Brand research record template

Maintain one record per brand and, where necessary, per model family:

  Field                   Required entry
  ----------------------- -----------------------------------------------
  Brand                   Manufacturer name
  Model family            Exact family/model identifiers
  Firmware/region         Known firmware and sales region
  Official app            Name, store/documentation link, observed role
  Official API/SDK        Link, access conditions, license/terms
  Discovery               Method and evidence
  Pairing/provisioning    User steps and technical flow
  Authentication          OAuth/account/local key/other; source
  Connectivity            LAN/cloud/BLE/Matter/IR
  Capabilities            Verified feature list
  Community references    Repository, revision, license
  Recommended route       Evidence-backed choice
  Fallback route          Evidence-backed alternative
  Hardware verification   Exact test details or pending
  Limitations             Model/firmware/network/account constraints
  Status                  One of the defined statuses
  Next action             Concrete next engineering task

### Required brand coverage matrix

All brands must remain visible in the project compatibility matrix even
when no model is currently supported:

  ---------------------------------------------------------------------------
  Brand             Research status   Implementation status Hardware status
  ----------------- ----------------- --------------------- -----------------
  Haier             Track exact model Track local/cloud     T3 Plus test
                    and HaiSmart      pieces separately     device
                    investigation                           historically
                                                            available;
                                                            re-verify

  Gree              Track protocol    Existing driver to    No physical
                    and GPL review    inspect/complete      device available
                                                            historically

  Midea             Separate protocol Implement feasible    Pending exact
                    generations       generation-specific   device
                                      routes                

  Daikin            Model-specific    Pending evidence      Pending
                    investigation                           

  LG                ThinQ and         Pending evidence      Pending
                    local/cloud                             
                    investigation                           

  Samsung           SmartThings and   Pending evidence      Pending
                    supported HVAC                          
                    controls                                

  Dawlance          Exact models/OEM  Pending evidence      Pending
                    investigation                           

  Orient            Exact models and  Pending evidence      Pending
                    connectivity                            

  PEL               Exact models and  Pending evidence      Pending
                    connectivity                            

  AUX               Protocol and      Pending evidence      Pending
                    official service                        
                    investigation                           

  TCL               Generation and    Pending evidence      Pending
                    official options                        

  Panasonic         Official          Pending evidence      Pending
                    ecosystem and                           
                    local options                           

  Kenwood           Exact models/OEM  Pending evidence      Pending
                    evidence                                
  ---------------------------------------------------------------------------

------------------------------------------------------------------------

# 6. Dedicated HaiSmart authentication investigation

## 6.1 Known failure

The previous account smoke test returned:

``` text
haier account: sign-in failed (REJECTED)
sign-in: The HaiSmart account did not accept the sign-in.
```

The user reported that the same email and password successfully
authenticate in the official HaiSmart mobile application.

Do NOT immediately conclude that the password is wrong. Do NOT
repeatedly ask the user to re-enter the same credentials. Do NOT repeat
the same failed request without changing or validating a hypothesis.

## 6.2 Investigation checklist

Inspect and verify each item against the actual implementation and
available evidence:

-   [ ] App ID configuration.
-   [ ] App key configuration and provenance.
-   [ ] Gradle property loading.
-   [ ] BuildConfig generation, if used.
-   [ ] Environment variable propagation.
-   [ ] `local.properties` handling and Git exclusion.
-   [ ] API endpoint and endpoint version.
-   [ ] API region and account-region mapping.
-   [ ] Request headers.
-   [ ] User-Agent/client identity.
-   [ ] Request body and field names/types.
-   [ ] Password encoding and transformation.
-   [ ] Timestamp units, timezone, and clock skew.
-   [ ] Signature algorithm and canonicalization.
-   [ ] Nonce generation and uniqueness.
-   [ ] Encryption and key derivation.
-   [ ] Response format and parsing.
-   [ ] Error-code interpretation.
-   [ ] Account region and account state.
-   [ ] App identity/client authorization requirements.
-   [ ] API version or protocol changes.
-   [ ] Session creation, persistence, refresh, and expiry.
-   [ ] Whether the route is actually supported for third-party clients.

Never log or publish the password, app key, session token, device key,
or complete sensitive request/response payload. Use redacted structured
diagnostics.

## 6.3 Diagnostic method

For every attempt, record:

  -----------------------------------------------------------------------
  Field                               Description
  ----------------------------------- -----------------------------------
  Attempt ID                          Sequential identifier

  Hypothesis                          One testable suspected cause

  Evidence inspected                  Exact code, configuration,
                                      documentation, or source

  Change                              Exact change, or "none"

  Test                                Reproducible command/flow

  Result                              Exact sanitized result

  Interpretation                      Confirmed, weakened, or unresolved
                                      hypothesis

  Next step                           New test that adds information
  -----------------------------------------------------------------------

Do not change multiple unrelated variables in one attempt if that
prevents causal interpretation. Avoid account lockout by respecting
vendor behavior and using a limited, deliberate test sequence.

## 6.4 Required attempt order

1.  Reproduce the failure once using the current implementation and
    capture sanitized diagnostics.
2.  Inspect configuration propagation from local/Gradle properties
    through runtime request construction.
3.  Validate endpoint, region, required headers, request shape, time,
    and signing/encryption logic against legitimate references.
4.  Inspect the unofficial `haismart-local` project as a research
    source, including its current license and implementation
    assumptions.
5.  Determine whether the official app uses a supported third-party
    authorization mechanism or whether the observed route is
    private/unsupported.
6.  Build isolated tests for deterministic request/signature/response
    components.
7.  If a legitimate alternate route exists, prototype it without
    damaging the working local discovery path.
8.  If the service rejects third-party access or required proprietary
    information is unavailable, document the evidence and classify only
    that route as blocked.

## 6.5 Isolation requirement

The local discovery and successful handshake are separate known
capabilities and MUST be protected by regression tests. Authentication
investigation MUST NOT unnecessarily modify their behavior.

If cloud sign-in remains blocked, continue device-independent work,
Gree, Midea, remaining brand research, shared architecture, UI, tests,
documentation, and security work.

------------------------------------------------------------------------

# 7. Authentication, credentials, and secret management

## 7.1 Storage

-   Use Android Keystore-backed protection for encryption keys where
    appropriate.
-   Store sensitive values using an encrypted persistence mechanism
    whose encryption key is protected by Android Keystore.
-   Evaluate backup behavior: vendor tokens, local device keys, and
    other secrets MUST NOT be copied to an insecure backup.
-   Keep non-secret device metadata separate from secrets.
-   Scope secrets by vendor account and device identity.
-   Support deletion and revocation where the vendor provides it.

Do not invent a cryptographic storage design casually. Use maintained
Android security primitives and document threat assumptions.

## 7.2 Credential handling

-   Never hardcode credentials, tokens, private app keys, or device
    encryption keys.
-   Never commit secrets to Git.
-   Do not put secrets in source files, test fixtures, screenshots,
    crash reports, analytics, or debug logs.
-   Do not pass secrets through intents, URLs, clipboard, or unprotected
    inter-process interfaces.
-   Do not expose secrets in exception messages.
-   Redact authorization headers, cookies, passwords, keys, and
    token-like values from diagnostics.
-   Avoid storing a vendor password if a token-based authorization flow
    is available.
-   Never use a shared account belonging to the developer, vendor, or
    another user.
-   Provide a clear logout/forget-device action and remove locally
    stored credentials when appropriate.
-   Handle token expiry and refresh without infinite retry loops.
-   If refresh fails, transition to an actionable reauthorization state.

## 7.3 Developer configuration

-   Document required local configuration keys by name and purpose,
    never by real value.
-   Use local-only configuration files or environment/CI secret storage.
-   Ensure local secret files are Git-ignored.
-   Validate missing configuration at startup or before the relevant
    operation and show a safe actionable error.
-   Do not silently substitute placeholder credentials in production.
-   Provide fake/test credentials only in isolated tests and ensure they
    cannot reach production builds.

## 7.4 Security audit

Inspect existing source for:

-   Plaintext preferences/databases containing secrets.
-   Hardcoded keys or credentials.
-   Secret-bearing logs.
-   Insecure HTTP.
-   Certificate validation bypasses.
-   Weak random number generation.
-   Unsafe cryptographic primitives.
-   Excessive Android permissions.
-   Exported components and intent handling.
-   Backup leakage.
-   Unvalidated network responses.
-   Unbounded discovery and network requests.
-   Insecure deep links/redirect validation.
-   Cross-device or cross-account session leakage.

Fix safely and add regression tests.

------------------------------------------------------------------------

# 8. Discovery and pairing

## 8.1 Discovery lifecycle

A user-initiated discovery operation SHOULD follow this sequence:

1.  Check required Android permissions and relevant radio/network state.
2.  Explain and request missing permissions in context.
3.  Confirm the phone is connected to an appropriate network where
    required.
4.  Start only the discovery mechanisms applicable to installed drivers.
5.  Use bounded scan duration, cancellation, and resource cleanup.
6.  Parse candidate responses defensively.
7.  Deduplicate candidates using stable identifiers where available.
8.  Ask drivers to identify candidates and return confidence/evidence.
9.  Present clear device labels without exposing raw protocol fields.
10. Let the user select the intended AC.
11. Run pairing/authentication as required.
12. Verify a real connection and supported state read.
13. Save the device and secrets securely.
14. Show the control screen only after the required connection criteria
    pass.

## 8.2 Permission and network behavior

-   Request only permissions required by the current Android version and
    feature.
-   Explain why nearby-device, location, Bluetooth, or network
    permissions are required where applicable.
-   Handle denial, permanent denial, disabled Wi-Fi/Bluetooth, and
    network changes.
-   Do not continuously scan in the background without a justified
    product requirement and compliant Android behavior.
-   Do not assume a phone and AC can communicate when client isolation,
    guest Wi-Fi, VLANs, or firewall rules prevent it.

## 8.3 Pairing and recovery

Pairing MUST have explicit progress states and bounded timeouts. On
failure:

-   Preserve user-entered information only when safe and useful.
-   Explain the likely cause without claiming certainty.
-   Offer a retry only when retry is meaningful.
-   Avoid duplicate device records.
-   Clean up partially created sessions/secrets.
-   Permit the user to cancel.
-   Do not leave sockets, BLE scans, or coroutines running after
    cancellation.
-   Record sanitized diagnostics for support.

------------------------------------------------------------------------

# 9. Unified control experience

## 9.1 Screen requirements

The main screen SHOULD present:

-   Device name and brand/model where known.
-   Connection state and last update.
-   Power control.
-   Current and target temperature where supported.
-   Temperature adjustment.
-   Mode selector for supported modes.
-   Fan speed and auto fan where supported.
-   Swing/louver controls where supported.
-   Loading/pending/acknowledged state.
-   Offline or stale-data indicator.
-   A path to device details and supported capabilities.

## 9.2 State correctness

Maintain separate representations for:

-   Last reported device state.
-   Pending user command.
-   Command result/acknowledgement.
-   Current connection state.
-   State freshness.

On command timeout, do not silently mark the command successful. Refresh
state when appropriate and report whether the result is confirmed,
unknown, or rejected.

## 9.3 Capability-aware rendering

The UI MUST derive visible controls from the driver's capability model.
The capability model should include constraints such as:

-   Temperature minimum/maximum/step/unit.
-   Supported operating modes.
-   Supported fan speeds.
-   Auto fan availability.
-   Swing axes and positions.
-   Readable sensor values.
-   Whether state feedback is available.
-   Whether the feature is read-only or writable.

Unknown capability is not equivalent to supported capability.

------------------------------------------------------------------------

# 10. Multiple-device support

-   Support multiple saved ACs.
-   Use stable internal IDs independent of dynamic IP addresses.
-   Store independent device metadata and secrets.
-   Keep vendor account sessions appropriately scoped.
-   Permit rename, remove, and active-device switching.
-   Maintain independent reconnection and state refresh per device.
-   Prevent commands from being dispatched to the wrong active device.
-   Prevent one device's failed session from corrupting another device's
    state.
-   Remove associated credentials when a device is forgotten, subject to
    vendor revocation semantics.
-   Test multiple devices of the same brand and devices from different
    brands.

------------------------------------------------------------------------

# 11. Reliability and error handling

Use typed errors at driver/protocol/domain boundaries. At minimum,
distinguish:

  -----------------------------------------------------------------------
  Failure                             Expected handling
  ----------------------------------- -----------------------------------
  Wi-Fi disabled                      Explain that Wi-Fi is required for
                                      this route and provide settings
                                      guidance.

  Permission denied                   Explain the missing permission and
                                      offer a safe retry/settings route.

  Wrong network                       Explain same-network requirement
                                      where applicable.

  Device not discovered               End scan cleanly; provide
                                      non-technical troubleshooting.

  Device offline                      Preserve saved device; show offline
                                      state and retry policy.

  Cloud unavailable                   Prefer local route if supported;
                                      otherwise show service-unavailable
                                      state.

  DNS failure                         Retry only within bounded policy;
                                      preserve diagnostics.

  Timeout                             Distinguish timeout from
                                      authentication rejection or
                                      unsupported device.

  Authentication rejected             Avoid repeated password prompts;
                                      provide reauthorization or support
                                      guidance.

  Invalid account                     Explain
                                      account/region/authorization issue
                                      without exposing details.

  Expired token                       Attempt one controlled refresh;
                                      reauthorize if necessary.

  Unsupported model                   State that this model is not
                                      currently supported; do not imply
                                      control is available.

  Unsupported command                 Reject before transport where
                                      capability metadata says
                                      unsupported.

  Malformed response                  Fail safely, retain last known
                                      state as stale, and record redacted
                                      diagnostics.

  Protocol mismatch                   Report unsupported/incompatible
                                      firmware or protocol evidence.

  Pairing failure                     Clean up partial state and provide
                                      recoverable instructions.

  AC restart                          Detect connection loss and
                                      reconnect with backoff.

  App restart                         Restore saved devices and reconnect
                                      safely.

  Network switch                      Re-evaluate route and reconnect
                                      without assuming old IP addresses.

  Duplicate device                    Merge or suppress duplicate
                                      candidates using stable identity
                                      evidence.

  Rate limiting                       Respect retry-after/provider
                                      policy; avoid request storms.
  -----------------------------------------------------------------------

Use bounded exponential backoff with jitter where suitable. Do not retry
permanent errors indefinitely. Cancellation MUST propagate through
discovery, pairing, transport, and retry operations.

------------------------------------------------------------------------

# 12. Testing strategy

Testing results MUST distinguish unit, mock, integration, UI, protocol,
and physical hardware evidence.

## 12.1 Unit tests

Cover:

-   Domain models and invariants.
-   Capability mapping and constraints.
-   Discovery response parsing.
-   Candidate deduplication.
-   Protocol serialization.
-   Protocol parsing.
-   Authentication request construction where lawful/testable.
-   Authentication response parsing.
-   Error mapping.
-   Command validation and serialization.
-   Device state transitions.
-   Secret redaction.
-   Token expiry/refresh logic.
-   Reconnection/backoff behavior.
-   Multi-device isolation.
-   Persistence mapping.

Tests MUST include malformed, truncated, unexpected, and adversarial
inputs where relevant.

## 12.2 Protocol tests

Use captured fixtures only when they are legally and safely obtained and
fully scrubbed of secrets and personal information. Include:

-   Valid packet/response cases.
-   Invalid checksums/signatures where applicable.
-   Unknown fields and protocol versions.
-   Partial responses.
-   Timeouts.
-   Duplicate messages.
-   Out-of-order responses.
-   Device restart.
-   Unsupported command.
-   State reconciliation.

Do not put real device keys or user tokens in fixtures.

## 12.3 Integration tests

Cover:

-   Driver registration and selection.
-   Discovery through fake/local test transport.
-   Pairing flow.
-   Authentication flow.
-   Device listing.
-   Key retrieval where applicable.
-   State read.
-   Command dispatch.
-   Command acknowledgement and state verification.
-   Session recovery.
-   Persistence across process/app restart.
-   Credential deletion.
-   Multi-device separation.

## 12.4 UI tests

Cover:

-   Onboarding.
-   Permission granted/denied/permanently denied.
-   Wi-Fi/Bluetooth disabled states.
-   Discovery progress and cancellation.
-   Empty discovery state.
-   Device selection.
-   Pairing progress and failure.
-   Authentication rejection.
-   Connected state.
-   Offline and stale state.
-   Unsupported capabilities.
-   Control interactions.
-   Device switching.
-   Device rename and removal.
-   Retry and recovery.

## 12.5 Hardware tests

For every physical test, record:

-   Brand.
-   Exact model and product code if available.
-   Firmware/version if available.
-   Region.
-   Phone model and Android version.
-   RoomAC build/version/commit.
-   Network topology at a non-sensitive level.
-   Connection route.
-   Test date.
-   Operations attempted.
-   Expected result.
-   Actual result.
-   Sanitized logs/evidence.
-   Whether the device physically responded.
-   Remaining limitations.

Do not publish private IP addresses, account details, device keys, or
tokens in public reports.

### Evidence ladder

  -----------------------------------------------------------------------
  Evidence level          What it proves          What it does not prove
  ----------------------- ----------------------- -----------------------
  Unit test               Isolated logic behaves  Network/device
                          as expected             compatibility

  Mock test               Components interact     Real protocol/device
                          with a simulated peer   behavior

  Protocol fixture test   Parser/serializer       Current device firmware
                          handles recorded        compatibility
                          fixture                 

  Discovery test          Candidate is found      Authentication or
                                                  control

  Handshake test          A protocol              State read or command
                          session/handshake       execution
                          succeeds                

  State-read test         Device state was        Commands work
                          retrieved               

  Command-dispatch test   Command was             Physical AC changed
                          sent/accepted at a      state
                          protocol layer          

  Physical response       AC visibly/reliably     All models/features
  verification            changed as requested    work

  Model hardware          Listed functions work   Every model under the
  verification            on the exact tested     brand works
                          model/firmware          
  -----------------------------------------------------------------------

Never collapse these evidence levels into a single "tested" label.

## 12.6 Test execution and reporting

-   Inspect existing Gradle tasks and project conventions before
    inventing commands.
-   Run relevant unit tests, integration tests, lint, static analysis,
    and builds.
-   Record exact commands, exit codes, counts, and skipped reasons.
-   Compare results with Phase 0 baseline.
-   Separate existing failures from new regressions.
-   Do not claim a test passed if it was not run or its result was
    ambiguous.

------------------------------------------------------------------------

# 13. Security and privacy

Security requirements apply to all connection routes.

-   Minimize collection and retention of personal data.
-   Do not create an unnecessary RoomAC backend.
-   Use HTTPS and standard certificate validation for cloud
    communication.
-   Do not disable TLS validation.
-   Validate redirects and deep links used for authentication.
-   Protect tokens, passwords, app keys, and local device keys.
-   Redact secrets in all logs and crash diagnostics.
-   Use least-privilege Android permissions.
-   Bound discovery and network activity.
-   Validate all network responses before parsing/acting.
-   Prevent unauthorized device control.
-   Keep device sessions isolated.
-   Provide user-controlled logout and device removal.
-   Review backup/export behavior.
-   Avoid exposing device identifiers or network details unnecessarily.
-   Document threat assumptions and residual risks.

A security issue that could expose credentials or enable unauthorized
control is a release blocker until mitigated or explicitly assessed and
accepted by an authorized decision-maker.

------------------------------------------------------------------------

# 14. Licensing and legal review

Before incorporating any external code, protocol library, command
database, or community integration:

1.  Identify the exact source and revision.
2.  Inspect the actual license file and relevant notices.
3.  Determine whether the license applies to the code/data being reused.
4.  Record attribution and notice obligations.
5.  Assess source-disclosure, copyleft, patent, trademark, and
    distribution obligations where applicable.
6.  Determine whether the intended proprietary distribution model is
    compatible.
7.  Obtain legal review where uncertainty is material.

Review at least relevant categories such as GPL, LGPL, MIT, Apache-2.0,
and other licenses encountered.

Do not silently introduce GPL-3.0 or other reciprocal-license code into
a proprietary release. Do not assume that using a library as a reference
is the same as copying its code. Keep research notes separate from
implementation. If reuse is uncertain, isolate the source and continue
with independent work or a legally suitable alternative.

Maintain `docs/LICENCES.md` and `THIRD_PARTY_NOTICES` with:

  Field                 Required information
  --------------------- ------------------------------------------------------
  Component/source      Name and purpose
  Repository            Canonical URL
  Revision              Version/tag/commit
  License               Exact license identifier and file
  Usage                 Research-only, dependency, copied/adapted code, data
  Obligations           Notices, attribution, source offer/disclosure, etc.
  Distribution impact   Assessment for intended release
  Decision              Approved, rejected, or pending review

------------------------------------------------------------------------

# 15. Performance and resource management

-   Discovery MUST be user-initiated or otherwise explicitly justified.
-   Use bounded scan windows and cancellation.
-   Avoid continuous polling when event-driven updates or longer
    intervals are suitable.
-   Avoid duplicate requests and concurrent command conflicts.
-   Use backoff for transient network/service failures.
-   Avoid retry storms and respect rate limits.
-   Release sockets, BLE scans, callbacks, and coroutine jobs when no
    longer needed.
-   Do not perform network operations on the main thread.
-   Use lifecycle-aware collection and background execution consistent
    with Android restrictions.
-   Refresh state at a frequency appropriate to the protocol and user
    experience.
-   Mark stale data rather than silently displaying old state as
    current.
-   Measure battery and network behavior on representative devices.
-   Keep memory and parser behavior bounded for malformed/large
    responses.

Document per-driver polling and reconnection choices, including why they
are appropriate.

------------------------------------------------------------------------

# 16. Documentation requirements

Create or update these files where applicable:

  -----------------------------------------------------------------------
  File                                Required content
  ----------------------------------- -----------------------------------
  `README.md`                         Product overview, setup, build/test
                                      instructions, supported models and
                                      limitations

  `requirement.md`                    This authoritative specification,
                                      updated only when requirements
                                      legitimately change

  `docs/ARCHITECTURE.md`              Actual modules, dependency
                                      direction, driver lifecycle, data
                                      flow

  `docs/PROGRESS.md`                  Requirement-by-requirement live
                                      status and next actions

  `docs/HARDWARE_TESTS.md`            Exact hardware test matrix and
                                      evidence

  `docs/SECURITY.md`                  Threat assumptions, secret
                                      handling, permission/security audit

  `docs/LICENCES.md`                  Third-party source and license
                                      assessment

  `THIRD_PARTY_NOTICES`               Required notices and attributions
  -----------------------------------------------------------------------

Documentation MUST describe actual code and verified behavior. Do not
document planned behavior as implemented. Update progress after each
meaningful implementation batch.

------------------------------------------------------------------------

# 17. Build and release requirements

Inspect the existing Android Gradle project and follow its established
build conventions.

The agent MUST assess:

-   Debug build.
-   Release build when signing/configuration permits.
-   Signing configuration and secret handling.
-   Dependency resolution and version compatibility.
-   Unit/integration test tasks.
-   Lint and static analysis.
-   Manifest and permission configuration.
-   Minimum/target SDK implications.
-   APK/AAB output path and artifact identity.
-   Version name/code.
-   Third-party notices.
-   Release readiness.

Never commit signing keys or passwords. Do not use a debug signing
configuration as evidence of production release readiness. If release
signing credentials are unavailable, complete the build/configuration
checks that do not require them and report the precise limitation.

A successful build does not prove AC compatibility. Do not call the app
production-ready while material security, licensing, or required
hardware verification remains incomplete.

------------------------------------------------------------------------

# 18. MANDATORY AUTONOMOUS EXECUTION RULES --- DO NOT STOP EARLY

This section is non-negotiable. Previous coding-agent runs stopped
during Phase 0 or Phase 1. The purpose of this section is to ensure that
the agent executes the work rather than merely describing it.

## 18.1 Execute, do not merely plan

The coding agent MUST:

-   Inspect the repository.
-   Establish a baseline.
-   Create a prioritized implementation plan.
-   Start implementing available functionality.
-   Run tests.
-   Diagnose and fix issues.
-   Continue through all phases.
-   Update documentation and progress.
-   Produce the required final report.

A plan is not the deliverable. A repository audit is not the
deliverable. An architecture review is not the deliverable. A roadmap is
not the deliverable. Writing documentation is not a substitute for
implementing available functionality.

## 18.2 Never stop after Phase 0 or Phase 1

The agent MUST NOT stop merely because it has completed:

-   Repository inspection.
-   Project analysis.
-   Architecture review.
-   Requirements review.
-   Task breakdown.
-   Documentation creation.
-   Initial scaffolding.

After Phase 0, continue to Phase 1. After Phase 1, continue to Phase 2.
Continue through every phase until each requirement is:

-   Completed and verified at the appropriate level;
-   Verified as already complete; or
-   Attempted through reasonable applicable approaches and documented as
    blocked/not supported, with evidence.

Do not present the end of an audit or plan as project completion.

## 18.3 One blocker must never stop unrelated work

Examples:

-   If Haier authentication fails, continue with Gree.
-   If Gree hardware is unavailable, finish all software tests and
    continue with Midea.
-   If Midea requires unavailable credentials, continue with Daikin.
-   If an official API is unavailable, investigate legitimate local
    control.
-   If local control is unavailable, investigate other legitimate
    approaches.
-   If one brand is blocked, continue to the next brand.
-   If hardware is unavailable, finish all work that does not require
    hardware.
-   If a third-party license prevents code reuse, investigate an
    independent implementation or another suitable source.
-   If a dependency cannot be installed, evaluate a suitable alternative
    and continue independent tasks.
-   If a build fails, diagnose it, attempt a safe fix, and continue
    independent research/tests where possible.

**One blocker MUST NOT become a reason to abandon the rest of RoomAC.**

## 18.4 Exhaust reasonable applicable approaches before declaring blocked

For each difficult requirement, consider and document the following
sequence where applicable:

**Attempt A --- Existing code:** inspect the implementation, reproduce
the issue, and identify the exact failing layer.

**Attempt B --- Configuration:** check Gradle properties, environment
propagation, dependencies, Android permissions, network conditions, and
runtime configuration.

**Attempt C --- Official sources:** inspect vendor documentation, SDKs,
public APIs, official apps, supported authorization, and terms.

**Attempt D --- Alternative connectivity:** investigate applicable LAN,
cloud, Bluetooth, Matter, IR, or supported bridge routes.

**Attempt E --- Community research:** inspect reputable integrations,
libraries, protocol references, maintenance, and licenses.

**Attempt F --- Isolated diagnostic:** build a small test/prototype to
validate a hypothesis without destabilizing working code.

**Attempt G --- Hardware validation:** test physical hardware where
available. If unavailable, finish all possible software validation and
document the remaining hardware requirement.

**Attempt H --- Reassessment:** review evidence and decide whether
another technically, legally, and securely reasonable route remains.

Not every attempt is relevant to every task. Record "not applicable"
with a reason instead of pretending to have performed it. Never invent
attempts or results. Only classify a task as blocked after applicable
reasonable routes have been considered.

## 18.5 Live progress tracker

Maintain `docs/PROGRESS.md` or an equivalent live tracker. For every
requirement, record:

-   Requirement ID.
-   Description.
-   Status.
-   Implementation file paths.
-   Tests.
-   Exact test results.
-   Evidence/source.
-   Remaining work.
-   Blocker, if any.
-   Next action.

Update it after each meaningful implementation batch. Do not leave it
unchanged while substantial work is performed.

Suggested table:

  ----------------------------------------------------------------------------------------
  ID       Requirement   Status   Files    Tests/evidence   Remaining   Blocker   Next
                                                            work                  action
  -------- ------------- -------- -------- ---------------- ----------- --------- --------

  ----------------------------------------------------------------------------------------

## 18.6 Do not ask for information already supplied

Use this document and repository context. Ask only for information
genuinely unavailable and necessary to proceed.

If physical user action is required, finish independent work first. Then
provide one precise, safe instruction describing exactly what the user
must do and what evidence to return.

Do not ask users to supply technical values that the app is required to
discover automatically.

## 18.7 Do not wait for approval between ordinary phases

The user has authorized ordinary engineering work described in this
specification. Do not ask:

-   "Should I continue to the next phase?"
-   "Should I investigate another brand?"
-   "Should I run tests?"
-   "Should I update documentation?"
-   "Should I try another approach?"

These are assigned tasks. Ask only when a genuinely consequential user
decision, external authorization, account consent, hardware access, or
legally required approval is necessary.

Never bypass user consent for vendor account linking, physical device
control, purchases, or other consequential external actions.

## 18.8 Phase checkpoints

At the end of each phase:

1.  Update progress.
2.  Record exact tests and results.
3.  Record failures and what was learned.
4.  Identify independent next tasks.
5.  Continue immediately.

A phase boundary is not the end of the work session.

If execution time or context is limited, prioritize implementation and
preserve a precise continuation checklist. Leave the repository safe and
state the next unfinished task, exact files, commands, and known risks.

## 18.9 Accurate completion classification

Use the status definitions in this document. Specifically:

-   Implemented is not the same as hardware verified.
-   A successful build does not prove an AC integration works.
-   A successful handshake does not prove commands work.
-   A mock test does not prove a physical device works.
-   A documentation page does not prove functionality exists.
-   A cloud API response does not necessarily prove the physical AC
    changed state.
-   A brand-level research result does not prove all models of that
    brand are supported.

## 18.10 Protect user work

Before editing, inspect Git status and preserve user modifications. Do
not reset, clean, force checkout, delete, or overwrite user work to make
a build pass. Do not create commits unless explicitly requested. If a
destructive migration or irreversible action is necessary, stop before
that action and obtain authorization.

------------------------------------------------------------------------

# 19. Required development phases

Execute all phases in order where dependencies require it, but continue
independent tasks in parallel when safe. Phase completion requires
evidence, not merely a written summary.

## Phase 0 --- Repository audit and baseline

Inspect:

-   Repository root and directory tree.
-   Git status, branch, and recent commits.
-   Gradle settings/build files and module definitions.
-   Kotlin/Android plugin and dependency versions.
-   Manifest and permissions.
-   Existing source and package structure.
-   Existing tests and fixtures.
-   Local configuration and secret handling.
-   Existing documentation.
-   Third-party dependencies and licenses.
-   Security-sensitive code.
-   Existing build outputs where relevant.

Run baseline:

-   Existing unit tests.
-   Existing lint/static analysis.
-   Debug build.
-   Existing integration tests where feasible.

Record exact commands, outcomes, and pre-existing failures. Do not
modify user changes during the audit. **Then continue to Phase 1.**

## Phase 1 --- Architecture and requirements gap analysis

Map actual modules and dependency direction. Identify:

-   Complete functionality.
-   Incomplete functionality.
-   Missing functionality.
-   Existing reusable implementations.
-   Driver registration and lifecycle.
-   Test coverage gaps.
-   Security risks.
-   Licensing risks.
-   Vendor dependencies and hardware needs.

Create requirement IDs and prioritized tasks. Identify parallelizable
work. **Then continue without waiting for approval.**

## Phase 2 --- Core architecture

Implement or improve as needed:

-   Domain models.
-   Driver interfaces.
-   Device capabilities and constraints.
-   Connection/pairing/authentication states.
-   Typed errors.
-   Driver registration.
-   Shared discovery/transport abstractions.
-   Multi-device identity and isolation.
-   Persistence contracts.

Preserve working architecture. Add unit tests and integration-level
contract tests.

## Phase 3 --- Haier integration

Investigate and implement/test:

-   Existing local discovery.
-   Existing local handshake.
-   HaiSmart authentication.
-   Device listing.
-   Key retrieval.
-   State read.
-   Authorized command execution.
-   Reconnection.
-   Secure storage and secret rotation/re-pairing considerations.

Protect working discovery and handshake with regression tests. If
authentication remains blocked after reasonable investigation, document
it and continue to Phase 4 and all independent tasks.

## Phase 4 --- Gree integration

Inspect and complete:

-   Existing driver.
-   Protocol parsing and serialization.
-   Command mapping.
-   Capability mapping.
-   Error handling.
-   Driver registration.
-   Automated tests.
-   Exact GPL-3.0 source/revision/license review.
-   Hardware verification documentation.

If no physical unit is available, complete all possible software work
and mark physical verification pending. Continue.

## Phase 5 --- Midea integration

Investigate protocol generations separately:

-   Model identification.
-   Local discovery and authentication.
-   V2/V3 differences where evidence supports them.
-   NetHome Plus authorization and user-owned token/key requirements
    where applicable.
-   Official cloud route versus community-derived route.
-   State and command mapping.
-   Automated tests.

Implement feasible model-specific support. If blocked, record evidence
and continue.

## Phase 6 --- Remaining brand investigation

Investigate each remaining brand individually: Daikin, LG, Samsung,
Dawlance, Orient, PEL, AUX, TCL, Panasonic, and Kenwood.

Complete a 13-brand compatibility/feasibility matrix. Implement viable,
legally acceptable approaches where practical. Document unsupported
models and unresolved research honestly. Do not stop after one or two
brand investigations.

## Phase 7 --- Onboarding and discovery

Complete and test:

-   Permissions.
-   Network checks.
-   Discovery.
-   Candidate deduplication and identification.
-   Device selection.
-   Pairing.
-   Authentication.
-   Connection confirmation.
-   Failure recovery.
-   Saved devices.
-   Reconnection.

## Phase 8 --- Unified controls

Complete and test:

-   Power.
-   Temperature.
-   Modes.
-   Fan speed.
-   Swing/louver.
-   State synchronization.
-   Command acknowledgement.
-   Unsupported controls.
-   Offline/stale state.
-   Multiple device switching.

Only expose capabilities supported by the selected device.

## Phase 9 --- Security, licensing, and reliability

Audit and fix:

-   Secret storage.
-   Credential/session lifecycle.
-   Secret logging.
-   Android permissions.
-   Network transport and TLS.
-   Deep links/auth redirects.
-   Third-party licenses.
-   Retry/backoff.
-   Polling and resource cleanup.
-   Reconnection.
-   Error handling.
-   Multi-device isolation.

Record remaining risks and any release blockers.

## Phase 10 --- Full testing

Run all feasible:

-   Unit tests.
-   Protocol tests.
-   Integration tests.
-   UI tests.
-   Hardware tests.
-   Lint.
-   Static analysis.
-   Debug build.
-   Release build if configuration is available.

Compare results with Phase 0 baseline. Explain every skipped test and
distinguish pre-existing failures from regressions.

## Phase 11 --- Documentation and final verification

Update all applicable documents. Verify documentation against actual
source and test evidence. Confirm the compatibility matrix, progress
tracker, security review, and licensing review are current. Produce the
final report in Section 22.

**Do not stop after any phase merely because the phase report is
complete. Continue until all feasible work is completed or each
remaining task is specifically blocked with evidence.**

------------------------------------------------------------------------

# 20. Blocker and failure management

For every failed approach, record:

1.  Requirement ID.
2.  Attempt number.
3.  Hypothesis.
4.  Investigation performed.
5.  Exact code/configuration/source inspected.
6.  Test performed and exact command/steps.
7.  Exact sanitized result.
8.  Error message with secrets removed.
9.  Whether the cause is confirmed, suspected, or unknown.
10. Alternative approaches remaining.
11. Why the task is blocked, if applicable.
12. What would unblock it.
13. Next action and owner, if known.

Potential external blockers include:

-   Missing physical hardware.
-   Missing account authorization.
-   Vendor API restrictions.
-   Network environment/client isolation.
-   Unavailable proprietary protocol information.
-   Licensing restrictions.
-   Unsupported firmware.
-   Unavailable credentials.
-   External service outage.
-   Missing release-signing configuration.

A blocker must be scoped narrowly. For example, "HaiSmart third-party
cloud sign-in is blocked" does not mean "Haier local discovery is
blocked," and neither means the entire RoomAC project is blocked.

The final report must list meaningful attempted approaches and their
results, not merely the final error.

------------------------------------------------------------------------

# 21. Final acceptance criteria

## 21.1 Application

-   [ ] App builds using the documented project build process.
-   [ ] App starts without a blocking crash.
-   [ ] Navigation works.
-   [ ] Onboarding and permissions work.
-   [ ] Discovery flow works or reports a correct empty/error state.
-   [ ] Device selection and management work for supported routes.
-   [ ] Pairing/authentication outcomes are truthful.
-   [ ] Unified controls work for verified models.
-   [ ] Offline and recovery states are usable.

## 21.2 Architecture

-   [ ] Drivers are modular.
-   [ ] Shared interfaces are consistent and do not erase vendor
    differences.
-   [ ] Brand-specific code is isolated.
-   [ ] Capabilities are model-aware.
-   [ ] Multi-device state and credentials are isolated.
-   [ ] Existing working functionality is preserved or changes are
    justified and tested.

## 21.3 Connectivity

For each claimed supported model:

-   [ ] Discovery tested.
-   [ ] Pairing/authentication tested.
-   [ ] State read tested.
-   [ ] Commands tested.
-   [ ] Command result/physical behavior verified to the level claimed.
-   [ ] Reconnection tested.
-   [ ] Limitations documented.

## 21.4 Security and licensing

-   [ ] No secrets committed.
-   [ ] No secrets logged.
-   [ ] Stored secrets protected.
-   [ ] Logout and credential removal work as applicable.
-   [ ] Android permissions reviewed.
-   [ ] Network security reviewed.
-   [ ] Third-party licenses reviewed.
-   [ ] Required notices included.
-   [ ] Known residual risks documented.

## 21.5 Testing and documentation

-   [ ] Automated tests run and results recorded.
-   [ ] Lint/static analysis run and results recorded.
-   [ ] Debug build result recorded.
-   [ ] Release build result recorded or exact blocker stated.
-   [ ] Hardware verification documented.
-   [ ] Skipped tests have explicit reasons.
-   [ ] Requirements/progress/architecture/security/licensing docs match
    implementation.
-   [ ] All 13 brands appear in compatibility matrix.
-   [ ] Blocked tasks include evidence and next steps.

Do not describe RoomAC as fully production-ready until required
security, licensing, release, and relevant hardware verification
criteria have been met.

------------------------------------------------------------------------

# 22. Required final implementation report

After working through all feasible tasks, produce a report with the
following exact structure.

## 22.1 Executive summary

Include:

-   Overall result.
-   What was implemented.
-   What demonstrably works.
-   What remains incomplete.
-   Debug/release build status.
-   Automated test status.
-   Hardware verification status.
-   Critical risks.

## 22.2 Phase completion table

  Phase                                     Status   Work performed   Tests/evidence   Remaining work
  ----------------------------------------- -------- ---------------- ---------------- ----------------
  0 --- Repository audit                                                               
  1 --- Gap analysis                                                                   
  2 --- Core architecture                                                              
  3 --- Haier                                                                          
  4 --- Gree                                                                           
  5 --- Midea                                                                          
  6 --- Remaining brands                                                               
  7 --- Onboarding/discovery                                                           
  8 --- Unified controls                                                               
  9 --- Security/licensing/reliability                                                 
  10 --- Full testing                                                                  
  11 --- Documentation/final verification                                              

## 22.3 Brand compatibility table

Include all 13 brands:

  -----------------------------------------------------------------------------------------------
  Brand       Models         Connectivity   Implementation   Hardware   Supported   Limitations
              investigated   method         status           verified   functions   
  ----------- -------------- -------------- ---------------- ---------- ----------- -------------
  Haier                                                                             

  Gree                                                                              

  Midea                                                                             

  Daikin                                                                            

  LG                                                                                

  Samsung                                                                           

  Dawlance                                                                          

  Orient                                                                            

  PEL                                                                               

  AUX                                                                               

  TCL                                                                               

  Panasonic                                                                         

  Kenwood                                                                           
  -----------------------------------------------------------------------------------------------

Do not fill the table with unsupported claims. Use "not investigated,"
"pending," or "not verified" when appropriate.

## 22.4 Implemented functionality

List actual functionality and relevant file paths. State the evidence
level for each significant item.

## 22.5 Failed attempts

For each failed attempt include:

-   What was attempted.
-   Why it was attempted.
-   What happened.
-   Sanitized error details.
-   What was learned.
-   What alternative was tried or remains.

## 22.6 Blocked work

For each blocker include:

-   Exact requirement.
-   Why it is blocked.
-   Attempts made.
-   Evidence.
-   Remaining possible approaches.
-   Exact user action needed, if any.

## 22.7 Tests and builds

Report exact commands and results, including:

-   Passed count.
-   Failed count.
-   Skipped count.
-   Lint/static-analysis result.
-   Debug build result.
-   Release build result.
-   Hardware tests and their evidence level.
-   Baseline comparison and regressions.

Never report a count without identifying the test suite/command it
describes.

## 22.8 Files changed

List:

-   Created files.
-   Modified files.
-   Deleted files, if any.
-   Reason for each important change.
-   Distinction between pre-existing user changes and agent changes.

## 22.9 Security and licensing

Report:

-   Secret audit.
-   Secure storage status.
-   Logging review.
-   Network/permission review.
-   Third-party licenses and notices.
-   Remaining concerns and release blockers.

## 22.10 User actions required

List only actions that genuinely require the user. Give exact, minimal
instructions. Do not ask the user to repeat information already
supplied.

## 22.11 Final assessment

Choose exactly one:

-   **Ready for further hardware testing.**
-   **Functional for verified model(s) only.**
-   **Partially implemented --- specific blockers remain.**
-   **Not ready --- critical implementation or verification failures
    remain.**

Explain the selected status with evidence.

------------------------------------------------------------------------

# 23. Coding-agent operating checklist

Before considering the assigned work session complete, verify all of the
following:

-   [ ] Inspected actual repository and Git state.
-   [ ] Recorded baseline build/test/lint results.
-   [ ] Preserved existing user changes.
-   [ ] Mapped actual modules and architecture.
-   [ ] Updated `docs/PROGRESS.md`.
-   [ ] Implemented available shared architecture work.
-   [ ] Investigated Haier authentication without damaging local
    discovery.
-   [ ] Completed feasible Gree work and reviewed GPL-3.0 source.
-   [ ] Investigated Midea protocol generations separately.
-   [ ] Investigated every remaining target brand individually.
-   [ ] Created/updated 13-brand compatibility matrix.
-   [ ] Implemented available onboarding/discovery/control work.
-   [ ] Added tests for changes.
-   [ ] Audited secrets, permissions, network security, and storage.
-   [ ] Reviewed third-party licenses.
-   [ ] Ran all feasible tests, lint, and builds.
-   [ ] Updated documentation to match actual behavior.
-   [ ] Recorded failures, blockers, evidence, and next actions.
-   [ ] Produced the complete final report.
-   [ ] Did not create Git commits unless explicitly requested.
-   [ ] Did not claim unverified model compatibility.

If any item remains incomplete, explain why and continue with all
independent work that can still be performed.

------------------------------------------------------------------------

# 24. Final instruction to the autonomous coding agent

Treat this file as the authoritative product and engineering
specification for RoomAC. Begin by inspecting the existing repository
and protecting all existing work. Then execute the phases, implement
what is feasible, test every change, investigate all 13 target
manufacturers, and keep the progress tracker current.

Do not stop after analysis, planning, documentation, or scaffolding. Do
not repeatedly ask for permission to perform ordinary assigned
engineering work. Do not let one vendor, credential, hardware,
licensing, or build blocker halt unrelated progress.

Do not bypass security controls, vendor authorization, licensing
obligations, or user consent. Do not invent APIs, protocol behavior,
successful tests, or compatibility. When a route is unavailable,
document exactly what was tried and why it is blocked, then continue
elsewhere.

**The required deliverable is a working, tested, honestly documented
RoomAC implementation to the greatest extent technically, legally, and
practically possible---not merely a plan describing how one might build
it.**
