package co.datapipelines.web.config

import co.datapipelines.lake.LakeProperties
import co.datapipelines.staging.DuckDbStaging
import co.datapipelines.staging.DuckDbStagingProperties
import co.datapipelines.staging.EngineDispatchingStagingFactory
import co.datapipelines.staging.H2Staging
import co.datapipelines.typesystem.StagingEngine
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.context.properties.bind.BindException
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import org.springframework.core.env.AbstractEnvironment
import org.springframework.core.env.MapPropertySource
import org.springframework.util.unit.DataSize
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * The DuckDB half of `DomainConfiguration.stagingFactory` (staging §8.5, #795): the bounds come from
 * `datapipelines.lake.admission.*` — bound whether or not the lake is enabled — and the engine-neutral
 * knobs from `datapipelines.staging.h2.*`. Each field is asserted at the factory, because the
 * hand-written copy into `DuckDbStagingProperties` is the hop a new knob silently misses (the same
 * reason `StagingH2WiringTest` exists).
 */
class StagingDuckDbWiringTest {
    @TempDir
    lateinit var scratch: Path

    @Test
    fun `with nothing configured the DuckDB factory gets the admission defaults and the tmpdir scratch directory`() {
        val factory = bean(emptyMap())
        val duck = factory.duckDb.properties
        duck.memoryLimitBytes shouldBe GIB
        duck.expansionFactor shouldBe 3
        duck.scratchCapacityBytes shouldBe 20 * GIB
        duck.defaultReservationBytes shouldBe 3 * GIB
        duck.scratchDir shouldBe Path.of(System.getProperty("java.io.tmpdir"), "dp-staging").toAbsolutePath()
        duck.threads shouldBe DuckDbStagingProperties.boundedThreads()
        factory.duckDb.budget.capacityBytes shouldBe 20 * GIB
        // The engine-neutral knobs are the H2 binding's defaults.
        val h2 = StagingH2Properties()
        duck.insertBatchSize shouldBe h2.insertBatchSize
        duck.resultBatchSize shouldBe h2.resultBatchSize
        duck.queryTimeoutSeconds shouldBe h2.queryTimeoutSeconds
        duck.maxConnections shouldBe h2.maxConnections
    }

    @Test
    fun `admission and staging overrides reach the DuckDB factory with the lake disabled`() {
        val factory =
            bean(
                mapOf(
                    "datapipelines.lake.enabled" to "false",
                    "datapipelines.lake.admission.staging-memory-limit" to "256MB",
                    "datapipelines.lake.admission.staging-expansion-factor" to "2",
                    "datapipelines.lake.admission.scratch-bytes" to "2GB",
                    "datapipelines.staging.h2.insert-batch-size" to "500",
                    "datapipelines.staging.h2.result-batch-size" to "100",
                    "datapipelines.staging.h2.query-timeout-seconds" to "30",
                    "datapipelines.staging.h2.max-connections" to "2",
                ),
            )
        val duck = factory.duckDb.properties
        duck.memoryLimitBytes shouldBe 256 * MIB
        duck.expansionFactor shouldBe 2
        duck.scratchCapacityBytes shouldBe 2 * GIB
        duck.defaultReservationBytes shouldBe 512 * MIB
        factory.duckDb.budget.capacityBytes shouldBe 2 * GIB
        duck.insertBatchSize shouldBe 500
        duck.resultBatchSize shouldBe 100
        duck.queryTimeoutSeconds shouldBe 30
        duck.maxConnections shouldBe 2
        // The H2 delegate is untouched by the admission keys.
        factory.h2.properties.maxMemoryMb shouldBe StagingH2Properties().maxMemoryMb
    }

    @Test
    fun `an admission value DuckDB cannot open with fails the bean, naming the bound`() {
        val thrown = shouldThrow<IllegalArgumentException> { bean(mapOf("datapipelines.lake.admission.staging-memory-limit" to "512KB")) }
        thrown.message.orEmpty() shouldContain "memoryLimitBytes"
    }

    /**
     * #804 R1 — the binder's domain for the factor, both sides of its upper boundary, at the bean's own prefix
     * ([LAKE_ADMISSION_PREFIX]) and target (`LakeProperties.Admission`). The validator side of the same two values is
     * `LakeRulesTest` (app): it admits `2147483647` and names the key at `2147483648`, so no value the §7 report
     * admits reaches this bind failure.
     */
    @Test
    fun `804 R1 - the expansion factor binds up to Int MAX_VALUE and fails the binder one past it`() {
        admission(mapOf("$LAKE_ADMISSION_PREFIX.staging-expansion-factor" to "2147483647")).stagingExpansionFactor shouldBe
            Int.MAX_VALUE
        val failure =
            shouldThrow<BindException> { admission(mapOf("$LAKE_ADMISSION_PREFIX.staging-expansion-factor" to "2147483648")) }
        generateSequence<Throwable>(failure) { it.cause }.any { it is NumberFormatException } shouldBe true
    }

    /**
     * #804 R1 sweep — every size form `LakeRulesTest.ADMITTED_SIZES` admits for `staging-memory-limit` and
     * `scratch-bytes` binds to the consumer's `DataSize`, to the bytes `DataSize.parse` reads (the validator's parser).
     */
    @Test
    fun `804 R1 - every size form the validator admits binds to the staging bean's DataSize`() {
        ADMITTED_SIZES.forEach { size ->
            val bound =
                admission(
                    mapOf(
                        "$LAKE_ADMISSION_PREFIX.staging-memory-limit" to size,
                        "$LAKE_ADMISSION_PREFIX.scratch-bytes" to size,
                    ),
                )
            val expected = DataSize.parse(size.trim()).toBytes()
            bound.stagingMemoryLimit.toBytes() shouldBe expected
            bound.scratchBytes.toBytes() shouldBe expected
        }
    }

    @Test
    fun `each engine is built by its own delegate, and a DuckDB database leaves nothing behind`() {
        val factory = stagingFactoryFor(StagingH2Properties(), LakeProperties.Admission(), scratch)
        val h2 = factory.create(UUID.randomUUID(), StagingEngine.H2)
        h2.shouldBeInstanceOf<H2Staging>()
        h2.close()
        val duck = factory.create(UUID.randomUUID(), StagingEngine.DUCKDB)
        duck.shouldBeInstanceOf<DuckDbStaging>()
        duck.reservationBytes shouldBe 3 * GIB
        duck.close()
        // Nothing of the database is left; the factory's owner lock file stays for the process's life (#803).
        Files.list(scratch).use { s -> s.filter { !it.fileName.toString().startsWith("dp-owner_") }.toList() }.shouldBeEmpty()
        factory.duckDb.budget.reservedBytes shouldBe 0L
    }

    /**
     * The `@Bean` over [properties] and nothing else (no OS environment): `StagingH2Properties` bound
     * from them as Spring binds it, the admission keys read by the bean itself.
     */
    private fun bean(properties: Map<String, String>): EngineDispatchingStagingFactory {
        val h2 =
            Binder(MapConfigurationPropertySource(properties))
                .bind("datapipelines.staging.h2", Bindable.of(StagingH2Properties::class.java))
                .orElseGet { StagingH2Properties() }
        val environment = object : AbstractEnvironment() {}
        environment.propertySources.addFirst(MapPropertySource("test", properties))
        return DomainConfiguration().stagingFactory(h2, environment) as EngineDispatchingStagingFactory
    }

    /** The bean's own binding: [LAKE_ADMISSION_PREFIX] onto `LakeProperties.Admission`, as `stagingFactory` does. */
    private fun admission(properties: Map<String, String>): LakeProperties.Admission {
        val environment = object : AbstractEnvironment() {}
        environment.propertySources.addFirst(MapPropertySource("test", properties))
        return Binder
            .get(environment)
            .bind(LAKE_ADMISSION_PREFIX, Bindable.of(LakeProperties.Admission::class.java))
            .orElseGet { LakeProperties.Admission() }
    }

    private companion object {
        const val MIB = 1L shl 20
        const val GIB = 1L shl 30

        /** #804 R1 — `LakeRulesTest.ADMITTED_SIZES` (app), verbatim: the forms the §7 report admits. */
        val ADMITTED_SIZES =
            listOf("1048576", "1048576B", "1024KB", "1MB", "+1MB", " 2GB ", "20GB", "1TB", "9223372036854775807B", "8388607TB")
    }
}
