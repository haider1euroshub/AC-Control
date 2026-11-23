plugins {
    kotlin("jvm")
}

// Local control of TVs, cameras and scanners found by the network scanner (feature flag DEVICE_CONTROL).
// Pure JVM: every protocol adapter is unit-tested against fake transports. NOT YET TESTED ON REAL DEVICES.
dependencies {
    api(project(":core-netscan"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    // JSON tree API only (Google Cast payloads); same version driver-gree uses.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
