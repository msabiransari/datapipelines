package co.datapipelines.application.compatibility

import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.DatasourceRepository
import co.datapipelines.datasources.DeleteResult
import co.datapipelines.datasources.TestResult
import co.datapipelines.datasources.ValidationResult
import co.datapipelines.datasources.pooling.ConnectionPool
import co.datapipelines.typesystem.Dialect
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * The offline report's registry (pipeline-write-transactions §7): it answers the ONE question the
 * write-set planner asks — which registration does this name resolve to in this workspace, and on
 * which engine — from the metadata store, and refuses every other call. It holds no credential,
 * builds no pool and opens no connection to a customer database: [poolFor], [testConnection] and
 * every write throw, and each refused call is counted in [refusedCalls] so a caller (and the
 * tests) can prove the report leased nothing.
 *
 * The resolved [Datasource] carries the name, display name and dialect only — no URL, username or
 * secret — because the planner reads nothing else.
 */
class RegistrationView(
    private val lookup: (name: String, workspaceId: UUID) -> Datasource?,
) : DatasourceRegistry {
    /** Calls that would have touched a customer database or a registration's definition. */
    val refusedCalls = AtomicInteger()

    override fun getVisible(
        name: String,
        workspaceId: UUID,
    ): Datasource? = lookup(name, workspaceId)

    override fun getVisibleLive(
        name: String,
        workspaceId: UUID,
    ): Datasource? = lookup(name, workspaceId)

    /** Also what the inherited `listVisible` default delegates to: refused. */
    override fun list(dialect: Dialect?): List<Datasource> = refuse("list")

    override fun get(name: String): Datasource? = refuse("get")

    override fun getLive(name: String): Datasource? = refuse("getLive")

    override fun isReadonlyLive(name: String): Boolean? = refuse("isReadonlyLive")

    override fun exists(name: String): Boolean = refuse("exists")

    override fun save(
        datasource: Datasource,
        actor: UUID,
    ): Datasource = refuse("save")

    override fun validate(datasource: Datasource): ValidationResult = refuse("validate")

    override fun delete(name: String): DeleteResult = refuse("delete")

    override fun poolFor(datasource: Datasource): ConnectionPool = refuse("poolFor")

    override fun testConnection(name: String): TestResult? = refuse("testConnection")

    private fun refuse(call: String): Nothing {
        refusedCalls.incrementAndGet()
        error("The offline compatibility report reads metadata only; '$call' is not available to it")
    }

    companion object {
        /** The production lookup: the repository's visibility predicate, mapped to identifiers and dialect only. */
        fun over(repository: DatasourceRepository): RegistrationView =
            RegistrationView { name, workspaceId ->
                repository.findVisibleByName(name, workspaceId)?.let { row ->
                    Datasource(name = row.name, displayName = row.displayName, dialect = row.dialect, jdbcUrl = "")
                }
            }
    }
}
