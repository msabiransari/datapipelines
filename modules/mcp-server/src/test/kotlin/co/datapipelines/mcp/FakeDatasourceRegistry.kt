package co.datapipelines.mcp

import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.DeleteResult
import co.datapipelines.datasources.TestResult
import co.datapipelines.datasources.ValidationResult
import co.datapipelines.datasources.pooling.ConnectionPool
import co.datapipelines.typesystem.Dialect
import java.time.Instant
import java.util.UUID

/**
 * An in-memory [DatasourceRegistry] with REAL §5.3 visibility semantics (bound-to-the-workspace
 * or global), so tool-level gate tests exercise the true name lookup instead of a stubbed
 * `testConnection` — the strict-stub arrangement is exactly what left the missing
 * `datasources_test` gate unobservable in the 022 review (F3). [testedNames] records every
 * connectivity probe, so a gate test can prove the probe never ran.
 */
class FakeDatasourceRegistry(
    private val stored: List<Datasource>,
) : DatasourceRegistry {
    val testedNames = mutableListOf<String>()

    override fun list(dialect: Dialect?): List<Datasource> = stored.filter { dialect == null || it.dialect == dialect }

    override fun listVisible(
        dialect: Dialect?,
        workspaceId: UUID,
    ): List<Datasource> = list(dialect).filter { it.workspaceId == null || it.workspaceId == workspaceId }

    override fun get(name: String): Datasource? = stored.firstOrNull { it.name == name }

    override fun getVisible(
        name: String,
        workspaceId: UUID,
    ): Datasource? = get(name)?.takeIf { it.workspaceId == null || it.workspaceId == workspaceId }

    // The live reads (044 F6 made them abstract — a missing override is a compile error, not a
    // silent cache-through hole). An in-memory fake IS live-through: the same map serves both.
    override fun getLive(name: String): Datasource? = get(name)

    override fun isReadonlyLive(name: String): Boolean? = get(name)?.isReadonly

    override fun exists(name: String): Boolean = get(name) != null

    override fun testConnection(name: String): TestResult? {
        testedNames += name
        return get(name)?.let {
            TestResult(connected = true, testedAt = Instant.parse("2026-08-09T12:00:00Z"), serverVersion = "PostgreSQL 16.2")
        }
    }

    override fun save(
        datasource: Datasource,
        actor: UUID,
    ): Datasource = throw UnsupportedOperationException("read-only fake")

    override fun validate(datasource: Datasource): ValidationResult = throw UnsupportedOperationException("read-only fake")

    override fun delete(name: String): DeleteResult = throw UnsupportedOperationException("read-only fake")

    override fun poolFor(datasource: Datasource): ConnectionPool = throw UnsupportedOperationException("read-only fake")
}
