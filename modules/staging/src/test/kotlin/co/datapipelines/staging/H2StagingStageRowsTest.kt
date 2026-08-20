package co.datapipelines.staging

import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.LogicalType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * `stageRows` — the already-decoded composition ingress path (§10): a parent PIPELINE node's
 * `direct`-delivered child rows land in the parent's tempdb under exactly `stage`'s contract
 * (duplicate guard, partial-table rollback, budget check), minus the source-dialect mapping the
 * child's executor already applied.
 */
class H2StagingStageRowsTest {
    private val staging = H2StagingFactory(H2StagingProperties()).create(UUID.randomUUID())

    @AfterEach
    fun tearDown() = staging.close()

    private val columns =
        listOf(
            ColumnSchema("id", LogicalType.INTEGER),
            ColumnSchema("label", LogicalType.STRING),
        )

    @Test
    fun `staged rows are readable under the staged table name with the canonical schema`() {
        val result =
            runBlocking {
                staging.stageRows("stg_child", columns, sequenceOf(listOf(1, "a"), listOf(2, "b"), listOf(3, null)))
            }

        result.tableName shouldBe "stg_child"
        result.rowsStaged shouldBe 3L
        result.columns shouldBe columns
        // No source metadata was read, so there are no mapping warnings to surface.
        result.warnings shouldBe emptyList()
        staging.readFromStaging { scalarLong(it, "SELECT COUNT(*) FROM \"stg_child\"") } shouldBe 3L
        staging.readFromStaging { scalarLong(it, "SELECT COUNT(*) FROM \"stg_child\" WHERE \"label\" IS NULL") } shouldBe 1L
        runBlocking { staging.stats() }.totalRows shouldBe 3L
    }

    @Test
    fun `an empty row stream still creates the table`() {
        val result = runBlocking { staging.stageRows("stg_empty", columns, emptySequence()) }

        result.rowsStaged shouldBe 0L
        staging.readFromStaging { scalarLong(it, "SELECT COUNT(*) FROM \"stg_empty\"") } shouldBe 0L
    }

    @Test
    fun `a duplicate table name fails exactly as stage does`() {
        runBlocking { staging.stageRows("stg_dup", columns, sequenceOf(listOf(1, "a"))) }

        val thrown =
            shouldThrow<StagingTableAlreadyExistsException> {
                runBlocking { staging.stageRows("stg_dup", columns, sequenceOf(listOf(2, "b"))) }
            }

        thrown.code shouldBe StagingErrorCodes.TABLE_ALREADY_EXISTS
        staging.readFromStaging { scalarLong(it, "SELECT COUNT(*) FROM \"stg_dup\"") } shouldBe 1L
    }

    @Test
    fun `a row stream that fails mid-insert leaves no table and no claimed name behind`() {
        // The composition failure shape: the child's cursor dies after some rows were consumed.
        // The parent must not find a half-written table — and a retry must get a clean name.
        val failing =
            sequence {
                yield(listOf(1, "a"))
                yield(listOf(2, "b"))
                throw ChildCursorDied()
            }

        shouldThrow<ChildCursorDied> {
            runBlocking { staging.stageRows("stg_partial", columns, failing) }
        }

        staging.readFromStaging { tableCount(it) } shouldBe 0
        // The name was released: staging the same table again succeeds.
        runBlocking { staging.stageRows("stg_partial", columns, sequenceOf(listOf(9, "z"))) }.rowsStaged shouldBe 1L
    }

    /** The mid-stream child failure, as a named type (detekt: no generic throws). */
    private class ChildCursorDied : RuntimeException("child cursor died mid-stream")

    /**
     * F2 — §4.5's column-label validation is the **security boundary**
     * ([StagingIdentifiers]'s KDoc: "validation is the security boundary, quoting the second
     * layer"), and it applies here for the same reason it applies to [Staging.stage]: these labels
     * come from user SQL. They are the child's caller-node result labels, read by
     * `ResultRowReader.schemaOf` off a `SELECT … AS "whatever the author typed"` — the identical
     * provenance, one execution removed. `stageRows` went straight to `createTable`, so the
     * identical DQL statement was rejected on the tempdb path and accepted on the composition one.
     */
    @Test
    fun `case-insensitively duplicated child labels fail with the catalogued code, as stage does`() {
        // `SELECT 1 AS total, 2 AS "TOTAL"` in the child's caller node: H2 folds unquoted
        // identifiers to upper case, so these two collide in the generated CREATE TABLE.
        val colliding =
            listOf(
                ColumnSchema("total", LogicalType.INTEGER),
                ColumnSchema("TOTAL", LogicalType.INTEGER),
            )

        val thrown =
            shouldThrow<StagingInvalidColumnNameException> {
                runBlocking { staging.stageRows("stg_collide", colliding, sequenceOf(listOf(1, 2))) }
            }

        // Not the generic `staging_failed` a raw H2 duplicate-column SQLException maps to.
        thrown.code shouldBe StagingErrorCodes.INVALID_COLUMN_NAME
        staging.readFromStaging { tableCount(it) } shouldBe 0
    }

    @Test
    fun `a malformed child label is refused before it reaches generated DDL`() {
        listOf("total revenue", "tötal", "1st", "a".repeat(MAX_LABEL_CHARS + 1)).forEachIndexed { i, label ->
            val thrown =
                shouldThrow<StagingInvalidColumnNameException> {
                    runBlocking {
                        staging.stageRows("stg_bad_$i", listOf(ColumnSchema(label, LogicalType.STRING)), sequenceOf(listOf("x")))
                    }
                }
            thrown.code shouldBe StagingErrorCodes.INVALID_COLUMN_NAME
        }

        // Refused before `createTable`, so no name was claimed and no table exists.
        staging.readFromStaging { tableCount(it) } shouldBe 0
    }

    @Test
    fun `a row with the wrong arity is rejected before it reaches the table`() {
        shouldThrow<IllegalArgumentException> {
            runBlocking { staging.stageRows("stg_arity", columns, sequenceOf(listOf(1, "a", "extra"))) }
        }

        staging.readFromStaging { tableCount(it) } shouldBe 0
    }

    private companion object {
        /** H2's identifier limit, the bound [StagingIdentifiers.COLUMN_NAME] encodes. */
        const val MAX_LABEL_CHARS = 63
    }
}
