plugins {
    kotlin("jvm")
}

// Network device scanner: discovers and classifies other devices on the phone's LAN (mDNS, SSDP, subnet sweep).
// Pure JVM so every discovery source is unit-testable with fakes; the Android pieces (NsdManager, multicast
// lock) live in the app's netscan package.
dependencies {
    api(project(":core-protocol"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

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
