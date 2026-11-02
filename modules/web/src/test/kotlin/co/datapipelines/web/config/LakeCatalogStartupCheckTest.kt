package co.datapipelines.web.config

import co.datapipelines.lake.LakeBootCheck
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.string.shouldStartWith
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.StaticListableBeanFactory

/**
 * The web side of the managed lake's boot check (#785 A6): [LakeCatalogStartupCheck] runs [LakeBootCheck] when
 * `lake`'s auto-configuration declared one (`datapipelines.lake.enabled=true`), lets its refusal stop the boot
 * unchanged, and does nothing when the lake is off — the module is inert when disabled. The checks themselves
 * are `lake`'s suites (`LakeBootCheckIntegrationTest`, `LakeBootCheckTest`).
 */
class LakeCatalogStartupCheckTest {
    @Test
    fun `with the lake disabled there is no boot check and startup proceeds`() {
        val check = LakeCatalogStartupCheck(StaticListableBeanFactory().getBeanProvider(LakeBootCheck::class.java))

        shouldNotThrowAny { check.check() }
    }

    @Test
    fun `with the lake enabled the boot check runs once`() {
        val bootCheck = mockk<LakeBootCheck> { every { verify() } just runs }

        LakeCatalogStartupCheck(providerOf(bootCheck)).check()

        verify(exactly = 1) { bootCheck.verify() }
    }

    @Test
    fun `a refused boot check refuses startup with its catalogued id leading the message`() {
        val refusal = IllegalStateException("lake.boot.trigger_missing: a lake catalog trigger is missing or disabled")
        val bootCheck = mockk<LakeBootCheck> { every { verify() } throws refusal }

        val thrown = shouldThrow<IllegalStateException> { LakeCatalogStartupCheck(providerOf(bootCheck)).check() }

        thrown.message.orEmpty() shouldStartWith "lake.boot.trigger_missing: "
    }

    private fun providerOf(bootCheck: LakeBootCheck) =
        StaticListableBeanFactory(mapOf("lakeBootCheck" to bootCheck)).getBeanProvider(LakeBootCheck::class.java)
}
