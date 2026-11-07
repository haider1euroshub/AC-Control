# BUILD AND INSTALLATION GUIDE

## 1. Prerequisites

- **Host OS:** Windows 11 (or macOS / Linux)
- **Java Development Kit:** Amazon Corretto JDK 17 (extracted at `.tools/jdk17.0.21_0/`)
- **Android SDK:** Android API Level 34 and Build-Tools 34.0.0 (installed at `.tools/android-sdk/`)
- **Gradle:** Gradle 8.9 (managed via the included `gradlew.bat` / `gradlew` wrapper)

---

## 2. Environment Setup

From PowerShell in the project root:

```powershell
# Set JAVA_HOME to the project's local JDK 17
$env:JAVA_HOME = "$PWD\.tools\jdk17.0.21_0"

# Verify Gradle Wrapper and JDK
.\gradlew.bat --version
```

---

## 3. Running Automated Tests

Run unit tests across all 6 modules:

```powershell
$env:JAVA_HOME = "$PWD\.tools\jdk17.0.21_0"
.\gradlew.bat test
```

Or test specific modules:
```powershell
.\gradlew.bat :core-domain:test
.\gradlew.bat :core-protocol:test
.\gradlew.bat :driver-smartair2:test
.\gradlew.bat :driver-haismart:test
.\gradlew.bat :driver-hon:test
.\gradlew.bat :app:testDebugUnitTest
```

---

## 4. Building the Application (APKs)

### Build Debug APK (Includes In-App Simulator)
```powershell
$env:JAVA_HOME = "$PWD\.tools\jdk17.0.21_0"
.\gradlew.bat :app:assembleDebug
```
The output APK is generated at:
`app/build/outputs/apk/debug/app-debug.apk`

### Build Release APK (Strictly Excludes Simulator)
```powershell
$env:JAVA_HOME = "$PWD\.tools\jdk17.0.21_0"
.\gradlew.bat :app:assembleRelease
```
The output APK is generated at:
`app/build/outputs/apk/release/app-release-unsigned.apk`

---

## 5. Installing the APK on Android Device

### Option A: Via ADB (USB Debugging)
```powershell
adb install -r dist\room-ac-controller-v1.0.0-debug.apk
```

### Option B: Sideloading via USB File Transfer / WhatsApp / Google Drive
1. Copy `room-ac-controller-v1.0.0-debug.apk` from `dist/` to your phone's *Downloads* folder.
2. On your Android phone, open the Files app.
3. Tap the APK file and select **Install**.
4. If prompted, enable "Allow installation of unknown apps".

