package co.datapipelines.pipeline

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.util.UUID

/** Shared migrated Postgres, unique workspace per case; no other suite's fixture world is touched. */
class NavigationSourceIntegrationTest {
    private val jdbc = NamedParameterJdbcTemplate(SharedPostgres.pooledDataSource())
    private val workspace = UUID.randomUUID()
    private val actor = UUID.randomUUID()

    private fun seedContext() {
        jdbc.update(
            "INSERT INTO users (id, email, display_name, provider, provider_subject) VALUES (:id, :email, 'Tree test', 'google', :email)",
            mapOf("id" to actor, "email" to "$actor@tree.test"),
        )
        jdbc.update(
            "INSERT INTO workspaces (id, name, display_name) VALUES (:id, :name, 'Tree test')",
            mapOf("id" to workspace, "name" to "tree_$workspace"),
        )
    }

    @AfterEach
    fun cleanOwnRows() {
        NavigationFamily.entries.forEach { family ->
            jdbc.update(
                "DELETE FROM ${family.table} WHERE workspace_id = :id",
                mapOf("id" to workspace),
            )
        }
        jdbc.update("DELETE FROM workspaces WHERE id = :id", mapOf("id" to workspace))
        jdbc.update("DELETE FROM users WHERE id = :id", mapOf("id" to actor))
    }

    @ParameterizedTest
    @EnumSource(NavigationFamily::class)
    fun `one level continues all folders and leaves past 200 without grandchildren`(family: NavigationFamily) {
        seedContext()
        repeat(LARGE_LEVEL) { index ->
            insert(family, "scope/f${index.toString().padStart(3, '0')}/deep/leaf")
            insert(family, "scope/a${index.toString().padStart(3, '0')}")
        }
        insert(family, "scope/collision")
        insert(family, "scope/collision/child")
        val rows = exhaust(family, ReadLens.Everything)
        rows.size shouldBe LARGE_LEVEL * 2 + 2
        rows.count { it.id == null } shouldBe LARGE_LEVEL + 1
        rows.map { it.key }.distinct().size shouldBe rows.size
        rows.count { it.path == "scope/collision" } shouldBe 2
        rows.any { it.path.contains("/deep") } shouldBe false
        rows.map { it.orderKey } shouldContainExactly rows.map { it.orderKey }.sorted()
    }

    @ParameterizedTest
    @EnumSource(NavigationFamily::class)
    fun `name search covers every match and display name but never description or a foreign workspace`(family: NavigationFamily) {
        seedContext()
        repeat(LARGE_LEVEL) { index -> insert(family, "scope/deep/match_${index.toString().padStart(3, '0')}") }
        insert(family, "scope/display", display = "MATCH display")
        insert(family, "scope/description", display = "Other", description = "match")
        insert(family, "elsewhere/match")
        val rows = exhaust(family, ReadLens.Everything, "match")
        rows.size shouldBe LARGE_LEVEL + 1
        rows.any { it.path == "scope/description" || it.path.startsWith("elsewhere/") } shouldBe false
        rows.all { it.id != null } shouldBe true
        NavigationSource.page(jdbc, family, NavigationRequest(UUID.randomUUID(), ReadLens.Everything, "", "", "match")).size shouldBe 0
        listOf(false, true).forEach { searching ->
            val plan =
                jdbc.queryForList(
                    "EXPLAIN (ANALYZE, BUFFERS) " + NavigationSource.sql(family, narrowed = false, search = searching),
                    mapOf(
                        "workspace" to workspace,
                        "prefix" to "scope/%",
                        "cut" to 7,
                        "pattern" to "%match%",
                        "after" to "",
                        "limit" to NavigationRequest.PAGE_SIZE + 1,
                    ),
                    String::class.java,
                )
            plan.any { it.contains("Execution Time:") } shouldBe true
            println("460-query-plan family=${family.route} search=$searching\n" + plan.joinToString("\n"))
        }
        insert(family, "scope/literal_percent", display = "100% real")
        exhaust(family, ReadLens.Everything, "%").map { it.path } shouldContainExactly listOf("scope/literal_percent")
    }

    @ParameterizedTest
    @EnumSource(NavigationFamily::class)
    fun `narrowing excludes hidden folder metadata and drafts before paging`(family: NavigationFamily) {
        seedContext()
        insert(family, "scope/visible/leaf")
        insert(family, "scope/hidden/leaf")
        insert(family, "scope/draft/leaf", released = false)
        val lens = ReadLens.Only(setOf("scope/visible/leaf", "scope/draft/leaf"))
        exhaust(family, lens).map { it.path } shouldContainExactly listOf("scope/visible")
        exhaust(family, lens, "leaf").map { it.path } shouldContainExactly listOf("scope/visible/leaf")
        exhaust(family, ReadLens.NOTHING).size shouldBe 0
    }

    private fun exhaust(
        family: NavigationFamily,
        lens: ReadLens,
        query: String? = null,
    ): List<NavigationRow> {
        val rows = mutableListOf<NavigationRow>()
        var after = ""
        do {
            val page = NavigationSource.page(jdbc, family, NavigationRequest(workspace, lens, "scope", "scope", query, after))
            rows.addAll(page)
            if (page.isNotEmpty()) {
                (page.last().orderKey > after) shouldBe true
                after = page.last().orderKey
            }
        } while (page.size == NavigationRequest.PAGE_SIZE)
        return rows
    }

    private fun insert(
        family: NavigationFamily,
        name: String,
        display: String = name,
        description: String = "",
        released: Boolean = true,
    ) {
        val id = UUID.randomUUID()
        val ownerColumn = if (family == NavigationFamily.PIPELINES) "owner_id" else "created_by"
        jdbc.update(
            "INSERT INTO ${family.table} (id, workspace_id, name, display_name, description, current_version, $ownerColumn)" +
                " VALUES (:id, :workspace, :name, :display, :description, :current, :actor)",
            mapOf(
                "id" to id,
                "workspace" to workspace,
                "name" to name,
                "display" to display,
                "description" to description,
                "current" to if (released) 1 else null,
                "actor" to actor,
            ),
        )
        val versions = family.table.removeSuffix("s") + "_versions"
        val contentColumns = if (family == NavigationFamily.TEMPLATES) "body, dialect" else "body_json"
        val content = if (family == NavigationFamily.TEMPLATES) "'select 1', 'POSTGRES'" else "'{}'::jsonb"
        jdbc.update(
            "INSERT INTO $versions (${family.foreignKey}, version, $contentColumns," +
                " status, body_hash, created_by, released_at, released_by)" +
                " VALUES (:id, 1, $content, :status, :hash, :actor, :releasedAt, :releasedBy)",
            mapOf(
                "id" to id,
                "actor" to actor,
                "status" to if (released) "RELEASED" else "DRAFT",
                "hash" to "a".repeat(HASH_LENGTH),
                "releasedAt" to if (released) java.sql.Timestamp.from(java.time.Instant.EPOCH) else null,
                "releasedBy" to if (released) actor else null,
            ),
        )
    }

    companion object {
        private const val LARGE_LEVEL = 205
        private const val HASH_LENGTH = 64
    }
}
