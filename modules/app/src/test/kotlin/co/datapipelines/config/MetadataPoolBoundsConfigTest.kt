package co.datapipelines.config

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.io.File

/**
 * #837 (R3's pin) — the metadata pool's two shipped settings, read from the shipped `application.yml`: the pool size
 * (10) and, new, the CONNECTION-ACQUIRE bound, `connection-timeout` in milliseconds. HikariCP has no per-call acquire
 * timeout, so this one pool-wide key is the only bound on how long a scheduled job (or a request handler) waits for a
 * metadata connection; before it was written the pool's own 30 000 ms default applied, outside every statement's
 * `queryTimeout`. The numbers and env names are literals, never read back from the classes under test, so a key removed
 * from the yml, a renamed variable or a changed default is red. That an exhausted pool fails a tick's acquire at this
 * bound is `ScheduledJobsMetadataBoundsE2eTest`'s.
 */
class MetadataPoolBoundsConfigTest {
    private val shipped by lazy {
        ConfigurationReferenceInventory.yaml(
            File(ConfigurationReferenceInventory.root, "modules/app/src/main/resources/application.yml").readText(),
        )
    }
    private val defaultsEnv by lazy { File(ConfigurationReferenceInventory.root, "deploy/env/defaults.env").readText() }

    @Test
    fun `the pool size is shipped with its env name and default`() {
        shipped["spring.datasource.hikari.maximum-pool-size"] shouldBe "\${SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE:10}"
    }

    @Test
    fun `the acquire bound is shipped explicitly - ten seconds in milliseconds, with its env name`() {
        shipped["spring.datasource.hikari.connection-timeout"] shouldBe "\${SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT:10000}"
    }

    @Test
    fun `the env names are listed with the framework wiring that compose does not pass through`() {
        defaultsEnv shouldContain "SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE"
        defaultsEnv shouldContain "SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT"
    }
}
