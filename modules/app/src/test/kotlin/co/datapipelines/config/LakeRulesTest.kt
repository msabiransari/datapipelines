package co.datapipelines.config

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.EnumerablePropertySource
import org.springframework.core.io.FileSystemResource
import java.io.File

/**
 * §7 / §3.36 (#785) — the managed lake's keys at boot. A disabled lake is never judged; an enabled one with the
 * shipped defaults needs exactly a bucket and a region or an endpoint; each record rule (§6 `LakeRules`) refuses
 * at its boundary, naming the key; no violation and no snapshot ever carries a credential's value.
 */
class LakeRulesTest {
    private val shipped: Map<String, String?> by lazy { LakeRules.KEYS.associateWith { defaultOf(it) } }

    private fun violations(overrides: Map<String, String?>): List<String> =
        ConfigValidator.validate(ConfigSnapshots.valid().copy(lake = shipped + overrides)).violations

    private fun enabled(vararg overrides: Pair<String, String?>): List<String> =
        violations(mapOf(LakeRules.ENABLED to "true", LakeRules.BUCKET to "dp-lake", LakeRules.REGION to "eu-west-1") + overrides)

    @Test
    fun `every key has a shipped default, and a disabled lake is never judged`() {
        shipped.filterValues { it == null }.keys shouldContainExactly emptyList()
        violations(emptyMap()) shouldContainExactly emptyList()
        // Disabled with nonsense everywhere: still nothing — a deployment without a lake is not refused for its keys.
        violations(LakeRules.KEYS.filter { it != LakeRules.ENABLED }.associateWith { "nonsense" }) shouldContainExactly emptyList()
    }

    @Test
    fun `enabled with the shipped defaults needs a bucket and a region or an endpoint`() {
        enabled() shouldContainExactly emptyList()
        enabled(LakeRules.REGION to "", LakeRules.ENDPOINT to "http://minio:9000") shouldContainExactly emptyList()
        enabled(LakeRules.BUCKET to "").single() shouldStartWith LakeRules.BUCKET
        enabled(LakeRules.REGION to "").single() shouldStartWith "${LakeRules.REGION} or ${LakeRules.ENDPOINT}"
    }

    @Test
    fun `kind, mode, name and endpoint are refused outside their values`() {
        enabled(LakeRules.STORAGE_KIND to "file").single() shouldStartWith LakeRules.STORAGE_KIND
        enabled(LakeRules.MODE to "paused").single() shouldStartWith LakeRules.MODE
        enabled(LakeRules.MODE to "restoring") shouldContainExactly emptyList()
        enabled(LakeRules.NAME to "My-Lake").single() shouldStartWith LakeRules.NAME
        enabled(LakeRules.ENDPOINT to "minio:9000").single() shouldStartWith LakeRules.ENDPOINT
        enabled(LakeRules.ENDPOINT to "ftp://minio:9000").single() shouldStartWith LakeRules.ENDPOINT
        // A credential, query or fragment in the URL is refused: the endpoint is a host, never a place for a secret.
        enabled(LakeRules.ENDPOINT to "https://user:pw@minio:9000").single() shouldStartWith LakeRules.ENDPOINT
        enabled(LakeRules.ENDPOINT to "http://minio:9000/?x=1").single() shouldStartWith LakeRules.ENDPOINT
        enabled(LakeRules.ENDPOINT to "http://minio:9000/#frag").single() shouldStartWith LakeRules.ENDPOINT
        enabled(LakeRules.ENDPOINT to "https://s3.example.net/") shouldContainExactly emptyList()
    }

    @Test
    fun `a credential pair is whole or absent, and no violation or snapshot carries its value`() {
        val secret = "s3cr3t-value-never-printed"
        val half = enabled(LakeRules.SECRET_KEY to LakeRules.carried(LakeRules.SECRET_KEY, secret))
        half.single() shouldStartWith "${LakeRules.ACCESS_KEY} and ${LakeRules.SECRET_KEY}"
        enabled(LakeRules.READER_ACCESS_KEY to LakeRules.PRESENT).single() shouldStartWith LakeRules.READER_ACCESS_KEY
        enabled(LakeRules.ACCESS_KEY to LakeRules.PRESENT, LakeRules.SECRET_KEY to LakeRules.PRESENT) shouldContainExactly emptyList()

        LakeRules.CREDENTIAL_KEYS.forEach { key ->
            LakeRules.carried(key, secret) shouldBe LakeRules.PRESENT
            LakeRules.carried(key, "  ") shouldBe ""
            LakeRules.carried(key, null) shouldBe ""
        }
        LakeRules.carried(LakeRules.BUCKET, "dp-lake") shouldBe "dp-lake"
        // The snapshot as ConfigValidator.snapshotFrom builds it: every credential set to the secret, the rest shipped.
        val carried = LakeRules.KEYS.associateWith { LakeRules.carried(it, if (it in LakeRules.CREDENTIAL_KEYS) secret else shipped[it]) }
        LakeRules.CREDENTIAL_KEYS.size shouldBe 4
        val snapshot = ConfigSnapshots.valid().copy(lake = carried)
        snapshot.toString() shouldContain "${LakeRules.SECRET_KEY}=${LakeRules.PRESENT}"
        snapshot.toString() shouldNotContain secret
        half.joinToString() shouldNotContain secret
    }

    @Test
    fun `the orphan grace must exceed job timeout plus statement timeout plus the skew hour`() {
        // Shipped: 72 h × 3600 = 259 200 > 2 h + 30 s + 3600 = 10 830.
        // Boundary: job 2 h + statement 30 s + 3600 s = 10 830 s; 3 h = 10 800 ≤ 10 830 → refused, 4 h passes.
        enabled(LakeRules.ORPHAN_GRACE_HOURS to "3").single() shouldStartWith "${LakeRules.ORPHAN_GRACE_HOURS} × 3600 (10800)"
        enabled(LakeRules.ORPHAN_GRACE_HOURS to "4") shouldContainExactly emptyList()
        enabled(LakeRules.ORPHAN_GRACE_HOURS to "4", LakeRules.JOB_TIMEOUT to "3h").single() shouldStartWith
            LakeRules.ORPHAN_GRACE_HOURS
        enabled(LakeRules.PROTECTION_DAYS to "0").single() shouldStartWith LakeRules.PROTECTION_DAYS
        enabled(LakeRules.JOB_TIMEOUT to "soon").single() shouldStartWith LakeRules.JOB_TIMEOUT
    }

    @Test
    fun `the lease wait stays under the node query ceiling and a delivery's staging fits the scratch budget`() {
        enabled(LakeRules.LEASE_WAIT_SECONDS to "900").single() shouldStartWith LakeRules.LEASE_WAIT_SECONDS
        enabled(LakeRules.LEASE_WAIT_SECONDS to "899") shouldContainExactly emptyList()
        // Shipped 2GB × 3 = 6GB ≤ 20GB; 7GB × 3 = 21GB > 20GB refuses; 6GB × 3 = 18GB passes.
        enabled(LakeRules.MAX_DELIVERY_BYTES to "7GB").single() shouldStartWith LakeRules.MAX_DELIVERY_BYTES
        enabled(LakeRules.MAX_DELIVERY_BYTES to "6GB") shouldContainExactly emptyList()
        enabled(LakeRules.SCRATCH_BYTES to "lots").single() shouldStartWith LakeRules.SCRATCH_BYTES
    }

    @Test
    fun `every number is positive and the maintenance window is HH_MM-HH_MM`() {
        val positives =
            listOf(
                LakeRules.MAX_CONCURRENT_INGESTIONS,
                LakeRules.MAX_CONCURRENT_MAINTENANCE,
                LakeRules.MAX_OPEN_PARTITIONS,
                LakeRules.LOCK_TIMEOUT_SECONDS,
                LakeRules.MAX_GENERATION_AGE_SECONDS,
                LakeRules.FRESHNESS_TICK_SECONDS,
            )
        positives.forEach { key -> enabled(key to "0").single() shouldStartWith key }
        enabled(LakeRules.STAGING_MEMORY_LIMIT to "0MB").single() shouldStartWith LakeRules.STAGING_MEMORY_LIMIT
        enabled(LakeRules.MAX_BYTES_REWRITTEN to "-1").single() shouldStartWith LakeRules.MAX_BYTES_REWRITTEN
        enabled(LakeRules.MAINTENANCE_WINDOW to "2am-5am").single() shouldStartWith LakeRules.MAINTENANCE_WINDOW
        enabled(LakeRules.MAINTENANCE_WINDOW to "22:30-01:15") shouldContainExactly emptyList()
        // A statement timeout of zero fails positivity, and nothing downstream divides by it.
        enabled(LakeRules.STATEMENT_TIMEOUT_SECONDS to "0") shouldHaveSize 1
    }

    private fun defaultOf(key: String): String? {
        val raw = yaml[key]?.toString() ?: return null
        return PLACEHOLDER_DEFAULT.find(raw)?.groupValues?.get(1) ?: raw
    }

    private val yaml: Map<String, Any?> by lazy {
        val file = generateSequence(File(".").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
        YamlPropertySourceLoader()
            .load("application.yml", FileSystemResource(File(file, "modules/app/src/main/resources/application.yml")))
            .filterIsInstance<EnumerablePropertySource<*>>()
            .flatMap { source -> source.propertyNames.map { it to source.getProperty(it) } }
            .toMap()
    }

    private companion object {
        val PLACEHOLDER_DEFAULT = Regex("""^\$\{[A-Z0-9_]+:(.*)}$""")
    }
}
