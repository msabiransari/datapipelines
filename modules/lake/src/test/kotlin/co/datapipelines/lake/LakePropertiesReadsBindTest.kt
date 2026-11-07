package co.datapipelines.lake

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.boot.context.properties.bind.BindException
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import org.springframework.util.unit.DataSize

/**
 * #818 (dp-lake P5, configuration.md §3.36): the `reads.*` keys bind the way the app's binder binds them, and a
 * value the read side cannot run with fails the bind itself. `LakeRules.checkLakeReads` judges the same keys at
 * boot with the cross-key rule (age + retire ceiling + tick within the protection hour). This class covers the
 * per-group rules only, the orchestrator's tick-below-age addition among them.
 */
class LakePropertiesReadsBindTest {
    private fun bind(vararg pairs: Pair<String, String>): LakeProperties =
        Binder(MapConfigurationPropertySource(mapOf(*pairs).mapKeys { "${LakeProperties.PREFIX}.${it.key}" }))
            .bindOrCreate(LakeProperties.PREFIX, LakeProperties::class.java)

    @Test
    fun `the shipped reads defaults - 900 s age, 60 s tick, 512MB reader memory`() {
        val reads = bind().reads
        assertAll(
            { reads.maxGenerationAgeSeconds shouldBe 900L },
            { reads.freshnessTickSeconds shouldBe 60L },
            { reads.memoryLimit shouldBe DataSize.ofMegabytes(512) },
        )
    }

    @Test
    fun `memory-limit binds a DuckDB-style size`() {
        bind("reads.memory-limit" to "2GB").reads.memoryLimit shouldBe DataSize.ofGigabytes(2)
    }

    @Test
    fun `a tick at or above the age bound fails the bind - 899 below 900 binds`() {
        bind("reads.freshness-tick-seconds" to "899").reads.freshnessTickSeconds shouldBe 899L
        val refused = shouldThrow<BindException> { bind("reads.freshness-tick-seconds" to "900") }
        rootMessage(refused) shouldContain "reads.freshness-tick-seconds (900) must be below"
    }

    @Test
    fun `non-positive values fail the bind`() {
        assertAll(
            {
                rootMessage(shouldThrow<BindException> { bind("reads.max-generation-age-seconds" to "0") }) shouldContain
                    "max-generation-age-seconds must be > 0"
            },
            {
                rootMessage(shouldThrow<BindException> { bind("reads.freshness-tick-seconds" to "-1") }) shouldContain
                    "freshness-tick-seconds must be > 0"
            },
            {
                rootMessage(shouldThrow<BindException> { bind("reads.memory-limit" to "0MB") }) shouldContain
                    "memory-limit must be a positive size"
            },
        )
    }

    private fun rootMessage(e: Throwable): String = generateSequence(e) { it.cause }.last().message.orEmpty()
}
