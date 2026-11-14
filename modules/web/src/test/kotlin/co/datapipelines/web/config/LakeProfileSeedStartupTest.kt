package co.datapipelines.web.config

import co.datapipelines.lake.LakeBootCheck
import co.datapipelines.lake.profiles.LakeProfileSeed
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.StaticListableBeanFactory

/**
 * The web side of the `production` profile's seed (#956 D3): [LakeProfileSeedStartup] calls [LakeProfileSeed] when
 * `lake`'s auto-configuration declared one, lets its refusal stop the boot, and does nothing with the lake off. The
 * seed's own behaviour (once per database, never over an edit, two starts together) is `LakeProfileSeedIntegrationTest`.
 */
class LakeProfileSeedStartupTest {
    private val catalogCheck = LakeCatalogStartupCheck(StaticListableBeanFactory().getBeanProvider(LakeBootCheck::class.java))

    @Test
    fun `with the lake disabled there is no seed and startup proceeds`() {
        val startup = LakeProfileSeedStartup(StaticListableBeanFactory().getBeanProvider(LakeProfileSeed::class.java), catalogCheck)

        shouldNotThrowAny { startup.seedProduction() }
    }

    @Test
    fun `with the lake enabled the seed runs once`() {
        val seed = mockk<LakeProfileSeed> { every { seed() } returns true }

        LakeProfileSeedStartup(providerOf(seed), catalogCheck).seedProduction()

        verify(exactly = 1) { seed.seed() }
    }

    @Test
    fun `a refused seed refuses startup unchanged`() {
        val refusal = IllegalStateException("lake.profile.invalid")
        val seed = mockk<LakeProfileSeed> { every { seed() } throws refusal }

        val thrown = shouldThrow<IllegalStateException> { LakeProfileSeedStartup(providerOf(seed), catalogCheck).seedProduction() }

        thrown shouldBe refusal
    }

    private fun providerOf(seed: LakeProfileSeed) =
        StaticListableBeanFactory(mapOf("lakeProfileSeed" to seed)).getBeanProvider(LakeProfileSeed::class.java)
}
