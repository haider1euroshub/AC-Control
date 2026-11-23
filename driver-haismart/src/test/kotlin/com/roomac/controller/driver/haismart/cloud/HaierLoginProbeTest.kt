package com.roomac.controller.driver.haismart.cloud

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Properties

/**
 * Opt-in diagnostic (HAIER_LOGIN_PROBE=1). Signs in to Haier's real login endpoint with a made-up
 * account on the reserved example.invalid domain, once with the configured app identity and once
 * with a deliberately wrong app key, and prints only the service's retCode/retInfo. No real account,
 * password or token is involved. Used to tell "account/region refused" from "app identity refused".
 */
class HaierLoginProbeTest {
    @Test
    fun `probe login reply codes with a made-up account`() {
        assumeTrue(System.getenv("HAIER_LOGIN_PROBE") == "1")
        val identity = configuredIdentity()
        assumeTrue(identity.isConfigured)
        val user = "roomac-probe-${System.currentTimeMillis()}@example.invalid"
        fun attempt(label: String, id: HaierAppIdentity) {
            val outcome = try {
                HaierCloudClient(id, HaierCloudClient.newClientId(), "92").login(user, "not-a-real-password")
                "unexpected success"
            } catch (e: HaierCloudException) {
                "${e.kind}: ${e.message}"
            }
            println("probe [$label]: $outcome")
        }
        attempt("configured identity", identity)
        attempt("wrong app key", identity.copy(appKey = "0".repeat(32)))
    }
}

/** The app identity from the environment, else from the project's local.properties (never printed). */
internal fun configuredIdentity(): HaierAppIdentity {
    val env = HaierAppIdentity(System.getenv("HAIER_APP_ID") ?: "", System.getenv("HAIER_APP_KEY") ?: "")
    if (env.isConfigured) return env
    val file = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "local.properties") }.firstOrNull { it.exists() }
        ?: return env
    val p = Properties().apply { file.inputStream().use { load(it) } }
    return HaierAppIdentity(p.getProperty("haier.appId") ?: "", p.getProperty("haier.appKey") ?: "")
}
