package co.datapipelines.datasources

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * [DatasourceProperties.fromRaw] — the parse that turns a raw `properties` object (request JSON,
 * or `properties_json` off the row) into the two §5 namespaces plus the evidence validation needs.
 *
 * This parse is the only place an unknown or malformed namespace can be *detected*: a typed model
 * with two fields would silently drop `{"hikkari": {...}}`, and the save would succeed having
 * quietly ignored everything the operator wrote. Previously it was covered only indirectly,
 * through validator cases that constructed [DatasourceProperties] by hand — which cannot fail for
 * the reason this function can.
 */
class DatasourcePropertiesTest {
    @Test
    fun `a well-formed object splits into both namespaces with nothing unknown`() {
        val parsed =
            DatasourceProperties.fromRaw(
                mapOf(
                    "hikari" to mapOf("maximumPoolSize" to 8),
                    "jdbc" to mapOf("ssl" to "true"),
                ),
            )

        parsed.hikari shouldBe mapOf("maximumPoolSize" to 8)
        parsed.jdbc shouldBe mapOf("ssl" to "true")
        parsed.unknownNamespaces.shouldBeEmpty()
    }

    @Test
    fun `an empty object yields two empty namespaces`() {
        val parsed = DatasourceProperties.fromRaw(emptyMap())

        parsed.hikari.shouldBeEmpty()
        parsed.jdbc.shouldBeEmpty()
        parsed.unknownNamespaces.shouldBeEmpty()
    }

    @Test
    fun `an unknown top-level namespace is preserved as evidence, not dropped`() {
        val parsed = DatasourceProperties.fromRaw(mapOf("hikkari" to mapOf("maximumPoolSize" to 8)))

        parsed.unknownNamespaces shouldBe setOf("hikkari")
        parsed.hikari.shouldBeEmpty()
    }

    @Test
    fun `a reserved namespace whose value is not a map counts as unknown`() {
        // It cannot be applied as a property map, so validation must reject it rather than the
        // code guessing what a scalar 'hikari' meant.
        DatasourceProperties.fromRaw(mapOf("hikari" to "maximumPoolSize=8")).unknownNamespaces shouldBe setOf("hikari")
        DatasourceProperties.fromRaw(mapOf("jdbc" to listOf("ssl"))).unknownNamespaces shouldBe setOf("jdbc")
    }

    @Test
    fun `a null namespace value counts as unknown rather than empty`() {
        DatasourceProperties.fromRaw(mapOf("jdbc" to null)).unknownNamespaces shouldBe setOf("jdbc")
    }

    @Test
    fun `every unknown namespace the validator sees becomes a properties_invalid error`() {
        val parsed = DatasourceProperties.fromRaw(mapOf("hikkari" to mapOf("x" to 1), "jbdc" to mapOf("y" to 2)))

        val errors = DatasourceValidator().validate(Fixtures.h2(properties = parsed), isCreate = true).errors

        errors.map { it.code }.distinct() shouldContainExactly listOf(DatasourceErrorCodes.PROPERTIES_INVALID)
        errors.size shouldBe 2
    }

    @Test
    fun `Datasource toString never prints the password`() {
        // DS-SEC-11: the generated data-class toString prints every property, and a Datasource
        // reaches an exception message, a debug log or an IDE watch by accident far more often
        // than by design. `password_set` mirrors the §3.2 response shape instead.
        val rendered = Fixtures.h2(password = "hunter2-the-secret").toString()

        rendered shouldNotContain "hunter2-the-secret"
        rendered shouldContain "password_set=true"
        // Still useful for debugging: which datasource, which dialect, which target.
        rendered shouldContain "name=test_h2"
        rendered shouldContain "jdbcUrl=jdbc:h2:mem:test_h2"

        Fixtures.h2(password = null).toString() shouldContain "password_set=false"
    }
}
