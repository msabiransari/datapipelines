package co.datapipelines.parameters

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterUtils

/**
 * The selector binds (record §6.2, P29) — pinned against the PINNED spring-jdbc 6.2.19 the
 * `NamedParameterTranslationTest` way: the first two tests are regression pins on the jar itself (a
 * bump that changes either half fails here, not in a customer's database), the rest pin
 * [SelectorBinds]' own rules over it.
 */
class SelectorBindsTest {
    private fun translate(
        sql: String,
        binds: Map<String, Any?>,
        maxBinds: Int = 2_000,
    ) = SelectorBinds.translate(sql, binds, maxBinds)

    @Test
    fun `the pinned jar expands an Iterable into one placeholder per member - but keeps the list as ONE value`() {
        val sql = "SELECT v FROM t WHERE r IN (:regions) AND c = :country"
        val source = MapSqlParameterSource(mapOf("regions" to listOf("a", "b", "c"), "country" to "US"))
        val parsed = NamedParameterUtils.parseSqlStatement(sql)

        NamedParameterUtils.substituteNamedParameters(parsed, source) shouldBe "SELECT v FROM t WHERE r IN (?, ?, ?) AND c = ?"
        NamedParameterUtils.buildValueArray(parsed, source, null).toList() shouldBe listOf(listOf("a", "b", "c"), "US")
    }

    @Test
    fun `the pinned jar renders an EMPTY Iterable as IN () - a syntax error on every engine`() {
        val parsed = NamedParameterUtils.parseSqlStatement("SELECT v FROM t WHERE r IN (:regions)")

        NamedParameterUtils.substituteNamedParameters(parsed, MapSqlParameterSource("regions", emptyList<String>())) shouldBe
            "SELECT v FROM t WHERE r IN ()"
    }

    @Test
    fun `a MULTI parent binds as one placeholder per member, the values flattened to match`() {
        val translated =
            translate(
                "SELECT v FROM t WHERE r IN (:regions) AND c = :country",
                mapOf(
                    "regions" to listOf("a", "b", "c"),
                    "country" to "US",
                ),
            )

        translated shouldBe
            SelectorBinds.Translation.Translated("SELECT v FROM t WHERE r IN (?, ?, ?) AND c = ?", listOf("a", "b", "c", "US"))
    }

    @Test
    fun `an empty MULTI binds as IN (NULL) - one placeholder holding null, never a literal`() {
        translate("SELECT v FROM t WHERE r IN (:regions)", mapOf("regions" to emptyList<String>())) shouldBe
            SelectorBinds.Translation.Translated("SELECT v FROM t WHERE r IN (?)", listOf(null))
    }

    @Test
    fun `a list referenced twice expands at both places`() {
        translate(
            "SELECT :regions_count AS n WHERE a IN (:regions) OR b IN (:regions)",
            mapOf(
                "regions" to listOf(1, 2),
                "regions_count" to 2,
            ),
        ) shouldBe
            SelectorBinds.Translation.Translated("SELECT ? AS n WHERE a IN (?, ?) OR b IN (?, ?)", listOf(2, 1, 2, 1, 2))
    }

    @Test
    fun `the in_list slices deal the list in order across the slices the SQL names`() {
        val list = listOf("a", "b", "c", "d", "e")

        translate("SELECT 1 WHERE (r IN (:regions__1) OR r IN (:regions__2))", mapOf("regions" to list)) shouldBe
            SelectorBinds.Translation.Translated("SELECT 1 WHERE (r IN (?, ?, ?) OR r IN (?, ?))", list)
    }

    @Test
    fun `a slice with nothing dealt to it binds IN (NULL)`() {
        translate(
            "SELECT 1 WHERE (r IN (:regions__1) OR r IN (:regions__2) OR r IN (:regions__3))",
            mapOf("regions" to listOf("a")),
        ) shouldBe
            SelectorBinds.Translation.Translated("SELECT 1 WHERE (r IN (?) OR r IN (?) OR r IN (?))", listOf("a", null, null))
    }

    @Test
    fun `a bind with no value is refused, never bound as null`() {
        translate("SELECT 1 WHERE a = :country AND b = :state", mapOf("country" to "US")) shouldBe
            SelectorBinds.Translation.Missing("state")
        // A slice of something that is not a bound LIST is not a slice.
        translate("SELECT 1 WHERE a IN (:country__1)", mapOf("country" to "US")) shouldBe SelectorBinds.Translation.Missing("country__1")
    }

    @Test
    fun `a bound null stays a null bind - the author's SQL handles the unresolved parent`() {
        translate("SELECT 1 WHERE (:country IS NULL OR c = :country)", mapOf("country" to null)) shouldBe
            SelectorBinds.Translation.Translated("SELECT 1 WHERE (? IS NULL OR c = ?)", listOf(null, null))
    }

    @Test
    fun `max-binds-per-statement counts every EXPANDED placeholder - the bound itself is allowed`() {
        val sql = "SELECT 1 WHERE r IN (:regions) AND c = :country"
        translate(sql, mapOf("regions" to List(1_999) { it }, "country" to "US"), maxBinds = 2_000)
            .shouldBeInstanceOf<SelectorBinds.Translation.Translated>()
            .values.size shouldBe 2_000
        translate(sql, mapOf("regions" to List(2_000) { it }, "country" to "US"), maxBinds = 2_000) shouldBe
            SelectorBinds.Translation.TooManyBinds(2_001, 2_000)
    }

    @Test
    fun `quotes, comments and casts are Spring's to skip - the same parser as pipeline SQL`() {
        translate("SELECT a::text, ':not_a_bind' AS q -- :nor_this\nFROM t WHERE c = :country", mapOf("country" to "US")) shouldBe
            SelectorBinds.Translation.Translated("SELECT a::text, ':not_a_bind' AS q -- :nor_this\nFROM t WHERE c = ?", listOf("US"))
    }

    @Test
    fun `a bare question mark is refused as data, never thrown and never bound null`() {
        translate("SELECT 1 WHERE a = ? AND b = :country", mapOf("country" to "US")).shouldBeInstanceOf<SelectorBinds.Translation.Refused>()
        translate("SELECT 1 WHERE a = ?", emptyMap()).shouldBeInstanceOf<SelectorBinds.Translation.Refused>()
        // Inside a literal it is text, not a placeholder.
        translate("SELECT '?' AS q WHERE b = :country", mapOf("country" to "US")).shouldBeInstanceOf<SelectorBinds.Translation.Translated>()
    }
}
