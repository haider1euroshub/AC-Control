import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The HaiSmart app identity Haier's account service requires (docs/BUILD_AND_INSTALL.md) is read from the
// untracked local.properties, so it is never committed. Without it the build works but Haier sign-in is off.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun buildConfigString(value: String?) = "\"" + (value ?: "").replace("\\", "\\\\").replace("\"", "\\\"") + "\""

// Release signing is read from an untracked keystore.properties (see docs/BUILD_AND_INSTALL.md).
// Without it, assembleRelease produces an unsigned APK and nothing secret is ever in the repository.
val signingProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.roomac.controller"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.roomac.controller"
        minSdk = 26
        targetSdk = 34
        versionCode = 8
        versionName = "2.6.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "HAIER_APP_ID", buildConfigString(localProps.getProperty("haier.appId")))
        buildConfigField("String", "HAIER_APP_KEY", buildConfigString(localProps.getProperty("haier.appKey")))
        // In-app HaiSmart account creation (feature flag). Default off: keeps the "create it in HaiSmart"
        // fallback until the captured flow (docs/HAIER_SIGNUP_BIND_ENDPOINTS.md) is proven in the field.
        buildConfigField("boolean", "HAIER_IN_APP_SIGNUP", (localProps.getProperty("roomac.haier.inAppSignup") ?: "false"))
        // NetHome Plus app key for Midea V3 token retrieval (same handling as the HaiSmart identity). Blank = Midea V3 sign-in unavailable.
        buildConfigField("String", "MIDEA_APP_KEY", buildConfigString(localProps.getProperty("midea.appKey")))
        // "Network devices" LAN scanner (feature flag). Default off; enable with roomac.networkScanner=true in local.properties.
        buildConfigField("boolean", "NETWORK_SCANNER", (localProps.getProperty("roomac.networkScanner") ?: "false"))
        // Local control of TVs, cameras and scanners found by the scanner (feature flag; needs NETWORK_SCANNER too).
        // Default off. Not yet tested on real devices. Enable with roomac.deviceControl=true in local.properties.
        buildConfigField("boolean", "DEVICE_CONTROL", (localProps.getProperty("roomac.deviceControl") ?: "false"))
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        if (signingProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isDebuggable = true
        }
        release {
            // The Haier and Midea sign-ins send the vendor apps' own app identities. requirement.md §1.4(8)
            // and §4.3 forbid impersonating a vendor app, so release builds leave them out (sign-in shows as
            // unavailable; local control is unaffected) unless an authorised owner opts in explicitly.
            // See docs/HAISMART_AUTH_INVESTIGATION.md "Route status".
            val vendorIdentityOptIn = localProps.getProperty("roomac.release.vendorAppIdentity") == "true"
            buildConfigField("boolean", "VENDOR_IDENTITY_OPT_IN", vendorIdentityOptIn.toString())
            if (!vendorIdentityOptIn) {
                buildConfigField("String", "HAIER_APP_ID", "\"\"")
                buildConfigField("String", "HAIER_APP_KEY", "\"\"")
                buildConfigField("String", "MIDEA_APP_KEY", "\"\"")
                // In-app sign-up signs with the vendor identity, so it is off whenever that identity is.
                buildConfigField("boolean", "HAIER_IN_APP_SIGNUP", "false")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkDependencies = true
        // Version-bump suggestions are informational and depend on the date the build runs.
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable", "OldTargetApi")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":driver-haismart"))
    implementation(project(":driver-gree"))
    implementation(project(":driver-midea"))
    implementation(project(":core-netscan"))
    implementation(project(":core-devicecontrol"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-process:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.browser:browser:1.8.0") // Custom Tabs for official manufacturer sign-up pages
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // real JSON for JVM tests; Android's is a stub
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
