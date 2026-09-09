package co.datapipelines.templates

import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.TemplateType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.util.UUID

/**
 * The PROMOTION half of template-hierarchy-design §11's compatibility story (gate §12.13,
 * round 046): a template released **before** V8 — `type` absent from its row, V6's hash
 * stamped on it — still promotes **idempotently after** V8. Re-promoting it to a receiver
 * that already holds the identical version returns the EXISTING version: no duplicate row,
 * no burned version number, no spurious `template.version.conflict`.
 *
 * [TypedTemplatesMigrationTest] proves the sibling half (a pre-V8 release still no-ops a
 * byte-identical PUT post-V8); this suite mirrors its harness exactly — schema built V1–V7
 * through [ShippedMigrations], the pre-V8 row inserted the way V7-era rows exist, V8 applied
 * once by plain JDBC — on its own scratch database, because the migration boundary is again
 * the subject.
 *
 * ## Why the test drives the repository, not the service
 *
 * The promotion receiver's idempotency decision lives in
 * `modules/web`'s `TemplateImportService.importPreserved` — and `web` depends on
 * `templates`, never the reverse. That service is a thin classifier over exactly the three
 * repository calls this test drives, in this order: recompute the payload hash
 * ([TemplateRepository.computeBodyHash], the §9.2 recompute guard), read the target
 * ([TemplateRepository.findVersionDetail]), and — RELEASED with an equal hash — return the
 * existing version WITHOUT calling [TemplateRepository.insertReleasedVersion]. The test
 * mirrors that classification against the same reads, and additionally proves the write the
 * no-op branch skips is suppressed by the repository's own `NOT EXISTS` guard.
 *
 * ## The load-bearing assertion
 *
 * `recomputed shouldBe source.bodyHash`: the receiver's post-V8 hash expression must
 * reproduce the hash V6 stamped on the pre-V8 row, byte for byte. If it did not, every
 * re-promotion of a pre-V8 template would be refused as `hash_mismatch` — the "spurious
 * version conflict" §11 forbids. Same falsification as the sibling gate: add `'type', type`
 * to `TemplateRepository.TEMPLATE_HASH_EXPR` and this test goes red.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PromotionIdempotencyMigrationTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate

    @BeforeAll
    fun createPreV8SchemaThenApplyV8() {
        jdbc = NamedParameterJdbcTemplate(DriverManagerDataSource(db.jdbcUrl, db.username, db.password))
        val dir = TemplateFixtures.repoDirectory("modules/app/src/main/resources/db/migration")
        ShippedMigrations.migrations(dir).filter { it.first < 8 }.forEach { pair ->
            jdbc.jdbcTemplate.execute(pair.second.readText())
        }
        jdbc.update(
            "INSERT INTO users (id, email, display_name, provider, provider_subject)" +
                " VALUES (:id, 'promotion@example.com', 'Promotion', 'google', 'sub-promotion')",
            mapOf("id" to ACTOR_ID),
        )
        // The RECEIVER workspace — V4 seeds only `default` (the source), and
        // templates.workspace_id REFERENCES workspaces, so the receiver must exist too.
        jdbc.update(
            "INSERT INTO workspaces (id, name, display_name) VALUES (:id, 'receiver', 'Receiver')",
            mapOf("id" to RECEIVER_WORKSPACE),
        )
        // A RELEASED v1 exactly the way a V7-era row exists — no `type` column yet, the hash
        // stamped by the SAME canonical expression V6's backfill used (see the sibling suite).
        jdbc.update(
            """
            INSERT INTO templates (name, display_name, description, current_version, workspace_id, created_by)
            VALUES (:name, 'Pre-V8', '', 1, :ws, :actor)
            """.trimIndent(),
            mapOf("name" to TEMPLATE_NAME, "ws" to SOURCE_WORKSPACE, "actor" to ACTOR_ID),
        )
        jdbc.update(
            """
            INSERT INTO template_versions
                (template_id, version, engine, dialect, is_library, imports_json, body,
                 status, body_hash, created_by, released_by, released_at)
            SELECT t.id, 1, 'freemarker', 'POSTGRES', FALSE, '[]'::jsonb, :body,
                   'RELEASED',
                   encode(sha256(convert_to(jsonb_build_object('engine', 'freemarker', 'dialect', 'POSTGRES',
                       'is_library', FALSE, 'imports', '[]'::jsonb, 'body', :body)::text, 'UTF8')), 'hex'),
                   :actor, :actor, NOW()
              FROM templates t WHERE t.name = :name
            """.trimIndent(),
            mapOf("name" to TEMPLATE_NAME, "body" to BODY, "actor" to ACTOR_ID),
        )
        // V8, applied once, exactly as Flyway would — the `type` backfill is its column default.
        jdbc.jdbcTemplate.execute(v8())
        // 101: the repository's detail reads select the V19 discard stamps, so a schema stopped
        // at V8 can no longer serve them — the later migrations run AFTER the pre-V8 rows exist,
        // exactly as a live deployment would have received them. The migration under test is
        // still V8's.
        ShippedMigrations.migrations(dir).filter { it.first in 9..18 }.forEach { pair ->
            jdbc.jdbcTemplate.execute(pair.second.readText())
        }
        ShippedMigrations.paths().filter { it.contains("V19__") }.forEach {
            jdbc.jdbcTemplate.execute(TemplateFixtures.repoFile(it).readText())
        }
    }

    @Test
    fun `a pre-V8 release promotes idempotently post-V8 - the receiver returns the existing version`() {
        val repository = TemplateRepository(jdbc)

        // The export half: the payload a promotion ships is the source's stored row — its
        // version number, its content fields, and the hash V6 stamped pre-V8 riding along.
        val source = checkNotNull(repository.findVersionDetail(SOURCE_WORKSPACE, TEMPLATE_NAME, 1))
        val content = checkNotNull(repository.findVersion(SOURCE_WORKSPACE, TEMPLATE_NAME, 1))
        content.type shouldBe TemplateType.SQL // V8's backfill of the pre-V8 row

        // The receiver's §9.2 recompute guard, run post-V8: it must reproduce the pre-V8
        // stamped hash byte for byte, or the re-promotion below would be refused as
        // hash_mismatch — the spurious conflict §11 says cannot happen.
        val recomputed =
            repository.computeBodyHash(
                engine = content.engine,
                dialect = content.dialect?.wire,
                isLibrary = content.isLibrary,
                importsJson = TemplateJson.writeImports(content.imports),
                body = content.body,
            )
        recomputed shouldBe source.bodyHash

        // First promotion: the receiver workspace is empty, so the version lands at its
        // exact number with the source's declared hash (§9.2's preserved-version import).
        val draft = TemplateFixtures.draft(id = TEMPLATE_NAME, body = BODY)
        repository.importTemplateVersion(RECEIVER_WORKSPACE, draft, 1, source.bodyHash, source.releasedAt, ACTOR_ID)
        checkNotNull(repository.findVersionDetail(RECEIVER_WORKSPACE, TEMPLATE_NAME, 1)).bodyHash shouldBe
            source.bodyHash

        // Re-promotion of the identical export: the receiver's classification reads RELEASED
        // with an equal hash — the idempotent no-op branch, which returns the existing
        // version and writes nothing. A differing hash here is what would raise the spurious
        // template.version.conflict instead.
        val target = checkNotNull(repository.findVersionDetail(RECEIVER_WORKSPACE, TEMPLATE_NAME, 1))
        assertSoftly {
            target.status shouldBe PipelineVersionStatus.RELEASED
            target.bodyHash shouldBe source.bodyHash
        }

        // The write the no-op branch skips is suppressed by the repository's own NOT EXISTS
        // guard as well: no duplicate row, no burned number, the pointer unmoved.
        repository
            .insertReleasedVersion(RECEIVER_WORKSPACE, TEMPLATE_NAME, draft, 1, source.bodyHash, source.releasedAt, ACTOR_ID)
            .shouldBeNull()
        assertSoftly {
            receiverVersionCount() shouldBe 1
            repository.findLatest(RECEIVER_WORKSPACE, TEMPLATE_NAME)?.version shouldBe 1
        }
    }

    // ---------------------------------------------------------------------------------------------

    private fun receiverVersionCount(): Int =
        checkNotNull(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM template_versions v JOIN templates t ON t.id = v.template_id" +
                    " WHERE t.workspace_id = :ws AND t.name = :name",
                mapOf("ws" to RECEIVER_WORKSPACE, "name" to TEMPLATE_NAME),
                Int::class.java,
            ),
        )

    private fun v8(): String = TemplateFixtures.repoFile(ShippedMigrations.paths().first { it.contains("V8__") }).readText()

    private companion object {
        /** templates.created_by is NOT NULL REFERENCES users — V1/V4 seed no users, so one is inserted. */
        val ACTOR_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000055")

        /** The promotion SOURCE workspace — holds the pre-V8 release. */
        val SOURCE_WORKSPACE: UUID = UUID.fromString("defa0000-0000-0000-0000-000000000001")

        /** The promotion RECEIVER workspace — empty until the test promotes into it. */
        val RECEIVER_WORKSPACE: UUID = UUID.fromString("defa0000-0000-0000-0000-000000000002")

        const val TEMPLATE_NAME = "test/promoted_across_v8.sql"
        const val BODY = "SELECT 7 AS promoted"

        /**
         * A scratch database on the module's shared container: this suite builds the schema
         * PART-WAY on purpose (pre-V8), so it must not see the fully-migrated database the
         * rest of the module runs against — the migration boundary is the subject.
         */
        val db = SharedPostgres.scratchDatabase("pre_v8_promotion")
    }
}
