package co.datapipelines.templates

import co.datapipelines.pipeline.CreateLifecycle
import co.datapipelines.pipeline.TemplateType
import co.datapipelines.pipeline.WriteSurface
import co.datapipelines.typesystem.LogicalType
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.util.UUID

/**
 * V33's transform-block schema against REAL pre-migration rows (transform-nodes design §2.2,
 * lane 7b): the three jsonb columns, the replaced `chk_template_type` / `chk_type_dialect`,
 * the new `chk_transform_blocks` — and the round's load-bearing gate, **hash compatibility**.
 *
 * The schema is built V1–V32 through [ShippedMigrations] (never a hand-copied list), pre-V33
 * rows are inserted hashed by the pre-V33 expression (the one V6's backfill used — the test's
 * OWN copy, so the old world is pinned in place), and V33 is then applied once, by plain JDBC,
 * exactly as Flyway would apply it.
 *
 * ## The hash gate, and its falsification
 *
 * `TEMPLATE_HASH_EXPR`'s CASE arm must leave every sql/html row's hash byte-identical — the
 * pre-V33 world is pinned with draft pins and release preconditions that read `body_hash`.
 * [pre-V33 hashes are byte-identical under the new expression] recomputes every stored row
 * through the repository's real [TemplateRepository.computeBodyHash] and counts the drifts.
 * Falsified at birth: making the CASE arm unconditional (the ELSE builds the blocks object
 * from nulls) turns that test red on every sql row; restored by the inverse edit.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TransformBlocksMigrationTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate

    @BeforeAll
    fun createPreV33SchemaThenApplyV33() {
        jdbc = NamedParameterJdbcTemplate(DriverManagerDataSource(db.jdbcUrl, db.username, db.password))
        val dir = TemplateFixtures.repoDirectory("modules/app/src/main/resources/db/migration")
        ShippedMigrations.migrations(dir).filter { it.first < 33 }.forEach { pair ->
            jdbc.jdbcTemplate.execute(pair.second.readText())
        }
        jdbc.update(
            "INSERT INTO users (id, email, display_name, provider, provider_subject)" +
                " VALUES (:id, 'v33@example.com', 'V33', 'google', 'sub-v33')",
            mapOf("id" to ACTOR_ID),
        )
        // Two pre-V33 rows, one sql and one html — hashed by the PRE-V33 expression (the test's
        // own copy of it; the production expression has moved on, which is the point).
        insertPreV33ReleasedTemplate("test/v33_orders.sql", "sql", "POSTGRES", "SELECT 1")
        insertPreV33ReleasedTemplate("test/v33_report.html.sql", "html", null, "<p>Hi</p>")
        // V33, applied once, exactly as Flyway would.
        jdbc.jdbcTemplate.execute(
            TemplateFixtures.repoFile("modules/app/src/main/resources/db/migration/V33__transform_template_blocks.sql").readText(),
        )
        // Then every later shipped migration, the TypedTemplatesMigrationTest precedent: the
        // cases below drive the CURRENT repository, whose projection reads V36's
        // `template_implements` (7e). None of them touches a template hash, so the pre-V33
        // rows' hash-compat verdict is unchanged by construction.
        ShippedMigrations.migrations(dir).filter { it.first > 33 }.forEach { pair ->
            jdbc.jdbcTemplate.execute(pair.second.readText())
        }
    }

    @Test
    fun `V33 admits the transform types and rewrites both constraints`() {
        // chk_template_type: the two transform types are admitted now.
        insertVersion(type = "jsonata", dialect = null, blocks = true)
        insertVersion(type = "javascript", dialect = null, blocks = true)
        // chk_type_dialect: a transform row carrying a dialect is refused; sql without one too.
        insertVersionShouldFail(type = "jsonata", dialect = "POSTGRES", blocks = true, constraint = "chk_type_dialect")
        insertVersionShouldFail(type = "sql", dialect = null, blocks = false, constraint = "chk_type_dialect")
        // chk_transform_blocks: blocks on an sql row, or missing on a transform row.
        insertVersionShouldFail(type = "sql", dialect = "POSTGRES", blocks = true, constraint = "chk_transform_blocks")
        insertVersionShouldFail(type = "jsonata", dialect = null, blocks = false, constraint = "chk_transform_blocks")
        // An unknown type is still refused.
        insertVersionShouldFail(type = "csv", dialect = null, blocks = false, constraint = "chk_template_type")
    }

    @Test
    fun `pre-V33 hashes are byte-identical under the new expression`() {
        val repository = TemplateRepository(jdbc)
        // Only the two pre-V33 rows: the blocks probe (a sibling test) writes transform rows
        // whose hashes legitimately include the CASE arm — counting those would be measuring
        // test order, not the migration.
        val rows =
            jdbc.queryForList(
                "SELECT v.engine, v.dialect, v.is_library, v.imports_json::TEXT AS imports_json, v.body, v.body_hash" +
                    " FROM template_versions v JOIN templates t ON t.id = v.template_id" +
                    " WHERE t.name IN ('test/v33_orders.sql', 'test/v33_report.html.sql')",
                emptyMap<String, Any>(),
            )
        var differ = 0
        rows.forEach { row ->
            val recomputed =
                repository.computeBodyHash(
                    engine = row["engine"] as String,
                    dialect = row["dialect"] as String?,
                    isLibrary = row["is_library"] as Boolean,
                    importsJson = row["imports_json"] as String,
                    body = row["body"] as String,
                )
            if (recomputed != row["body_hash"] as String) differ++
        }
        println("V33 hash-compat: ${rows.size} rows recomputed, $differ differ")
        differ shouldBe 0
        rows.size shouldBe 2
    }

    @Test
    fun `a transform version's hash changes when any block changes, and its blocks read back`() {
        val repository = TemplateRepository(jdbc)
        val draft =
            TemplateFixtures.draft(
                id = "test/v33_probe.jsonata",
                type = TemplateType.JSONATA,
                engine = Template.NONE_ENGINE,
                dialect = null,
                body = "rows",
                contract = probeContract(),
                invariants = emptyList(),
                tests = listOf(probeCase()),
            )
        val created = repository.create(WORKSPACE_ID, draft, ACTOR_ID, CreateLifecycle.RELEASED, WriteSurface.SESSION)

        // The blocks read back through the content mapper, bound.
        val stored = checkNotNull(repository.findVersion(WORKSPACE_ID, "test/v33_probe.jsonata", created.version))
        assertSoftly {
            stored.type shouldBe TemplateType.JSONATA
            stored.contract?.mode shouldBe TransformMode.ROW
            stored.contract?.inputs?.keys shouldBe setOf("orders", "tz")
            stored.tests?.single()?.name shouldBe "empty"
        }

        // The hash tracks every block: a contract change and a tests change both move it.
        val base = created.bodyHash
        val contractChanged =
            repository.createDraft(
                WORKSPACE_ID,
                "test/v33_probe.jsonata",
                draft.copy(contract = probeContract().copy(rejects = true)),
                base,
                ACTOR_ID,
                WriteSurface.SESSION,
            )
        checkNotNull(contractChanged).bodyHash shouldNotBe base

        val testsChanged =
            repository.writeDraft(
                WORKSPACE_ID,
                "test/v33_probe.jsonata",
                draft.copy(tests = listOf(probeCase().copy(name = "renamed"))),
                checkNotNull(contractChanged).bodyHash,
                ACTOR_ID,
                WriteSurface.SESSION,
            )
        checkNotNull(testsChanged).bodyHash shouldNotBe checkNotNull(contractChanged).bodyHash

        // And a byte-identical write is the §5.1 no-op guard's target: the same content hashes
        // the same (the createDraft no-op needs a RELEASED parent, so this asserts the primitive).
        repository.computeBodyHash(
            engine = Template.NONE_ENGINE,
            dialect = null,
            isLibrary = false,
            importsJson = "[]",
            body = "rows",
            contractJson = TransformBlocks.writeContract(probeContract()),
            invariantsJson = TransformBlocks.writeInvariants(emptyList()),
            testsJson = TransformBlocks.writeTests(listOf(probeCase())),
        ) shouldBe base
    }

    /** One legal row-mode contract — the record §2.2's example, trimmed to two inputs. */
    private fun probeContract() =
        TransformContract(
            mode = TransformMode.ROW,
            inputs =
                mapOf(
                    "orders" to
                        TransformInput.Table(
                            listOf(ContractColumn("order_id", LogicalType.INTEGER)),
                        ),
                    "tz" to TransformInput.Value(LogicalType.STRING),
                ),
            output = TransformOutput.Table(listOf(ContractColumn("order_id", LogicalType.INTEGER))),
        )

    private fun probeCase() =
        TransformTestCase(
            name = "empty",
            input = TransformTestInput(rows = emptyList(), inputs = mapOf("tz" to "UTC")),
            expect = TransformTestExpect(output = TransformBlocks.mapper.readTree("""{"rows": []}""")),
        )

    /** Seeds a RELEASED v1 the way a pre-V33 row exists — hashed by the PRE-V33 expression. */
    private fun insertPreV33ReleasedTemplate(
        name: String,
        type: String,
        dialect: String?,
        body: String,
    ) {
        jdbc.update(
            """
            INSERT INTO templates (name, display_name, description, current_version, workspace_id, created_by)
            VALUES (:name, 'Pre-V33', '', 1, :ws, :actor)
            """.trimIndent(),
            mapOf("name" to name, "ws" to WORKSPACE_ID, "actor" to ACTOR_ID),
        )
        jdbc.update(
            """
            INSERT INTO template_versions
                (template_id, version, engine, type, dialect, is_library, imports_json, body,
                 status, body_hash, created_by, released_by, released_at)
            SELECT t.id, 1, 'freemarker', :type, CAST(:dialect AS TEXT), FALSE, '[]'::jsonb, :body,
                   'RELEASED',
                   encode(sha256(convert_to(jsonb_build_object('engine', 'freemarker', 'dialect', CAST(:dialect AS TEXT),
                       'is_library', FALSE, 'imports', '[]'::jsonb, 'body', :body)::text, 'UTF8')), 'hex'),
                   :actor, :actor, NOW()
              FROM templates t WHERE t.name = :name
            """.trimIndent(),
            mapOf("name" to name, "type" to type, "dialect" to dialect, "body" to body, "actor" to ACTOR_ID),
        )
    }

    /** A post-V33 direct insert of one probe version with the given constraint shape. */
    private fun insertVersion(
        type: String,
        dialect: String?,
        blocks: Boolean,
    ) {
        jdbc.update(
            """
            INSERT INTO templates (name, display_name, description, current_version, workspace_id, created_by)
            VALUES ('shape_probe_v33.sql', 'Probe', '', 1, :ws, :actor)
            ON CONFLICT (workspace_id, name) DO NOTHING
            """.trimIndent(),
            mapOf("ws" to WORKSPACE_ID, "actor" to ACTOR_ID),
        )
        jdbc.update(
            """
            INSERT INTO template_versions
                (template_id, version, engine, type, dialect, is_library, imports_json, body,
                 contract_json, invariants_json, tests_json,
                 status, body_hash, created_by)
            SELECT t.id, 1, 'freemarker', CAST(:type AS TEXT), CAST(:dialect AS TEXT), FALSE, '[]'::jsonb, 'x',
                   CAST(:contract AS jsonb), CAST(:invariants AS jsonb), CAST(:tests AS jsonb),
                   'RELEASED', 'h', :actor
              FROM templates t WHERE t.name = 'shape_probe_v33.sql'
            """.trimIndent(),
            mapOf(
                "type" to type,
                "dialect" to dialect,
                "actor" to ACTOR_ID,
                "contract" to if (blocks) """{"mode":"row"}""" else null,
                "invariants" to if (blocks) "[]" else null,
                "tests" to if (blocks) "[]" else null,
            ),
        )
        jdbc.update(
            "DELETE FROM template_versions v USING templates t" +
                " WHERE t.id = v.template_id AND t.name = 'shape_probe_v33.sql'",
            emptyMap<String, Any>(),
        )
    }

    /** Asserts the insert fails naming exactly [constraint] — the shape's own refusal. */
    private fun insertVersionShouldFail(
        type: String,
        dialect: String?,
        blocks: Boolean,
        constraint: String,
    ) {
        val thrown =
            shouldThrow<org.springframework.dao.DataIntegrityViolationException> {
                jdbc.update(
                    """
                    INSERT INTO templates (name, display_name, description, current_version, workspace_id, created_by)
                    VALUES ('shape_probe_v33.sql', 'Probe', '', 1, :ws, :actor)
                    ON CONFLICT (workspace_id, name) DO NOTHING
                    """.trimIndent(),
                    mapOf("ws" to WORKSPACE_ID, "actor" to ACTOR_ID),
                )
                jdbc.update(
                    """
                    INSERT INTO template_versions
                        (template_id, version, engine, type, dialect, is_library, imports_json, body,
                         contract_json, invariants_json, tests_json,
                         status, body_hash, created_by)
                    SELECT t.id, 1, 'freemarker', CAST(:type AS TEXT), CAST(:dialect AS TEXT), FALSE, '[]'::jsonb, 'x',
                           CAST(:contract AS jsonb), CAST(:invariants AS jsonb), CAST(:tests AS jsonb),
                           'RELEASED', 'h', :actor
                      FROM templates t WHERE t.name = 'shape_probe_v33.sql'
                    """.trimIndent(),
                    mapOf(
                        "type" to type,
                        "dialect" to dialect,
                        "actor" to ACTOR_ID,
                        "contract" to if (blocks) """{"mode":"row"}""" else null,
                        "invariants" to if (blocks) "[]" else null,
                        "tests" to if (blocks) "[]" else null,
                    ),
                )
            }
        thrown.mostSpecificCause.message shouldContain constraint
    }

    private companion object {
        /** templates.created_by is NOT NULL REFERENCES users — V1/V4 seed no users, so one is inserted. */
        val ACTOR_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000033")

        val WORKSPACE_ID: UUID = UUID.fromString("defa0000-0000-0000-0000-000000000001")

        /**
         * A scratch database on the module's shared container: this suite builds the schema
         * PART-WAY on purpose (pre-V33), so it must not see the fully-migrated database the
         * rest of the module runs against — the migration boundary is the subject.
         */
        val db = SharedPostgres.scratchDatabase("pre_v33_blocks")
    }
}
