package co.datapipelines.pipeline

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
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

    /**
     * #464 — the listed version beside a newer draft: the unnarrowed view lists the RELEASED
     * `current_version` and names the draft; a narrowing view emits released metadata only.
     */
    @ParameterizedTest
    @EnumSource(NavigationFamily::class)
    fun `a released current version beside a newer draft lists the release and names the draft`(family: NavigationFamily) {
        seedContext()
        insertVersions(family, "scope/pair", listOf(1 to RELEASED, 2 to DRAFT), current = 1)

        val everything = exhaust(family, ReadLens.Everything).single()
        (everything.version to everything.draftVersion) shouldBe (1 to 2)
        val searched = exhaust(family, ReadLens.Everything, "pair").single()
        (searched.version to searched.draftVersion) shouldBe (1 to 2)
        val narrowed = exhaust(family, ReadLens.Only(setOf("scope/pair"))).single()
        (narrowed.version to narrowed.draftVersion) shouldBe (1 to null)
    }

    /**
     * #464 — the §3.2 derivation is the soft delete (V19 dropped the `is_deleted` columns): an
     * entity whose every version is DISCARDED is absent from browse and search; one with a newer
     * draft and no current version is listed through that draft.
     */
    @ParameterizedTest
    @EnumSource(NavigationFamily::class)
    fun `an all-discarded entity is absent and a discarded release beside a draft lists the draft`(family: NavigationFamily) {
        seedContext()
        insertVersions(family, "scope/gone", listOf(1 to DISCARDED), current = null)
        insertVersions(family, "scope/redrafted", listOf(1 to DISCARDED, 2 to DRAFT), current = null)

        val browsed = exhaust(family, ReadLens.Everything)
        browsed.map { it.path } shouldContainExactly listOf("scope/redrafted")
        (browsed.single().version to browsed.single().draftVersion) shouldBe (2 to 2)
        exhaust(family, ReadLens.Everything, "gone").size shouldBe 0
        exhaust(family, ReadLens.Everything, "redrafted").map { it.version } shouldContainExactly listOf(2)
        // A narrowing view lists released metadata only: a draft-only survivor is not admitted.
        exhaust(family, ReadLens.Only(setOf("scope/gone", "scope/redrafted"))).size shouldBe 0
    }

    /**
     * #464 — LIKE metacharacters in a ROOT or PARENT are data (`literal(prefix)`): browsing
     * `a_b` must not list `aXb`'s children, nor `a%b` list `aYYb`'s, and a search rooted at
     * `a_b` stays inside it. (Postgres's LIKE escape is already the backslash, so the guard
     * is the escaping of the prefix, not the `ESCAPE` clause itself.)
     */
    @ParameterizedTest
    @EnumSource(NavigationFamily::class)
    fun `underscore and percent in a root or parent folder are literal`(family: NavigationFamily) {
        seedContext()
        insert(family, "scope/a_b/inside")
        insert(family, "scope/axb/outside")
        insert(family, "scope/a%b/percent")
        insert(family, "scope/ayyb/other")

        page(family, root = "scope", parent = "scope/a_b").map { it.path } shouldContainExactly listOf("scope/a_b/inside")
        page(family, root = "scope", parent = "scope/a%b").map { it.path } shouldContainExactly listOf("scope/a%b/percent")
        page(family, root = "scope/a_b", parent = "scope/a_b", query = "side").map { it.path } shouldContainExactly
            listOf("scope/a_b/inside")
    }

    /**
     * #464 A.8 — the liveness invariant the listing relies on: no `current_version` names a
     * DISCARDED version. Exercised through [PipelineRepository.discardVersion] (the one DISCARDED
     * writer of this module) in both postures, then asserted in SQL over every family's rows of
     * this workspace.
     */
    @org.junit.jupiter.api.Test
    fun `discarding the current release never leaves the pointer on a discarded version`() {
        seedContext()
        val repository = PipelineRepository(jdbc)
        val pipelines = NavigationFamily.PIPELINES
        val besideDraft = insertVersions(pipelines, "scope/beside_draft", listOf(1 to RELEASED, 2 to DRAFT), current = 1)
        val twoReleases = insertVersions(pipelines, "scope/two_releases", listOf(1 to RELEASED, 2 to RELEASED), current = 2)
        val developed = insertVersions(pipelines, "scope/developed", listOf(1 to RELEASED, 2 to DRAFT), current = 1)

        repository.discardVersion(workspace, besideDraft, "scope/beside_draft", 1, actor, draftEligible = false).shouldNotBeNull()
        repository.discardVersion(workspace, twoReleases, "scope/two_releases", 2, actor, draftEligible = false).shouldNotBeNull()
        repository.discardVersion(workspace, developed, "scope/developed", 1, actor, draftEligible = true).shouldNotBeNull()

        pointer(besideDraft) shouldBe null
        pointer(twoReleases) shouldBe 1
        pointer(developed) shouldBe 2
        NavigationFamily.entries.forEach { family -> discardedPointers(family) shouldBe 0 }
        exhaust(NavigationFamily.PIPELINES, ReadLens.Everything).map { it.path to it.version } shouldContainExactly
            listOf("scope/beside_draft" to 2, "scope/developed" to 2, "scope/two_releases" to 1)
    }

    private fun pointer(id: UUID): Int? =
        jdbc.queryForObject("SELECT current_version FROM pipelines WHERE id = :id", mapOf("id" to id), Int::class.javaObjectType)

    private fun discardedPointers(family: NavigationFamily): Int =
        jdbc.queryForObject(
            "SELECT count(*) FROM ${family.table} s JOIN ${versionsOf(family)} v" +
                " ON v.${family.foreignKey} = s.id AND v.version = s.current_version" +
                " WHERE s.workspace_id = :workspace AND v.status = 'DISCARDED'",
            mapOf("workspace" to workspace),
            Int::class.java,
        ) ?: 0

    private fun page(
        family: NavigationFamily,
        root: String,
        parent: String,
        query: String? = null,
    ): List<NavigationRow> = NavigationSource.page(jdbc, family, NavigationRequest(workspace, ReadLens.Everything, root, parent, query))

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
        insertVersions(family, name, listOf(1 to if (released) RELEASED else DRAFT), if (released) 1 else null, display, description)
    }

    /**
     * One entity holding [versions] (number to status) with [current] as its pointer, chosen
     * explicitly — the shapes a one-version seed never reaches (#464). A non-draft carries its
     * release stamp, a DISCARDED one its discard stamp (the V19/V39/V42 CHECKs).
     */
    @Suppress("LongParameterList") // the entity's identity, its version list, its pointer and two display fields
    private fun insertVersions(
        family: NavigationFamily,
        name: String,
        versions: List<Pair<Int, String>>,
        current: Int?,
        display: String = name,
        description: String = "",
    ): UUID {
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
                "current" to current,
                "actor" to actor,
            ),
        )
        val contentColumns = if (family == NavigationFamily.TEMPLATES) "body, dialect" else "body_json"
        val content = if (family == NavigationFamily.TEMPLATES) "'select 1', 'POSTGRES'" else "'{}'::jsonb"
        versions.forEach { (version, status) ->
            val stamp = java.sql.Timestamp.from(java.time.Instant.EPOCH)
            jdbc.update(
                "INSERT INTO ${versionsOf(family)} (${family.foreignKey}, version, $contentColumns," +
                    " status, body_hash, created_by, released_at, released_by, discarded_at, discarded_by)" +
                    " VALUES (:id, :version, $content, :status, :hash, :actor, :releasedAt, :releasedBy, :discardedAt, :discardedBy)",
                mapOf(
                    "id" to id,
                    "version" to version,
                    "actor" to actor,
                    "status" to status,
                    "hash" to version.toString().repeat(HASH_LENGTH),
                    "releasedAt" to if (status == DRAFT) null else stamp,
                    "releasedBy" to if (status == DRAFT) null else actor,
                    "discardedAt" to if (status == DISCARDED) stamp else null,
                    "discardedBy" to if (status == DISCARDED) actor else null,
                ),
            )
        }
        return id
    }

    private fun versionsOf(family: NavigationFamily): String = family.table.removeSuffix("s") + "_versions"

    companion object {
        private const val RELEASED = "RELEASED"
        private const val DRAFT = "DRAFT"
        private const val DISCARDED = "DISCARDED"

        private const val LARGE_LEVEL = 205
        private const val HASH_LENGTH = 64
    }
}
