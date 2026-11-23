# Requirements --- Haier AC Local Control Android App

**Project:** Existing Android application (incomplete)\
**Target appliance:** Haier T3 Plus Inverter Series\
**Exact model:** `HSU-20HFTEX/013WUSDC(OW)-T3`\
**Primary objective:** Complete the existing Android app so it can
discover, pair with, monitor, and control the target AC over the local
Wi-Fi network, with no cloud dependency for normal control after setup.

------------------------------------------------------------------------

## 1. Project instructions

This is an **existing, incomplete Android project**. The implementation
agent/developer must:

1.  Inspect the complete existing repository before changing code.
2.  Identify the current framework, language, architecture, navigation,
    state management, networking implementation, and build
    configuration.
3.  Preserve the existing working features and visual design wherever
    practical.
4.  Continue and repair the existing project rather than replacing it
    with a new app or rewriting the project without a clear technical
    reason.
5.  Identify incomplete, broken, placeholder, and duplicated
    functionality; fix these systematically.
6.  Keep the implementation compatible with the project's existing
    technology stack unless a change is necessary and justified.
7.  Do not simulate successful AC control. A control is complete only
    when the command is accepted by the AC and the resulting state is
    read back or otherwise verified.
8.  Do not claim support for an AC feature unless it is confirmed by the
    appliance's model profile, reported capabilities, protocol
    documentation, or successful hardware testing.

Before implementation, provide a short audit of the existing project,
the main gaps, and the proposed implementation plan. Then proceed with
the work; do not stop after producing an audit.

## 2. Important technical constraints

### 2.1 Local network control

The target AC has been observed on the home network with a service
listening on **TCP port `56800`**. During testing, the candidate device
at `192.168.18.68` became unreachable when the AC's power was physically
disconnected. This is strong evidence that the address belongs to the
AC's network interface, but the app must not hardcode this IP address.

The app must:

-   Discover the AC dynamically on the local network.
-   Communicate directly with the AC over the LAN for normal control.
-   Handle DHCP address changes and rediscover the appliance when
    necessary.
-   Avoid relying on the phone and AC retaining fixed IP addresses.
-   Verify the device identity before sending commands.
-   Support a connection timeout, retry strategy, and clear connection
    status.

**Do not assume that an open TCP port alone is sufficient to control the
AC.** The correct Haier local protocol, message framing,
authentication/encryption, session handling, device identifier, and
local key requirements must be implemented accurately.

### 2.2 Local key / authentication requirement

The community `haismart-local` implementation documents local
communication with compatible Haismart appliances over TCP port `56800`.
Its documented setup may require obtaining a per-appliance local
encryption key through a Haier account setup step, or using a key
already obtained and saved. After the key is available, normal appliance
communication can take place locally.

Therefore:

-   Investigate and reuse/adapt the documented protocol implementation
    where its license and architecture permit.
-   Determine whether this exact AC uses the compatible Haismart
    protocol.
-   Do not assume that the serial number or barcode alone generates,
    reveals, or replaces the local encryption key.
-   If a key must be retrieved from Haier during initial setup, clearly
    disclose this to the user and request only the credentials and
    permissions genuinely required.
-   Store any local key securely using Android Keystore-backed
    encryption. Never print keys, passwords, tokens, or other secrets in
    logs.
-   Once setup is complete, normal control should work on the local
    network without requiring cloud access, provided the protocol and
    key remain valid.
-   Provide an offline setup route if the user already has the required
    local key and device information.

The app must clearly distinguish: 1. **Local discovery** --- finding the
AC on the Wi-Fi network. 2. **Device identification** --- confirming the
AC's model/device identity. 3. **Authentication/pairing** --- supplying
or obtaining the required local key. 4. **Local control** --- sending
commands directly to the AC.

These are separate steps and must not be represented as one successful
step when only discovery has occurred.

## 3. Core user requirements

### 3.1 Automatic Wi-Fi detection and AC connection

When the app opens, it must automatically:

1.  Check whether Wi-Fi is enabled and the phone is connected to a
    network.
2.  Check the required Android network permissions.
3.  Begin local AC discovery without requiring the user to enter an IP
    address.
4.  Search the current local subnet using a safe, efficient discovery
    strategy supported by the protocol.
5.  Identify compatible Haier devices and verify their identity.
6.  Automatically reconnect to a previously paired AC when it is found.
7.  Load the AC's current status and supported controls.
8.  Display a clear connection state.

Required connection states:

-   Checking network
-   Wi-Fi unavailable
-   Searching for AC
-   AC found
-   Authenticating / pairing
-   Connected locally
-   Reconnecting
-   AC offline / unreachable
-   Authentication failed
-   Unsupported device or model
-   Connection error

The app must not freeze while scanning. Discovery and network operations
must run asynchronously. Provide a manual **Scan Again** action.

### 3.2 First-time setup and multiple ACs

The app must support:

-   First-time discovery and pairing.
-   Saving a paired AC so it can reconnect automatically next time.
-   Adding more than one AC.
-   Giving each AC a user-friendly name, such as Bedroom, Lounge, or
    Office.
-   Selecting the active AC from a device list.
-   Removing a saved AC.
-   Re-discovering a saved AC if its IP address changes.
-   Preventing the same physical AC from appearing multiple times.
-   Showing each device's model, serial/device identifier (where
    available), local IP, and connection status.

Do not identify an appliance using IP address alone. Prefer a stable
device identifier supplied by the protocol.

### 3.3 Barcode / QR scanner

Provide an in-app scanner using the phone camera.

The scanner must:

-   Scan supported barcodes and QR codes on the AC's product label,
    rating plate, packaging, or documentation.
-   Extract the serial number, model number, product code, device
    identifier, or other relevant encoded information when present.
-   Parse the scanned value safely and show the extracted fields for
    user confirmation.
-   Validate the model against the target model and supported model
    profiles.
-   Use scanned information to identify or select the correct AC during
    pairing where technically supported.
-   Avoid treating an ordinary serial number as a local encryption key.
-   Show a useful error if the code is unreadable or contains no
    supported identifier.
-   Allow the user to scan again or enter the information manually.
-   Request camera permission only when the scanner is opened.
-   Work in adequate low-light conditions using torch control if
    supported.
-   Avoid uploading barcode contents to a server unless the user has
    been clearly informed and the upload is required for a documented
    setup step.

### 3.4 Manual device information entry

Provide a manual setup path alongside scanning.

The user must be able to enter:

-   AC model number
-   Serial number
-   Product code / device identifier, if required
-   Local IP address, as an optional advanced/manual fallback
-   Local key, only if the protocol requires it and the user already has
    it

Validate fields and explain which values are required. Do not make the
local IP address mandatory for normal setup.

The exact model must be selectable or recognizable as:

`HSU-20HFTEX/013WUSDC(OW)-T3`

### 3.5 AC control dashboard

After successful pairing, show a clear, responsive control screen with
the current AC state and all controls supported by the actual appliance.

The dashboard must include, where supported:

-   Power ON / OFF
-   Operating mode
-   Target temperature
-   Current indoor/room temperature
-   Fan speed
-   Vertical swing
-   Horizontal swing
-   Turbo / Powerful mode
-   ECO / energy-saving mode
-   Sleep mode
-   Self-clean function
-   Display / LED control
-   Health / purification controls, only if the unit reports and
    supports them
-   Any additional model-specific functions exposed by the appliance

The dashboard must show the actual state reported by the AC, not just
the last button the user tapped.

### 3.6 Operating modes

Expose all operating modes reported as supported by the AC, expected to
include:

-   Auto
-   Cool
-   Heat
-   Dry
-   Fan

The model listing for this exact product identifies cooling, heating,
and dry operation. The final app must confirm the actual available modes
from the appliance/model capability data. Do not show unsupported modes.

### 3.7 Temperature control

-   Provide an intuitive temperature control.
-   Use the temperature range and increments reported for the exact AC
    model.
-   Display the current setpoint and room temperature separately.
-   Prevent invalid values.
-   Update the display after the AC confirms the change.
-   Handle mode-dependent restrictions and rejected commands.
-   Do not assume a temperature range or step size without
    model/protocol evidence.

### 3.8 Fan control

-   Display all fan-speed options reported by the appliance.
-   Support Auto and manual fan speeds where available.
-   Show the actual selected speed.
-   Handle mode restrictions and commands rejected by the AC.

### 3.9 Swing / louvre control

Where supported, provide separate controls for:

-   Vertical / up-down swing
-   Horizontal / left-right swing

Support available fixed positions and automatic swing modes only when
the AC reports or the model profile confirms them.

### 3.10 Special functions

Investigate and implement every additional function that the exact model
actually supports through the local protocol. Potential functions
include:

-   Turbo / Powerful
-   ECO
-   Sleep
-   Self-clean
-   Display light
-   Quiet mode
-   Health / purification
-   Fresh air
-   Other model-specific options

The exact model's retailer listing identifies Wi-Fi, heating, cooling,
dry, vertical and horizontal swing, self-clean, Turbo, and ECO. These
are useful starting points, not proof that every feature is exposed
through the local protocol.

Do not invent buttons for features that are absent from the model or
unavailable through the protocol. If a function is supported by the
physical AC but not yet controllable through the local protocol,
document it as unsupported rather than presenting a non-working control.

### 3.11 Live status and synchronization

-   Fetch the AC's current status after connection.
-   Refresh status periodically using a configurable, reasonable polling
    interval.
-   Refresh status after every command.
-   Reflect changes made using the original Haier remote or HaiSmart app
    when they are visible in the local status report.
-   Show the last successful update time.
-   Avoid excessive polling that could overload the AC or disrupt its
    single local session.
-   Handle a lost connection and restore the dashboard state when the AC
    reconnects.

## 4. User interface and experience

The app should look and behave like a finished Android product.

Requirements:

-   Clean, modern, professional interface.
-   Simple onboarding for non-technical users.
-   Clear device discovery and pairing progress.
-   Easy-to-read temperature and power controls.
-   Clear indication that the connection is local.
-   Clear error messages with actionable recovery steps.
-   Loading indicators for operations that take time.
-   Confirmation or feedback when a command succeeds or fails.
-   No fake success messages.
-   Accessible text sizes, touch targets, and contrast.
-   Support common Android phone screen sizes.
-   Handle portrait orientation correctly.
-   Preserve paired devices and user settings after app restart.

## 5. Android permissions and networking

Implement only the permissions required by the app and the Android
versions being supported.

Review and correctly implement:

-   Camera permission for barcode/QR scanning.
-   Wi-Fi/network state access.
-   Local network communication permissions and platform requirements.
-   Android 13+ nearby Wi-Fi device permission requirements, where
    applicable.
-   Any Android-version-specific multicast/discovery permissions if the
    chosen discovery protocol requires them.
-   Runtime permission explanations and denial handling.

The app must work when the phone and AC are connected to the same
reachable local network/subnet. Explain that guest Wi-Fi, client
isolation, VLAN separation, VPNs, or router isolation may prevent local
discovery.

Do not request location permission unless it is genuinely required by
the selected Android API and explain the reason to the user.

## 6. Protocol implementation and architecture

The developer must investigate the protocol before implementing
commands.

Required tasks:

1.  Review the `enapt/haismart-local` project and its protocol
    documentation:
    -   https://github.com/enapt/haismart-local
2.  Review its license and comply with all applicable attribution and
    license requirements.
3.  Confirm whether the target AC pairs with HaiSmart / Haismart / Haier
    U+ and uses the documented port-56800 protocol.
4.  Determine the exact local authentication and encryption
    requirements.
5.  Determine how to discover devices without hardcoding IP addresses.
6.  Determine how to identify the exact product/model profile.
7.  Map each reported AC attribute to a user-facing control or status
    item.
8.  Implement command acknowledgement and status read-back.
9.  Handle protocol errors, timeouts, reconnects, and session conflicts.
10. Add protocol-level tests using captured or documented messages where
    possible.

Architecture should separate:

-   Android UI
-   Device discovery
-   Pairing and authentication
-   Local protocol client
-   Model/capability profile
-   AC command service
-   Status polling and synchronization
-   Secure credential/key storage
-   Persistence for paired devices
-   Logging and diagnostics

Do not put protocol parsing, encryption, or socket logic directly inside
UI components.

The AC may allow only one local session at a time. The app must manage
its own connection lifecycle and avoid opening unnecessary concurrent
sessions. If another local controller is connected, show a clear
recoverable error.

## 7. Security and privacy

-   Store local keys and credentials securely using Android
    Keystore-backed storage.
-   Never hardcode personal credentials or per-device secrets in source
    code.
-   Never log passwords, tokens, encryption keys, or sensitive barcode
    data.
-   Use local communication for ordinary control after setup.
-   Do not send AC status, serial numbers, or personal data to external
    services unless necessary, disclosed, and authorized.
-   Provide a way to remove a paired device and securely clear its
    stored credentials.
-   Do not disable Android network security protections globally.
-   Do not use undocumented credentials or bypass access controls.

## 8. Error handling

Provide clear handling for:

-   Wi-Fi disabled
-   Phone not connected to Wi-Fi
-   AC powered off
-   AC not connected to Wi-Fi
-   AC not found
-   Phone and AC on isolated/different networks
-   Port/service unavailable
-   Invalid or unsupported barcode
-   Incorrect model or serial number
-   Missing/invalid local key
-   Authentication failure
-   Protocol mismatch
-   AC busy / another local session active
-   Command rejected by AC
-   Command timeout
-   AC disconnecting during a command
-   Permission denied
-   App process killed or resumed
-   DHCP/IP address changed

Errors must include a practical next action. Do not expose secret values
or raw sensitive protocol payloads in user-facing messages.

## 9. Testing requirements

Testing must cover both software behavior and real hardware.

### 9.1 Automated/software tests

-   Unit tests for barcode parsing and validation.
-   Unit tests for model/capability mapping.
-   Unit tests for protocol encoding/decoding.
-   Unit tests for authentication and error handling, using safe test
    fixtures.
-   Tests for command-to-status synchronization.
-   Tests for saved-device persistence.
-   Tests for discovery timeouts and reconnect logic.
-   Tests for unsupported features and invalid values.
-   Build and lint checks must pass.

### 9.2 Real-device tests

Test with the exact physical model:

`Haier HSU-20HFTEX/013WUSDC(OW)-T3`

At minimum, verify:

1.  Phone and AC on the same Wi-Fi network.
2.  App opens and automatically discovers the AC.
3.  Previously paired AC reconnects after app restart.
4.  AC is found after its IP address changes.
5.  AC powered off and powered back on.
6.  Wi-Fi temporarily disconnected and restored.
7.  Power ON and OFF.
8.  Every available operating mode.
9.  Temperature adjustment at minimum, maximum, and normal values.
10. Every available fan speed.
11. Vertical swing and horizontal swing.
12. Turbo / Powerful mode, if available.
13. ECO mode, if available.
14. Sleep mode, if available.
15. Self-clean, if available, with correct cycle/status behavior.
16. Every other feature confirmed by the actual model.
17. State synchronization after changes made using the original remote.
18. Invalid commands and unsupported functions.
19. App backgrounding, foregrounding, and process restart.
20. Local operation with the phone's internet access disabled, after any
    required initial key setup is complete.

Record the result of every test as **PASS**, **FAIL**, or **NOT
SUPPORTED**, with notes. Do not mark a feature as tested merely because
its UI button exists.

### 9.3 Cloud-independence test

The desired end state is local control without Haier Cloud for normal
operation.

Verify separately:

-   Whether initial pairing/key retrieval requires a Haier account or
    cloud connection.
-   Whether the app can control the AC after setup with phone internet
    access disabled while local Wi-Fi remains enabled.
-   Whether the AC itself needs internet access for the local protocol
    to continue working.
-   Whether the app makes any cloud requests during normal control.

Document any remaining cloud dependency honestly. Do not claim the
complete system is cloud-independent unless it has been tested under
these conditions.

## 10. APK build and delivery requirements

The project is not complete until a working APK is built and made
available.

Deliver:

-   A debug APK for installation and testing.
-   A release APK if release signing is configured and the developer can
    produce it securely.
-   The APK file in an obvious project output location, such as
    `build/outputs/` or a clearly named `release/` folder.
-   Exact APK file path and a direct downloadable artifact/link in the
    completion report when the environment supports file delivery.
-   Build instructions and required Android/Gradle/JDK versions.
-   Installation instructions.
-   A short test report describing the tested device, Android version,
    test results, and known limitations.

Do not commit signing passwords, private keystores, API secrets, or
local keys to the repository. If a production signing key is not
supplied, deliver a debug APK and clearly state that a production-signed
APK still requires the owner's signing configuration.

The APK must be installed and launched on a real Android device. A
successful Gradle build alone is not sufficient proof that the app
works.

## 11. Definition of done / acceptance criteria

The project is accepted only when all applicable criteria below are
satisfied:

-   [ ] Existing project has been audited and completed without
    unnecessarily replacing its architecture.
-   [ ] App builds successfully.
-   [ ] APK is generated and available to download.
-   [ ] APK installs and launches on a real Android phone.
-   [ ] App checks Wi-Fi automatically on launch.
-   [ ] App discovers the AC without requiring the user to enter its IP
    address.
-   [ ] Previously paired AC reconnects automatically.
-   [ ] Barcode/QR scanner reads supported appliance identifiers.
-   [ ] Manual serial/model/device information entry works.
-   [ ] Pairing and local-key requirements are implemented and
    explained.
-   [ ] Exact AC identity is verified before control.
-   [ ] App communicates with the AC locally over the LAN.
-   [ ] Power and every confirmed supported function work on the
    physical AC.
-   [ ] AC status is read back and synchronized correctly.
-   [ ] App recovers from power loss, Wi-Fi loss, and IP changes.
-   [ ] No unsupported control is presented as functional.
-   [ ] Local operation without phone internet has been tested after
    setup.
-   [ ] No secrets are exposed in source code or logs.
-   [ ] Automated checks pass and real-device test results are
    documented.
-   [ ] Known limitations and any remaining cloud dependency are
    documented.

A feature that cannot be verified on the exact AC must be marked **NOT
VERIFIED** or **NOT SUPPORTED**, not marked complete.

## 12. Required final developer report

At completion, provide:

1.  Summary of what was already present in the project.
2.  Summary of changes made.
3.  Protocol and authentication approach used.
4.  Exact AC functions successfully tested.
5.  Functions not supported or not verified, with reasons.
6.  Whether normal control works without internet after setup.
7.  APK download location/link.
8.  Build and installation instructions.
9.  Automated test results.
10. Real-device test results.
11. Remaining limitations or follow-up work.

------------------------------------------------------------------------

## 13. Reference information

### Target appliance

-   Brand: Haier
-   Series: T3 Plus Inverter Series
-   Model: `HSU-20HFTEX/013WUSDC(OW)-T3`
-   Observed local service: TCP port `56800`
-   Candidate AC network address observed during testing:
    `192.168.18.68` (dynamic; do not hardcode)
-   Candidate device became unreachable when the AC's power was
    physically disconnected. This is a strong correlation, not a
    substitute for protocol-level identity verification.

### Initial model feature references

A product listing for this model reports:

-   Cooling
-   Heating
-   Dry mode
-   Vertical flapper auto swing
-   Horizontal louvre auto swing
-   Wi-Fi
-   Self-clean
-   Turbo
-   ECO

These should be confirmed against the actual appliance and its local
protocol capabilities before being marked implemented.

### Technical reference

-   Haismart Local: https://github.com/enapt/haismart-local

This is a community-maintained interoperability project, not an official
Haier SDK. Review its current documentation, compatibility information,
protocol behavior, and license before relying on it. Its documentation
describes port `56800` local communication and explains that some
devices require a local key obtained during setup. It does **not** prove
that every feature listed for this exact model is controllable through
the local protocol.

------------------------------------------------------------------------

**Primary delivery expectation:** Finish the existing Android
application as a tested, installable product for the specified Haier AC.
Do not stop at UI mockups, a technical explanation, a partial
implementation, or a successful build. Verify real commands and status
against the physical AC, and provide the APK.
