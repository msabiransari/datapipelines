package co.datapipelines.auth

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.BindException
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource

/**
 * `datapipelines.audit.retention-days` (configuration.md §3.12, #310): the key binds, and a value
 * outside [AuditProperties.MIN_RETENTION_DAYS]..[AuditProperties.MAX_RETENTION_DAYS] refuses to
 * BIND — which is what refuses startup. A misconfigured `1` must stop the deployment, never erase
 * the trail on the first retention tick.
 *
 * Bound through Spring's [Binder] (the `ExecutorPropertiesDialectMapBindingTest` shape), not by
 * calling the constructor alone: the refusal an operator meets is the binder's, so that is the
 * one pinned.
 */
class AuditPropertiesTest {
    @Test
    fun `the key binds from its dotted path`() {
        bind("400").retentionDays shouldBe 400
    }

    @Test
    fun `an absent key binds the documented default`() {
        AuditProperties().retentionDays shouldBe 365
    }

    @Test
    fun `the floor and the ceiling themselves bind`() {
        bind("30").retentionDays shouldBe 30
        bind("3650").retentionDays shouldBe 3650
    }

    @Test
    fun `a value under the floor refuses to bind, naming the key`() {
        val refused = shouldThrow<BindException> { bind("29") }
        refused.rootCauseMessage() shouldContain "datapipelines.audit.retention-days"
        refused.rootCauseMessage() shouldContain "30"
    }

    @Test
    fun `a misconfigured one-day retention refuses to bind`() {
        shouldThrow<BindException> { bind("1") }
    }

    @Test
    fun `a value over the ceiling refuses to bind, naming the key`() {
        val refused = shouldThrow<BindException> { bind("3651") }
        refused.rootCauseMessage() shouldContain "datapipelines.audit.retention-days"
        refused.rootCauseMessage() shouldContain "3650"
    }

    private fun bind(days: String): AuditProperties =
        Binder(MapConfigurationPropertySource(mapOf("datapipelines.audit.retention-days" to days)))
            .bind("datapipelines.audit", Bindable.of(AuditProperties::class.java))
            .get()

    private fun Throwable.rootCauseMessage(): String = generateSequence(this) { it.cause }.last().message.orEmpty()
}
