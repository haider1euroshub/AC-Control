# RoomAC --- Product Requirements Specification

**Document type:** Requirements specification, not an implementation
prompt.\
**Purpose:** Define the product behavior, architecture, integrations,
security, verification, and delivery criteria for a universal
multi-brand AC controller.

## 1. Product vision

RoomAC is one Android application for discovering, connecting to, and
controlling compatible air conditioners (ACs) from multiple
manufacturers. Users should not need to understand networking,
protocols, IP addresses, ports, local keys, tokens, or
manufacturer-specific technical setup.

### Target user journey

1.  Open RoomAC.
2.  RoomAC automatically scans the current Wi-Fi/LAN and discovers
    compatible ACs.
3.  RoomAC identifies each device's manufacturer, model (when possible),
    protocol, and capabilities.
4.  User selects an AC.
5.  RoomAC performs all supported authentication, credential retrieval,
    provisioning, and connection steps inside RoomAC.
6.  The AC is added to the user's device list.
7.  User controls it from RoomAC.
8.  On later launches, RoomAC reconnects automatically.

**Ideal flow: Open RoomAC → Discover ACs → Select AC → Automatically
authenticate and connect → Control AC.**

If a manufacturer genuinely requires an action unavailable to
third-party apps, RoomAC must explain that exact limitation. It must
never pretend the action is automatic.

## 2. User-experience constraints

RoomAC must not ask users to manually enter IP/MAC addresses, ports,
subnets, protocols, encryption keys, tokens, API credentials, or
technical payloads.

-   Keep manufacturer login inside RoomAC whenever an authorized in-app
    flow is available.
-   Do not send users to a manufacturer's app or browser merely for
    routine sign-in when a supported in-app flow exists.
-   Do not require a manufacturer's app for routine control if RoomAC
    can control the AC directly.
-   A manufacturer's app may be unavoidable for a narrowly defined
    first-time task (for example, adding an appliance to an account or
    initial Wi-Fi provisioning) when no supported third-party method
    exists.
-   Do not fabricate APIs, device state, compatibility, or successful
    commands.
-   Do not expose controls the connected device does not support.

## 3. Core functional requirements

### 3.1 Automatic discovery

-   Discover supported ACs on the current LAN using appropriate
    protocol-specific methods: broadcast, multicast, mDNS/SSDP, or other
    documented methods.
-   Respect Android permissions, Wi-Fi changes, local-network
    restrictions, and network isolation.
-   Deduplicate devices found by multiple discovery methods.
-   Retry transient packet loss with bounded retries and timeouts. A
    single lost packet must not cause a saved AC to be declared offline.
-   Distinguish unreachable, unsupported, authentication-failed, and
    timed-out devices.
-   Support automatic discovery and a manual refresh action without
    creating uncontrolled scan loops.
-   Do not assume discovery works across VLANs, guest Wi-Fi, AP
    isolation, or different subnets.

### 3.2 Device identification

Record, when available: manufacturer, model/family, device ID, MAC,
IP/service endpoint, protocol/version, firmware, credential type,
supported capabilities, local/cloud availability, and required initial
provisioning.

Identification must be evidence-based. If the exact model is unknown,
show only what is known; do not guess.

### 3.3 Authentication and credential acquisition

Support applicable authorized methods: 1. Official
OAuth/authorization-code flows. 2. Official manufacturer Android
SDKs/APIs. 3. Manufacturer-supported native sign-in. 4. Authorized
manufacturer cloud endpoints, only after security, legal, and stability
review. 5. Local pairing without an account. 6. Retrieval of
device-specific local credentials through the user's own manufacturer
account.

Rules: - Use the user's own account. Never use shared, hard-coded,
developer-owned, or default accounts. - Never bypass account security,
MFA, consent, access controls, or device ownership checks. - Never
scrape credentials from another app, read another app's private storage,
or use accessibility automation to capture passwords/tokens. - Never
place user passwords, keys, tokens, cookies, or authorization headers in
source code, test fixtures, logs, crash reports, or APK constants. - A
website account is not automatically the same as an appliance/IoT
account. - Treat account creation, password reset, appliance onboarding,
Wi-Fi provisioning, account linking, and routine sign-in as distinct
capabilities. - Do not invent registration or authentication
endpoints. - Implement session persistence and renewal only where actual
service behavior has been verified. - If authorization expires, request
the minimum necessary action and preserve valid local control where
possible.

### 3.4 Local-first hybrid connectivity

Use a per-model/per-device strategy. Do not force every manufacturer
into one connection method.

Preferred options, in order where supported: 1. Direct local Wi-Fi/LAN
control. 2. Official manufacturer cloud API or SDK. 3. Official in-app
authentication to obtain authorization or local credentials. 4.
Bluetooth or other supported local provisioning. 5. Standard smart-home
protocols such as Matter. 6. Reviewed community/reverse-engineered
integrations where lawful and appropriate. 7. External hardware bridges
(IR blasters or smart-home hubs) as a fallback.

Local-first means routine commands and state reads should stay on the
LAN when supported. Cloud authentication may still be needed initially
or periodically to acquire or renew credentials.

### 3.5 Unified controls and state

Expose only controls supported by the selected device: - Power. - Target
temperature. - Operating mode (auto/cool/heat/dry/fan, where
supported). - Fan speed. - Swing/air direction. - Sleep, turbo, eco,
timers, and other verified features. - Current temperature and device
state, when available.

Distinguish requested state from confirmed state. Read back state after
commands where possible. Show pending, success, failure, offline, and
stale-state conditions. A command being sent is not proof it succeeded.
Support multiple ACs independently, room assignment, naming, renaming,
removal, and reconnect. Do not imply every saved AC is continuously
polled if only the selected unit is active.

### 3.6 Reconnection

-   Persist device and authorization data securely.
-   Reconnect automatically on app launch and network changes.
-   Check saved-device reachability with bounded retries before a full
    scan.
-   Handle DHCP/IP changes, router changes, Wi-Fi loss, AC power cycles,
    expired sessions, and key rotation.
-   Avoid unnecessary account login while a valid local credential
    works.
-   Preserve offline LAN control where supported.
-   Provide recovery when an AC is reset, moved to another network, or
    removed from the manufacturer's account.

## 4. Integration architecture

### 4.1 Shared integration contract

Each brand/model integration must declare: - Manufacturer, supported
models, and protocol versions. - Discovery and identification methods,
with confidence/evidence. - Connection type: local, cloud, or hybrid. -
Authentication method and account requirement. - Credential type,
acquisition method, validation, and renewal behavior. - Initial
provisioning requirements and whether the maker's app is unavoidable. -
Supported controls and telemetry. - Known limitations, tested firmware,
and license/distribution constraints.

The shared application layer must not accumulate brand-specific
conditionals for setup text, authentication, or control behavior when
the integration can declare those requirements.

### 4.2 Architectural responsibilities

Keep existing project modules and boundaries where practical.
Separate: - Discovery orchestration and deduplication. - Device
identification. - Manufacturer integration registry. - Authentication
and account sessions. - Provisioning workflows. - Credential retrieval
and validation. - Local and cloud transports. - Normalized state and
capability mapping. - Command execution and read-back. - Renewal and
reconnection. - Secure persistence. - UI and recovery guidance.

Use typed interfaces and explicit outcomes. Do not collapse
authentication failures into generic network errors.

### 4.3 Integration lifecycle

Support the stages relevant to each integration: 1. Discover candidate.
2. Identify brand/model/protocol. 3. Select supported connection method.
4. Determine account and provisioning requirements. 5. Authenticate
inside RoomAC where supported. 6. Retrieve/validate credentials. 7.
Pair/connect. 8. Validate device identity and state. 9. Expose supported
controls. 10. Persist secure connection data. 11. Reconnect and renew
when required.

Not every brand uses every stage; integration declarations drive the
flow.

## 5. Manufacturer roadmap

These are investigation targets, not claims of compatibility. Support is
model- and protocol-specific.

### 5.1 Haier --- existing priority

Existing project context: - Module: `driver-haismart`. - User's test AC:
Haier T3 Plus Inverter, model `HSU-20HFTEX/013WUSDC(OW)-T3`. -
Previously discovered at `192.168.18.68`. - Test device identifier:
`540853BD1D41`. - Local TCP port: `56800`. - A prior hardware handshake
reported key version `58`. - Discovery and handshake do not prove
authenticated status reads or working controls.

Required verification: - HaiSmart sign-in inside RoomAC using the user's
own account. - Account device listing. - Per-device local-key retrieval
and validation without exposing the key. - Actual status read from the
physical AC. - Physical power, temperature, mode, and fan commands from
an Android phone. - Read-back confirmation. - Reconnect and session/key
renewal where applicable.

Haier's private app identity/API may change or be blocked; this is a
compatibility risk, not an official public API. Do not claim complete
Haier support until real sign-in, key retrieval, status reads, and
physical controls pass.

A device key was exposed in an earlier conversation. Treat it as
compromised: never repeat, log, commit, or hard-code it. Rotate/re-pair
if the manufacturer supports this.

### 5.2 Gree --- existing priority

Existing module: `driver-gree`. Code includes local
discovery/pairing/control work, but physical hardware has not been
available for verification.

Required: - Test discovery on a real Gree unit. - Verify pairing and
real status reads. - Verify power, temperature, mode, fan, and supported
swing controls. - Verify whether GREE+ is required for first-time Wi-Fi
provisioning for the exact model. - Verify whether routine control works
without an account after provisioning. - Review the driver license
before distribution.

### 5.3 Midea --- next candidate after Haier/Gree

Research suggests supported local protocol generations differ in
credential requirements: some older V2 devices may need no cloud
credential; newer V3 devices may require a token/key from the user's own
NetHome Plus account.

Required: - Verify exact model/protocol support. - Determine an
authorized user-account credential flow. - Never use built-in shared
accounts or developer-owned credentials. - Assess private
API/app-identity dependency and stability. - Review library licenses,
dependencies, Android compatibility, and security. - Test physical
hardware before claiming support.

### 5.4 Other target brands

Investigate individually by model/controller family: Daikin, Panasonic,
LG, Samsung, TCL, AUX, Dawlance, Orient, PEL, and Kenwood.

Evaluate official APIs/SDKs, local LAN protocols, Bluetooth
provisioning, Matter, and suitable community implementations. Do not
assume brands share protocols because of possible OEM relationships. For
LG ThinQ and Samsung SmartThings, verify developer access and
authorization requirements; a consumer account alone may not grant
third-party API access.

## 6. Security, privacy, and licensing

-   Use Android Keystore-backed encryption for stored secrets.
-   Never log passwords, keys, tokens, cookies, authorization headers,
    or sensitive payloads.
-   Redact diagnostics and crash reports.
-   Use TLS for cloud traffic and validate authentication redirect
    destinations.
-   Use OAuth state/PKCE or equivalent protections when supported.
-   Request only necessary Android permissions and explain them.
-   Do not bypass MFA, ownership checks, account protections, or access
    controls.
-   Do not extract private data from other apps.
-   Review open-source licenses and attribution before adapting code or
    distributing drivers.
-   Keep secrets out of Git and APK constants where technically
    possible.
-   Use synthetic credentials in tests.
-   Provide sign-out and removal of stored authorization.
-   Signing out should not unnecessarily destroy a working local
    credential unless required for security or requested.

## 7. Android UX and accessibility

The app must be usable by ordinary users, including elderly users: -
Large, clearly labelled controls and touch targets. - High contrast and
readable typography. - Simple navigation and minimal setup. - Clear
device and connection status. - Plain-language explanation of
unavoidable manufacturer actions. - Keep technical diagnostics separate
from the normal control interface. - Support light/dark themes and
Android font scaling. - Keep authentication inside RoomAC whenever
permitted.

## 8. Testing and evidence

Automated tests are necessary but do not prove real-device
compatibility.

### 8.1 Automated test coverage

Test discovery/deduplication, packet-loss retries, timeouts, unknown
models, authentication success/failure/expiry, credential
retrieval/validation/rotation, local/cloud transports, command parsing,
state read-back, multiple devices, network changes, reconnection, secure
persistence, log redaction, unsupported features, and declared
provisioning requirements.

Hardware tests must be opt-in and must not run in ordinary CI unless the
required hardware and credentials are explicitly configured.

### 8.2 Physical hardware matrix

For every claimed supported model/protocol, record:

  Test                   Required evidence
  ---------------------- -------------------------------------------------
  Discovery              Physical AC found on LAN
  Identification         Brand/model/protocol evidence
  Authentication         Real account login or verified no-account flow
  Credential retrieval   Secure retrieval where required
  Connection             Authenticated local/cloud connection
  Status                 Real device state received
  Power                  Physical response and read-back
  Temperature            Physical setpoint change and read-back
  Mode                   Physical mode change and read-back
  Fan                    Physical fan change and read-back
  Reconnect              Recovery after app/AC restart
  Offline                LAN operation without internet, where supported

Do not mark a test passed based only on mocks, emulators, unit tests,
packet transmission, or an accepted handshake.

Use these evidence labels consistently: - **Verified on hardware** -
**Automated test only** - **Implemented, unverified** - **Research
only** - **Unsupported** - **Unknown**

A skipped opt-in hardware test is not a passing test.

## 9. Repository execution rules

-   Inspect repository structure, Git status, source, docs, tests, and
    APK version before changing anything.
-   Preserve existing functionality and user changes; do not replace the
    project wholesale.
-   Work incrementally and keep changes scoped.
-   Do not delete or rewrite files without explaining impact.
-   Do not commit, push, publish, or overwrite user work without
    explicit authorization.
-   Record modified, deleted, and untracked files.
-   Run relevant tests after meaningful changes and report exact
    outcomes.
-   Compilation is not proof of feature completion.
-   Never invent APIs, endpoints, protocol behavior, or passing results.

## 10. Recommended implementation sequence

### Phase 0 --- baseline audit

Inspect modules, docs, Git status, tests, build version, license risks,
and secret handling. Report baseline before implementation.

### Phase 1 --- complete Haier

Run the real HaiSmart account smoke test using credentials entered only
in the user's local PowerShell environment. Never ask the user to paste
passwords, tokens, or keys into chat. Verify login, device list,
local-key retrieval, and real status read. Then test physical power,
temperature, mode, and fan from the Android phone. Record model,
firmware, app build, network conditions, date, and outcomes.

### Phase 2 --- verify Gree

Use a real Gree unit to verify discovery, pairing, state, controls, and
initial provisioning requirements. Complete license review before
distribution.

### Phase 3 --- Midea feasibility and implementation

Complete protocol/model research, security and license review, implement
one clearly identified model/protocol family, and verify physical
hardware.

### Phase 4 --- additional manufacturers

Prioritize based on local market relevance, test hardware availability,
API access, protocol evidence, and maintainability. Implement one
integration at a time.

### Phase 5 --- reliability and release readiness

Test multiple ACs, reconnection, network changes, offline control,
accessibility, security, privacy, and licensing. Prepare a
release-signed build only with user authorization. Label debug APKs as
debug.

## 11. Required project documentation

Maintain and update: - `requirement.md` --- this specification. -
`docs/MANUFACTURER_CAPABILITIES.md` --- brand/model capability and
evidence matrix. - `docs/PROGRESS.md` --- completed, active, blocked,
and next tasks. - `docs/HARDWARE_TESTS.md` --- reproducible physical
test procedures and actual results. - `docs/ARCHITECTURE.md` --- shared
architecture and integration contracts. - `docs/SECURITY.md` ---
credential storage, authentication, threats, and redaction. -
`docs/LICENCES.md` --- dependencies, licenses, attribution, and
distribution restrictions.

Preserve useful existing documentation; do not overwrite it blindly.

## 12. Required implementation report

After each work session, report:

1.  **Inspected:** repository state, modules, documentation, and
    baseline.
2.  **Changed:** exact files added/modified/deleted and reasons.
3.  **Working:** distinguish implemented behavior, automated tests, and
    hardware verification.
4.  **Tested:** exact commands, test counts, failures, skips, and
    hardware models.
5.  **Manufacturer status:** connection method, account requirements,
    provisioning, credential flow, controls, limitations, and evidence
    label.
6.  **Security/licensing:** changes and unresolved risks.
7.  **Blocked:** missing hardware, account access, manufacturer
    restrictions, or dependencies.
8.  **Build:** exact APK path, version, build type, and debug/release
    status.
9.  **Git:** modified/untracked/deleted files and commit status. Do not
    commit without authorization.
10. **Next action:** one concrete recommended step.

Never report work as completed unless it was actually executed. Source
inspection alone is not hardware verification.

## 13. Definition of success

For each supported model, RoomAC succeeds when it: - Discovers the
physical AC without manual network details. - Identifies the device and
selects a supported connection method. - Supports in-app authentication
wherever the manufacturer permits it. - Retrieves required credentials
securely without manual technical entry. - Explains unavoidable external
setup actions narrowly and accurately. - Connects and reports real
device state. - Controls all verified supported functions from RoomAC. -
Reconnects reliably. - Documents model coverage, limitations, legal
basis, and hardware evidence.

**Optimize for the simplest honest user experience, not the largest
unverified brand-support list.**
