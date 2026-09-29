package co.datapipelines.auth

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.BindException
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource

/**
 * The `datapipelines.persistence.*` keys as the application binds them (configuration.md §3.32):
 * through Spring's [Binder], so a bound that cannot hold refuses the BOOT — naming the key and the
 * reason — rather than a writer failing later.
 */
class PersistencePropertiesTest {
    @Test
    fun `batch-max-events above 7000 refuses to bind, naming the replay log's Lua unpack limit`() {
        // #266b (the security pass's observation 4). Red before the bound existed: 7001 bound.
        bind("batch-max-events" to "7000").batchMaxEvents shouldBe PersistenceProperties.MAX_BATCH_MAX_EVENTS
        val refusal = shouldThrow<BindException> { bind("batch-max-events" to "7001") }
        generateSequence<Throwable>(refusal) { it.cause }.last().message.orEmpty().run {
            this shouldContain "datapipelines.persistence.batch-max-events must be <= 7000"
            this shouldContain "unpack"
        }
    }

    @Test
    fun `shutdown-drain-ms above 25000 refuses to bind - the drain must fit Spring's 30 s shutdown phase`() {
        bind("shutdown-drain-ms" to "25000").shutdownDrainMs shouldBe PersistenceProperties.MAX_SHUTDOWN_DRAIN_MS
        val refusal = shouldThrow<BindException> { bind("shutdown-drain-ms" to "25001") }
        generateSequence<Throwable>(refusal) { it.cause }.last().message.orEmpty() shouldContain "shutdown-drain-ms must be <= 25000"
    }

    @Test
    fun `audit-enabled binds from its key and ships false - the audit writer is off unless switched on`() {
        bind().audit.enabled shouldBe false
        bind("audit.enabled" to "true").audit.enabled shouldBe true
        bind("audit.enabled" to "true").enabled shouldBe true
    }

    private fun bind(vararg keys: Pair<String, String>): PersistenceProperties =
        Binder(MapConfigurationPropertySource(keys.associate { (k, v) -> "datapipelines.persistence.$k" to v }))
            .bindOrCreate("datapipelines.persistence", PersistenceProperties::class.java)
}
