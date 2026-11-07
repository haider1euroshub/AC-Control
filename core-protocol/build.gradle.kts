plugins {
    kotlin("jvm")
    `java-test-fixtures`
}

dependencies {
    implementation(project(":core-domain"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    // DriverContractTest lives in testFixtures so it is shared to driver modules
    // via testImplementation(testFixtures(project(":core-protocol"))) but is never
    // compiled into the production JAR and therefore never dexed.
    testFixturesImplementation("junit:junit:4.13.2")
    testFixturesImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testFixturesImplementation(project(":core-domain"))
    testFixturesImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

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
