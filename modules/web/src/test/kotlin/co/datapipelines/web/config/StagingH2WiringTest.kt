package co.datapipelines.web.config

import co.datapipelines.staging.H2StagingFactory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.BindException
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource

/**
 * `datapipelines.staging.h2.max-connections` through every layer it crosses (146 / #118,
 * configuration.md §3.3): Spring binding into [StagingH2Properties], its startup validation,
 * and [DomainConfiguration.stagingFactory]'s mapping into the staging module's own
 * `H2StagingProperties` — the one hop the spec's "verified premises" name as the place a
 * knob silently disconnects. A field added to the staging class alone leaves the operator's
 * setting unread; this test fails on exactly that omission.
 */
class StagingH2WiringTest {
    @Test
    fun `the default binds to four and reaches the factory`() {
        val bound = bind(emptyMap())
        bound.maxConnections shouldBe 4
        factoryFor(bound).properties.maxConnections shouldBe 4
    }

    @Test
    fun `an override binds and reaches the factory unchanged, with the adjacent settings intact`() {
        val bound =
            bind(
                mapOf(
                    "datapipelines.staging.h2.max-connections" to "2",
                    "datapipelines.staging.h2.max-memory-mb" to "512",
                    "datapipelines.staging.h2.mode" to "REGULAR",
                ),
            )
        val effective = factoryFor(bound).properties
        effective.maxConnections shouldBe 2
        // The mapping copies every field, not just the new one — a hand-written constructor
        // call is where a sibling setting quietly stops travelling.
        effective.maxMemoryMb shouldBe 512
        effective.mode shouldBe "REGULAR"
        effective.insertBatchSize shouldBe bound.insertBatchSize
        effective.resultBatchSize shouldBe bound.resultBatchSize
        effective.queryTimeoutSeconds shouldBe bound.queryTimeoutSeconds
    }

    @Test
    fun `one connection is a legal cap`() {
        factoryFor(bind(mapOf("datapipelines.staging.h2.max-connections" to "1"))).properties.maxConnections shouldBe 1
    }

    @Test
    fun `zero and negative caps are refused at binding time, naming the key`() {
        listOf("0", "-4").forEach { value ->
            val thrown = shouldThrow<BindException> { bind(mapOf("datapipelines.staging.h2.max-connections" to value)) }
            generateSequence<Throwable>(thrown) { it.cause }.joinToString { it.message.orEmpty() } shouldContain "max-connections"
        }
    }

    private fun bind(properties: Map<String, String>): StagingH2Properties =
        Binder(MapConfigurationPropertySource(properties))
            .bind("datapipelines.staging.h2", Bindable.of(StagingH2Properties::class.java))
            .orElseGet { StagingH2Properties() }

    private fun factoryFor(properties: StagingH2Properties): H2StagingFactory =
        DomainConfiguration().stagingFactory(properties) as H2StagingFactory
}
