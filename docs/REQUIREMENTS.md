# Universal Android AC Remote App --- Requirements Specification

**File:** `requirement.md`\
**Document purpose:** Product and engineering specification for the
existing Android application\
**Status:** Requirements baseline; implementation has not started

------------------------------------------------------------------------

## 1. Project Overview

### 1.1 Vision

Build a highly universal, intelligent, automatic air-conditioner (AC)
remote-control application for Android. The application should
automatically discover, identify, authenticate with, connect to, and
control compatible air conditioners over the phone's local Wi-Fi
network.

The primary objective is to remove technical setup from ordinary users.
The application should make every reasonable engineering effort to
provide broad AC compatibility and a highly automatic user experience.

### 1.2 Intended user experience

The ideal user journey is:

1.  The user opens the application.
2.  The application detects the active Wi-Fi network.
3.  The application scans the local network for AC devices.
4.  The application identifies each detected device's manufacturer,
    model, protocol, and available capabilities where the device exposes
    that information.
5.  The application performs required authorized authentication,
    pairing, and credential provisioning.
6.  The application connects to the AC.
7.  The user can control the AC through a simple, consistent remote
    interface.

The user must not need to understand networking, protocols, device
identifiers, or technical configuration.

### 1.3 Product principles

-   Automate discovery, identification, pairing, and reconnection
    wherever supported.
-   Prefer direct local-network control for routine operations.
-   Provide one consistent control experience across supported
    manufacturers.
-   Adapt the interface to the actual capabilities of each AC.
-   Protect device credentials and user privacy.
-   Build on the existing Android project rather than replacing it.
-   Expand compatibility through modular brand and protocol
    integrations.
-   Record compatibility at model level and distinguish tested support
    from unverified support.

## 2. Brand Priorities and Compatibility Strategy

### 2.1 Initial target manufacturers

The application must prioritize manufacturers commonly used in Pakistan
while remaining extensible to international manufacturers, OEMs, and
rebranded devices.

  Priority tier              Manufacturer     Initial priority
  -------------------------- -------------- ------------------
  Highest                    Haier                           1
  Highest                    Gree                            2
  Highest                    Dawlance                        3
  Highest                    Orient                          4
  Highest                    PEL                             5
  Highest                    Midea                           6
  Highest                    AUX                             7
  Highest                    TCL                             8
  Additional international   Daikin                          9
  Additional international   Panasonic                      10
  Additional international   LG                             11
  Additional international   Samsung                        12
  Additional international   Kenwood                        13

The architecture must not be limited to this list. It must allow
additional manufacturers, OEM manufacturers, rebranded devices, models,
and protocol families to be integrated without rewriting the core
application. Compatible OEM and rebranded AC models are important
compatibility targets.

### 2.2 Initial integration sequence

The implementation roadmap must prioritize integrations in this order:

1.  Haier
2.  Gree
3.  Midea
4.  AUX
5.  TCL
6.  Dawlance
7.  Daikin
8.  Orient
9.  PEL
10. Panasonic
11. LG
12. Samsung
13. Kenwood

Continue expanding to additional manufacturers and compatible OEM
devices.

### 2.3 Compatibility evidence

Support must be tracked at the model level. A manufacturer or protocol
being supported does not, by itself, mean every model from that
manufacturer is fully supported. Each model's discovery, authentication,
communication, and control capabilities must be recorded and tested.

## 3. Functional Requirements

### 3.1 Automatic Wi-Fi discovery

Automatic local-network discovery is a core product requirement.

The application shall:

-   Detect the active Wi-Fi connection and determine the local
    network/subnet details required for discovery.
-   Initiate device discovery automatically when appropriate.
-   Support applicable discovery techniques, including UDP broadcast,
    multicast, mDNS, SSDP, service discovery, and manufacturer-specific
    discovery protocols.
-   Use protocol-specific scanning and identification strategies.
-   Detect devices when they become available and detect devices that
    disappear or become unreachable.
-   Handle DHCP address changes.
-   Recognize previously paired devices after reconnecting.
-   Avoid registering the same physical AC more than once.
-   Support manual rescanning and automatic rediscovery.
-   Discover multiple AC units on the same network.
-   Identify manufacturer and model wherever the available device
    information permits.
-   Determine the communication protocol and control capabilities
    supported by each device.

The normal user must not be required to enter an IP address, MAC
address, serial number, TCP/UDP port, local key, device token, protocol
name, or network configuration. Discovery and identification work should
occur automatically in the background wherever technically supported.

### 3.2 Authentication and credential provisioning

The application shall minimize manual setup and obtain required device
credentials through authorized methods.

Requirements:

-   Investigate official manufacturer APIs and SDKs.
-   Support manufacturer-account authentication where required.
-   Investigate authorized local-device pairing and provisioning
    mechanisms.
-   Use manufacturer integrations and maintained community
    implementations as technical references.
-   Retrieve or provision local keys, tokens, session credentials, and
    other required authentication material through authorized workflows.
-   Associate credentials with the correct physical AC.
-   Store credentials securely using Android Keystore-backed encryption.
-   Reuse previously authorized credentials when reconnecting.
-   Avoid asking users to manually copy keys or tokens.
-   Provide a simple first-time authorization flow when authorization is
    required.
-   Keep authentication logic separate for each manufacturer and
    protocol.
-   Refresh credentials or recover expired sessions automatically where
    supported.

Credentials must never be exposed in source code, Git repositories,
application logs, analytics, crash reports, or ordinary UI screens.

### 3.3 Local Wi-Fi communication and control

The primary communication objective is direct communication between the
Android application and the AC over the local network.

Implement manufacturer-specific protocol adapters. Each adapter shall
handle:

-   Device communication.
-   Authentication.
-   Encryption and message encoding.
-   Command transmission.
-   Response parsing.
-   Device-status retrieval.
-   Connection monitoring.
-   Error handling.
-   Reconnection.

The application shall:

-   Prioritize local LAN communication for routine AC operations.
-   Support offline local operation after initial provisioning wherever
    the device protocol permits it.
-   Avoid unnecessary cloud requests for ordinary commands such as
    power, temperature, and fan-speed changes.
-   Verify commands reliably and distinguish between a command being
    sent and the AC actually applying it.

### 3.4 Unified AC controls

Provide one consistent remote-control interface regardless of
manufacturer or protocol. Show controls only when the selected AC
actually supports them.

**Essential controls, wherever supported:**

-   Power ON/OFF.
-   Temperature adjustment.
-   Operating mode: Auto, Cool, Heat, Dry, and Fan.
-   Fan speed: Auto, Low, Medium, and High.
-   Swing control.
-   Current AC operating status.
-   Current temperature setting.
-   Indoor temperature, where available.

**Additional controls, wherever supported:**

-   Sleep mode.
-   Turbo mode.
-   Eco mode.
-   Quiet mode.
-   Timer ON/OFF.
-   Vertical swing.
-   Horizontal swing.
-   Display ON/OFF.
-   Filter status.
-   Energy-saving features.
-   Error or fault status.

The interface must adapt to the actual supported feature set of each AC.
Unavailable controls must not be presented as functional controls.

### 3.5 Multiple AC management

The application must manage multiple AC units independently, for example
Living Room AC, Bedroom AC, Office AC, and Guest Room AC.

Requirements:

-   Discover multiple devices automatically.
-   Assign each device a unique internal identity.
-   Allow users to rename devices and assign room names.
-   Save each device's configuration and authorization information.
-   Display each device's connection status.
-   Allow users to select and control each AC independently.
-   Preserve names and configurations across application restarts.
-   Reconnect automatically to previously configured devices.
-   Prevent commands from being sent accidentally to the wrong AC.

Users must be able to manage all discovered ACs from one application.

### 3.6 Reconnection and background management

Implement reliable connection management.

The application shall:

-   Reconnect automatically to previously paired ACs.
-   Detect Wi-Fi disconnections and network-connectivity changes.
-   Rediscover devices after network changes.
-   Handle AC power cycles and router restarts.
-   Refresh expired authentication sessions where supported.
-   Maintain accurate device connection status.
-   Avoid unnecessary repeated scans.
-   Use appropriate Android background-execution mechanisms.
-   Preserve device configuration across application restarts.

## 4. User Experience and Accessibility Requirements

### 4.1 Elderly-friendly interface

The application is intended for elderly people and non-technical users.
Simplicity is a major design requirement.

The interface shall include:

-   Large buttons and temperature controls.
-   A clear, prominent power button.
-   High-contrast colors.
-   Readable typography.
-   Simple language.
-   Minimal navigation.
-   Minimal setup screens.
-   Clear connection indicators.
-   Immediate visual feedback.
-   Simple error messages.
-   Accessible touch targets.
-   Support for Android font scaling.

The normal user interface must avoid technical terminology such as IP
address, MAC address, local key, protocol, and port number.

### 4.2 Main screen

The main screen must prioritize the currently selected AC and its
essential controls. Users should not need technical knowledge to operate
the application.

### 4.3 Feedback and errors

The application must clearly communicate connection state, command
outcomes, and recoverable errors in language understandable to
non-technical users. A command must not be shown as successfully applied
unless the application has adequate confirmation from the device or
protocol.

## 5. Technical Architecture Requirements

The project must use a modular, extensible architecture. The following
components must be considered and implemented in a way that fits the
existing codebase.

  -----------------------------------------------------------------------
  Component                           Responsibilities
  ----------------------------------- -----------------------------------
  Network Discovery Manager           Detect network connectivity,
                                      identify local network details, and
                                      coordinate discovery operations.

  Brand and Protocol Registry         Maintain manufacturer and protocol
                                      definitions; map supported models
                                      to communication adapters.

  Device Identification Service       Identify manufacturer, model,
                                      protocol, and capabilities.

  Authentication and Provisioning     Handle manufacturer-specific
  Manager                             authentication and authorized
                                      credential retrieval/management.

  Protocol Adapter Layer              Implement individual brand and
                                      protocol integrations.

  Unified AC Device Interface         Expose common AC operations
                                      regardless of manufacturer.

  Device Manager                      Manage multiple ACs, room names,
                                      and saved configurations.

  Secure Credential Store             Protect credentials using Android
                                      security facilities.

  Connection Manager                  Manage connection state,
                                      reconnection, and communication
                                      errors.

  Remote Control UI                   Present consistent controls based
                                      on each device's supported
                                      capabilities.
  -----------------------------------------------------------------------

New brand integrations should be addable through modular adapters
without major changes to the core application.

## 6. Security and Privacy Requirements

Implement security throughout the application.

-   Use authorized authentication and device provisioning.
-   Protect stored credentials.
-   Use encrypted storage and Android Keystore-backed protection for
    sensitive credential material.
-   Do not hardcode secrets.
-   Do not expose authentication material in logs, analytics, crash
    reports, or ordinary UI.
-   Do not transmit credentials unnecessarily.
-   Request only Android permissions required for application
    functionality.
-   Clearly explain required account authorization to users.
-   Ensure users can manage only devices they are authorized to control.

## 7. Non-Functional Requirements

### 7.1 Reliability

-   Device discovery and reconnection must handle changing network
    conditions.
-   The application must distinguish device reachability from successful
    command execution.
-   Communication failures must be handled without corrupting saved
    device configuration.
-   A failure affecting one AC must not cause commands to be routed to
    another AC.

### 7.2 Maintainability and extensibility

-   Keep manufacturer-specific logic isolated in protocol adapters.
-   Keep shared device operations behind a unified interface.
-   Allow new manufacturers, OEMs, models, and protocol families to be
    added incrementally.
-   Maintain compatibility and testing documentation as integrations
    expand.

### 7.3 Privacy and usability

-   Minimize unnecessary cloud communication for local control.
-   Keep sensitive credentials out of ordinary user-facing screens and
    diagnostic output.
-   Keep normal setup and control flows understandable to non-technical
    users.

## 8. Testing and Verification Requirements

Test with real AC hardware wherever possible.

Testing must cover:

-   Automatic device discovery.
-   Manufacturer identification.
-   Model identification.
-   Authentication and provisioning.
-   Local Wi-Fi connection.
-   Power ON/OFF.
-   Temperature changes.
-   Operating modes.
-   Fan speed.
-   Swing.
-   Additional supported controls.
-   Multiple AC units.
-   Device renaming.
-   Router restart.
-   Wi-Fi disconnection and reconnection.
-   AC power cycling.
-   Credential persistence.
-   Offline local control.
-   Incorrect commands and communication failures.

### 8.1 Compatibility matrix template

Maintain a compatibility matrix with at least the following fields:

  --------------------------------------------------------------------------------------------------------------------------
  Manufacturer   Model   Protocol   Discovery   Authentication   Credential     Local           Supported   Test     Notes
                                    method      method           provisioning   communication   controls    status   
                                                                 method         method                               
  -------------- ------- ---------- ----------- ---------------- -------------- --------------- ----------- -------- -------
                                                                                                            Not      
                                                                                                            tested   

  --------------------------------------------------------------------------------------------------------------------------

Use model-level records. Do not mark a model fully supported solely
because its manufacturer or protocol is supported. Record actual testing
status and the controls verified on the physical device.

### 8.2 Test status definitions

Use explicit status values in compatibility records, such as:

-   **Not tested:** No model-level test has been completed.
-   **In investigation:** Research or integration work is underway.
-   **Partially tested:** Some discovery, authentication, or control
    behavior has been verified, but the complete feature set has not.
-   **Verified:** The documented discovery, authentication, local
    communication, and listed controls have been tested on the
    identified model.
-   **Issue identified:** Testing found a known defect; document the
    affected behavior.

These status labels are for development records, not necessarily for the
normal user interface.

## 9. Existing Android Project Preservation

**Critical requirement:** An Android application project already exists.
Development agents must work on that existing project rather than
creating an entirely new application.

Before making changes, the development agent must:

1.  Inspect the existing project.
2.  Understand its current architecture and implementation.
3.  Identify existing functionality and incomplete features.
4.  Review dependencies and build configuration.
5.  Produce an implementation plan based on the existing codebase.

During implementation, the agent must:

-   Preserve working features.
-   Reuse existing code wherever appropriate.
-   Extend the existing architecture.
-   Avoid unnecessary rewrites.
-   Not replace the project with a new template.
-   Not delete existing functionality without a clear technical reason.
-   Maintain the existing build configuration unless a change is
    necessary.
-   Implement new functionality incrementally.
-   Keep the project buildable after each major development phase.

The existing codebase must remain the foundation of the application.

## 10. Implementation Roadmap

### Phase 1 --- Existing Project Audit

-   Inspect the Android project.
-   Identify existing functionality.
-   Review architecture and dependencies.
-   Identify incomplete features.
-   Produce an implementation plan.

### Phase 2 --- Research and Protocol Architecture

-   Analyze the supplied research material.
-   Investigate manufacturer-specific protocols.
-   Identify discovery and authentication methods.
-   Build the modular protocol architecture.
-   Prepare the compatibility matrix.

### Phase 3 --- Initial Brand Integrations

Prioritize Haier, Gree, Midea, AUX, TCL, Dawlance, and Daikin. Then
expand to Orient, PEL, Panasonic, LG, Samsung, and Kenwood. Continue
expanding to additional manufacturers and compatible OEM devices.

### Phase 4 --- Automatic Discovery

-   Implement network detection.
-   Implement manufacturer-specific discovery.
-   Implement automatic device identification.
-   Implement duplicate detection.
-   Support multiple ACs.

### Phase 5 --- Authentication and Provisioning

-   Implement account authorization where needed.
-   Implement authorized automatic credential retrieval/provisioning.
-   Securely store credentials.
-   Implement automatic reconnection.

### Phase 6 --- Local Control

-   Implement protocol adapters.
-   Implement unified AC controls.
-   Verify command responses.
-   Implement device-status synchronization.

### Phase 7 --- User Interface

-   Build the elderly-friendly interface.
-   Implement device selection.
-   Implement room naming.
-   Implement simple setup and connection feedback.

### Phase 8 --- Testing and Expansion

-   Test real devices.
-   Fix discovery and communication issues.
-   Improve connection reliability.
-   Expand brand and model compatibility.
-   Maintain updated documentation.

## 11. Required Development Deliverables

The development team or AI coding agent must maintain:

-   Existing project audit report.
-   Technical architecture documentation.
-   AC manufacturer compatibility matrix.
-   Protocol integration documentation.
-   Device discovery documentation.
-   Authentication and provisioning documentation.
-   Model-level testing records.
-   Security implementation notes.
-   Known implementation issues and their fixes.
-   Development progress records.

## 12. Definition of Done

The long-term project objective is achieved when:

1.  The application automatically discovers supported ACs on the local
    Wi-Fi network.
2.  It identifies their manufacturer and model wherever possible.
3.  It performs authorized authentication and credential provisioning
    with minimal user involvement.
4.  Users do not need to manually enter technical device information.
5.  It connects to multiple AC units.
6.  Users can rename and independently manage each AC.
7.  A unified interface provides all available controls supported by
    each device.
8.  Local Wi-Fi control works reliably for integrated devices.
9.  Previously configured devices reconnect automatically.
10. The application provides an accessible, simple experience suitable
    for elderly users.
11. The architecture supports continuous expansion to additional
    manufacturers and models.

## 13. Requirements for AI Coding Agents

When using this document, an AI coding agent or developer must:

-   Treat the existing Android project as the codebase to extend.
-   Audit the project before implementation.
-   Use this document as the product requirements baseline.
-   Convert the requirements into incremental implementation tasks.
-   Preserve working functionality and existing project conventions.
-   Keep the application buildable after each major phase.
-   Update the compatibility matrix with model-level evidence.
-   Distinguish implemented, partially implemented, and verified
    functionality.
-   Avoid claiming support for a model or feature that has not been
    tested.
-   Keep technical setup out of the normal user experience wherever
    possible.
-   Do not begin a wholesale rewrite without a clear, documented
    technical reason.

## 14. Research Source and Traceability Note

The project brief refers to a Deep Research PDF covering AC brands,
local Wi-Fi discovery, authentication, local control protocols, and
compatibility in Pakistan. That PDF was not present among the files
available when this requirements document was prepared. Therefore, this
document captures the requirements and brand priorities stated in the
supplied project brief, but it does not claim to incorporate or verify
PDF-specific findings.

Before protocol implementation, the development team must review the
Deep Research PDF and add its brand-specific findings, technical
details, and recommendations to the relevant sections and compatibility
records. Do not invent research findings or treat unverified protocol
assumptions as established facts.

------------------------------------------------------------------------

## 15. Acceptance Checklist for This Specification

-   [ ] Project vision and intended automatic user journey are
    documented.
-   [ ] All 13 named priority manufacturers are included.
-   [ ] Wi-Fi discovery requirements and discovery methods are listed.
-   [ ] Authentication and authorized credential provisioning
    requirements are listed.
-   [ ] Local communication, command verification, and reconnection
    requirements are listed.
-   [ ] Essential and additional AC controls are listed.
-   [ ] Multiple-device management requirements are listed.
-   [ ] Elderly-friendly accessibility requirements are listed.
-   [ ] Modular architecture components and responsibilities are
    defined.
-   [ ] Security and privacy requirements are documented.
-   [ ] Testing scope and compatibility matrix template are included.
-   [ ] Existing Android project preservation requirements are explicit.
-   [ ] All eight implementation phases are documented.
-   [ ] Required development deliverables are listed.
-   [ ] Definition of Done is testable and explicit.
-   [ ] Missing research PDF is transparently identified rather than
    fabricated.
