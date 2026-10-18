package co.datapipelines.pipeline

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.util.UUID

/**
 * #556 A.5 — `RecentSource`'s statement, by shape: the family's own tables from [NavigationFamily],
 * every value bound, the four modification columns unlensed and RELEASED rows with `released_at`
 * alone through a narrowed lens (InventorySource's rule), discarded-only objects never listed.
 * The rows themselves are `FamilyRecentE2eTest`'s.
 */
class RecentSourceTest {
    @ParameterizedTest
    @EnumSource(NavigationFamily::class)
    fun `the statement reads the family's tables with every value bound`(family: NavigationFamily) {
        val versions = family.table.removeSuffix("s") + "_versions"
        listOf(false, true).forEach { narrowed ->
            val sql = RecentSource.sql(family, narrowed)
            sql shouldContain "FROM ${family.table} s"
            sql shouldContain "JOIN $versions v ON v.${family.foreignKey} = s.id"
            sql shouldContain "WHERE s.workspace_id = :workspace"
            sql shouldContain "HAVING bool_or(v.status IN ('DRAFT', 'RELEASED'))"
            sql shouldContain "ORDER BY modified_at DESC, s.name COLLATE \"C\""
            sql shouldContain "LIMIT :limit"
            sql shouldNotContain "current_version"
        }
    }

    @Test
    fun `unlensed, the time is the greatest of the four modification columns`() {
        val sql = RecentSource.sql(NavigationFamily.PIPELINES, narrowed = false)

        sql shouldContain "GREATEST(s.updated_at, max(coalesce(v.updated_at, v.created_at)), max(v.released_at), max(v.discarded_at))"
        sql shouldNotContain ":names"
        sql shouldNotContain "AND v.status"
    }

    @Test
    fun `a narrowed lens reads admitted names' RELEASED rows and their release time alone`() {
        val sql = RecentSource.sql(NavigationFamily.PIPELINES, narrowed = true)

        sql shouldContain "AND s.name IN (:names) AND v.status = 'RELEASED'"
        sql shouldContain "coalesce(max(v.released_at), max(v.created_at)) AS modified_at"
        sql shouldNotContain "s.updated_at,"
        sql shouldNotContain "v.updated_at"
        sql shouldNotContain "discarded_at"
    }

    @Test
    fun `an empty lens performs no database access`() {
        val jdbc = mockk<NamedParameterJdbcTemplate>()

        RecentSource.recent(jdbc, NavigationFamily.PIPELINES, UUID.randomUUID(), ReadLens.NOTHING).shouldBeEmpty()
        verify(exactly = 0) { jdbc.query(any<String>(), any<Map<String, *>>(), any<org.springframework.jdbc.core.RowMapper<*>>()) }
    }

    @Test
    fun `the overview lists up to five`() {
        RecentSource.DEFAULT_LIMIT shouldBe 5
    }
}
