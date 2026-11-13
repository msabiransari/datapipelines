package co.datapipelines.web.incoming

import co.datapipelines.application.dependencies.PostgresStatementCeiling
import co.datapipelines.auth.WorkspaceRepository
import co.datapipelines.datasources.DatasourceErrorCodes
import co.datapipelines.datasources.DatasourceReference
import co.datapipelines.datasources.DatasourceRepository
import co.datapipelines.datasources.DefaultDatasourceRegistry
import co.datapipelines.datasources.ParameterSetDatasourceReference
import co.datapipelines.datasources.crypto.CredentialEncryptor
import co.datapipelines.dependencies.FamilyRegistry
import co.datapipelines.pipeline.Node
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.PipelineNodeRef
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.PipelineVersionStatus.DISCARDED
import co.datapipelines.pipeline.PipelineVersionStatus.RELEASED
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.WriteMode
import co.datapipelines.web.SharedPostgres
import co.datapipelines.web.config.DependencyGraphNonArtifactConfiguration
import co.datapipelines.web.config.DomainConfiguration
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/**
 * #877 §C — the `datasource.in_use` delete guard (datasources §6.2, #873 U2 erratum (7)) with its references port
 * ADAPTED over the datasource family's two inverse arms: the production `datasourceReferences` bean over the production
 * datasource descriptor, the real [DefaultDatasourceRegistry.delete], a real PostgreSQL. A pipeline's `source` refuses, a
 * PIPELINE node's `output.datasource` refuses, a parameter-set selector refuses — in a DISCARDED version of a set that
 * still has a live version (the ANY rule) — and an unreferenced datasource deletes.
 *
 * A set whose EVERY version is DISCARDED does not lock the name: the base scan's `LIVE_S` predicate (an entity with a
 * DRAFT or RELEASED version) is preserved as the brief's per-family table requires; the case is pinned here so a
 * harmonization would be red (the brief's "every version DISCARDED refuses" wording is a recorded deviation).
 */
class DatasourceDeleteGuardAdapterIntegrationTest {
    private val dataSource = SharedPostgres.dataSource()
    private val jdbc = NamedParameterJdbcTemplate(dataSource)
    private val seed = CompleteAnswerSeed(jdbc)
    private val registry =
        FamilyRegistry(listOf(DependencyGraphNonArtifactConfiguration().datasourceDependencyFamily(PostgresStatementCeiling(dataSource))))
    private val references =
        DomainConfiguration().datasourceReferences(
            StaticListableBeanFactory(mapOf("dependencyFamilyRegistry" to registry)).getBeanProvider(FamilyRegistry::class.java),
            WorkspaceRepository(jdbc),
        )
    private val datasources = DefaultDatasourceRegistry(DatasourceRepository(jdbc), mockk<CredentialEncryptor>(), references = references)

    @BeforeEach
    fun reset() {
        seed.reset()
        listOf(WAREHOUSE, LANDING, SELECTOR, ABANDONED, UNUSED).forEach(::datasource)
        seed.pipelineNodes("acme/p/report", 1, RELEASED, listOf(dql("read", WAREHOUSE)))
        seed.pipeline("acme/p/child", 1, RELEASED)
        seed.pipelineNodes(
            "acme/p/loader",
            1,
            RELEASED,
            listOf(
                Node(
                    id = "run",
                    description = "",
                    type = NodeType.PIPELINE,
                    source = "",
                    template = TemplateRef(),
                    output = NodeOutput.Datasource(LANDING, "t", WriteMode.REPLACE),
                    dependsOn = emptyList(),
                    pipeline = PipelineNodeRef("acme/p/child", 1),
                ),
            ),
        )
        seed.parameterSet("acme/s/filters", 1, RELEASED, selectors = emptyList())
        selectorSet("acme/s/filters", 2, DISCARDED, SELECTOR)
        selectorSet("acme/s/gone", 1, DISCARDED, ABANDONED)
    }

    @Test
    fun `a pipeline node's source refuses the delete - datasource in_use naming the node`() {
        val refused = datasources.delete(WAREHOUSE)

        assertSoftly {
            refused.deleted shouldBe false
            refused.errorCode shouldBe DatasourceErrorCodes.IN_USE
            refused.references shouldBe listOf(DatasourceReference("acme/p/report", 1, "RELEASED", "read", seed.workspaceId))
            refused.parameterSetReferences.shouldBeEmpty()
        }
    }

    @Test
    fun `a PIPELINE node's output datasource refuses the delete`() {
        val refused = datasources.delete(LANDING)

        refused.errorCode shouldBe DatasourceErrorCodes.IN_USE
        refused.references shouldBe listOf(DatasourceReference("acme/p/loader", 1, "RELEASED", "run", seed.workspaceId))
    }

    @Test
    fun `a parameter-set-only pin in a DISCARDED version of a live set refuses the delete`() {
        val refused = datasources.delete(SELECTOR)

        assertSoftly {
            refused.errorCode shouldBe DatasourceErrorCodes.IN_USE
            refused.references.shouldBeEmpty()
            refused.parameterSetReferences shouldBe
                listOf(
                    ParameterSetDatasourceReference("acme/s/filters", 2, "DISCARDED", "parameters[0].source.datasource", seed.workspaceId),
                )
        }
    }

    @Test
    fun `an unreferenced datasource deletes - and so does one only an all-DISCARDED set names, the base rule kept`() {
        assertSoftly {
            datasources.delete(UNUSED).deleted shouldBe true
            datasources.delete(ABANDONED).deleted shouldBe true
        }
    }

    private fun datasource(name: String) =
        jdbc.update(
            "INSERT INTO datasources (name, display_name, dialect, jdbc_url, credential_kind, created_by)" +
                " VALUES (:name, :name, 'POSTGRES', 'jdbc:postgresql://x/y', 'none', :actor)",
            mapOf("name" to name, "actor" to seed.userId),
        )

    /** A set version with ONE selector parameter reading [datasource] (no template). */
    private fun selectorSet(
        name: String,
        version: Int,
        status: PipelineVersionStatus,
        datasource: String,
    ) = seed.parameterSetBody(name, version, status) {
        """{"display_name":"$name","parameters":[{"name":"p0","label":"P0","type":"INTEGER","kind":"SELECT",""" +
            """"source":{"sql":"SELECT 1","datasource":"$datasource"}}]}"""
    }

    private fun dql(
        id: String,
        source: String,
    ) = Node(
        id = id,
        description = "",
        type = NodeType.DQL,
        source = source,
        template = TemplateRef("acme/t/q.sql", 1),
        output = NodeOutput.Caller,
        dependsOn = emptyList(),
    )

    private companion object {
        const val WAREHOUSE = "warehouse"
        const val LANDING = "landing"
        const val SELECTOR = "selector-only"
        const val ABANDONED = "abandoned"
        const val UNUSED = "unused"
    }
}
