package co.datapipelines.datasources

import co.datapipelines.typesystem.Dialect
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * 050/R3 — the strip list in `DatasourceRow.toDatasource`, pinned to
 * [RefusedPropertyKeys.SERVER_MANAGED]: the SET the mapper strips and the SET §5.6 refuses
 * must be THE SAME set, asserted — not assumed by sharing a constant. The universe below is
 * literal on purpose: a key added to `SERVER_MANAGED` without landing in the strip behaviour
 * shows up here as a set difference, not as a silently inert refusal-list entry.
 */
class DatasourceRowServerManagedStripTest {
    /** The expected strip list, spelled out (§5.6 SERVER_MANAGED). */
    private val expectedStripped =
        setOf(
            "jdbcurl",
            "username",
            "password",
            "driverclassname",
            "datasourceclassname",
            "poolname",
            "metricregistry",
            "healthcheckregistry",
            "datasource",
            "datasourcejndi",
            "exceptionoverrideclassname",
            "readonly",
        )

    @Test
    fun `the mapper strips exactly the SERVER_MANAGED set - asserted, not assumed`() {
        // One deliberate non-canonical spelling (`ReadOnly`) proves case is not a smuggling
        // path; the benign keys prove the strip is a filter, not a wipe.
        val universe = expectedStripped + setOf("ReadOnly") + BENIGN
        val row = row(hikari = universe.associateWith { "anything" })

        val projected = row.toDatasource().properties.hikari

        projected.keys shouldContainExactly BENIGN
        // Lowercased: the strip matches case-insensitively, so the mixed-case probe counts
        // with its canonical spelling.
        (universe - projected.keys).map { it.lowercase() }.toSet() shouldBe RefusedPropertyKeys.SERVER_MANAGED
        RefusedPropertyKeys.SERVER_MANAGED shouldBe expectedStripped
    }

    @Test
    fun `the strip is hikari-only - a server-managed name in the jdbc namespace is not touched`() {
        // R3's rule is scoped to `properties.hikari`: those keys configure the POOL. The jdbc
        // map goes to the driver (a `readOnly` THERE is a driver property, not the pool flag),
        // and save-time validation already refuses the smuggle at the write boundary.
        val row =
            row(
                hikari = mapOf("readonly" to true),
                jdbc = mapOf("readonly" to true),
            )

        val projected = row.toDatasource().properties

        projected.hikari shouldBe emptyMap()
        projected.jdbc shouldBe mapOf("readonly" to true)
    }

    @Test
    fun `pool build reads the same mapper - loadWithCredential is covered by construction`() {
        // The one-boundary claim: pool build goes through toDatasource (loadWithCredential),
        // so the stored flag never reaches the HikariConfig. The registry-level proof with a
        // REAL HikariConfig lives in DatasourceRegistryIntegrationTest; this pins that the
        // row-level projection the pool factory consumes is already clean.
        val row = row(hikari = mapOf("readOnly" to true, "maximumPoolSize" to 5))

        val ds = row.toDatasource(password = "pw")

        ds.password shouldBe "pw"
        ds.properties.hikari shouldBe mapOf("maximumPoolSize" to 5)
    }

    private fun row(
        hikari: Map<String, Any?>,
        jdbc: Map<String, Any?> = emptyMap(),
    ) = DatasourceRow(
        name = "oob_row",
        displayName = "Out of band",
        description = null,
        dialect = Dialect.H2,
        jdbcUrl = "jdbc:h2:mem:oob_row",
        username = "sa",
        passwordEncrypted = ByteArray(0),
        properties = DatasourceProperties(hikari = hikari, jdbc = jdbc),
        queryTimeoutSeconds = null,
        introspectionIncludeSchemas = emptyList(),
        isReadonly = false,
        workspaceId = null,
        workspaceName = null,
        isDeleted = false,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        createdBy = UUID.randomUUID(),
    )

    private companion object {
        val BENIGN = setOf("maximumPoolSize", "connectionTimeout", "idleTimeout")
    }
}
