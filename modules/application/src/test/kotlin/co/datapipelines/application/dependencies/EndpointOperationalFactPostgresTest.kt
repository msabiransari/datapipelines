package co.datapipelines.application.dependencies

import co.datapipelines.application.SharedPostgres
import co.datapipelines.application.endpoints.PublishedEndpointVersionRepository
import co.datapipelines.dependencies.Authority
import co.datapipelines.dependencies.Deadline
import co.datapipelines.dependencies.MetadataSnapshot
import co.datapipelines.dependencies.Operational
import co.datapipelines.dependencies.Outcome
import co.datapipelines.dependencies.Purpose
import co.datapipelines.dependencies.ReadIsolation
import co.datapipelines.dependencies.Relationship
import co.datapipelines.dependencies.ResolveContext
import co.datapipelines.dependencies.Selector
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.pipeline.release.ReleaseNodeIdentity
import co.datapipelines.pipeline.release.ResolvedNode
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.util.UUID

/**
 * #902 (dependency-graph record §11.1 `api_endpoint { enabled: boolean }`, §5.3) on a REAL PostgreSQL: an API node's
 * operational fact is the entity's `is_enabled`, read by the SAME statement as the version row — never a second one —
 * and it is shown, never part of the node's identity, body hash, current pointer or lifecycle. A node that does not
 * resolve (absent version, absent path, another workspace's path) is [Outcome.Missing], which carries no facts.
 *
 * The provider is the production descriptor over the production [EndpointDependencySource] and the real versions
 * repository, whose template runs over [ObservedStatements] so every real statement is counted.
 */
class EndpointOperationalFactPostgresTest {
    private val statements = ObservedStatements(SharedPostgres.dataSource())
    private val jdbc = NamedParameterJdbcTemplate(SharedPostgres.dataSource())
    private val provider =
        ReleaseDependencyFamilies
            .descriptor(
                ReleaseFamily.ENDPOINT,
                EndpointDependencySource(PublishedEndpointVersionRepository(NamedParameterJdbcTemplate(statements.dataSource))),
                StatementCeiling.NONE,
            ).provider
    private var userId: UUID = UUID.randomUUID()
    private var workspaceId: UUID = UUID.randomUUID()
    private var endpointId: UUID = UUID.randomUUID()

    @BeforeEach
    fun seed() {
        jdbc.jdbcTemplate.execute("TRUNCATE users CASCADE")
        userId = UUID.randomUUID()
        workspaceId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO users (id, email, display_name, provider, provider_subject) VALUES (:id, :email, 'T', 'google', :sub)",
            mapOf("id" to userId, "email" to "u$userId@example.com", "sub" to "sub-$userId"),
        )
        jdbc.update(
            "INSERT INTO workspaces (id, name, display_name, created_by) VALUES (:id, :name, 'W', :owner)",
            mapOf("id" to workspaceId, "name" to "w_${workspaceId.toString().replace("-", "")}", "owner" to userId),
        )
        val pipelineId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO pipelines (id, name, display_name, owner_id, current_version, workspace_id)" +
                " VALUES (:id, :name, 'P', :owner, NULL, :ws)",
            mapOf("id" to pipelineId, "name" to PIPELINE, "owner" to userId, "ws" to workspaceId),
        )
        endpointId = UUID.randomUUID()
        // is_enabled is left to its column default (V11: BOOLEAN NOT NULL DEFAULT TRUE).
        jdbc.update(
            "INSERT INTO published_endpoints (id, workspace_id, path_pattern, pipeline_id, timeout_seconds, created_by)" +
                " VALUES (:id, :ws, :path, :pipeline, 30, :owner)",
            mapOf("id" to endpointId, "ws" to workspaceId, "path" to PATH, "pipeline" to pipelineId, "owner" to userId),
        )
        jdbc.update(
            "INSERT INTO published_endpoint_versions (endpoint_id, version, status, pipeline_name, pipeline_version, timeout_seconds," +
                " body_hash, created_by) VALUES (:id, 1, 'DRAFT', :pipeline, 1, 30, 'h1', :owner)",
            mapOf("id" to endpointId, "pipeline" to PIPELINE, "owner" to userId),
        )
        jdbc.update(
            "INSERT INTO published_endpoint_versions (endpoint_id, version, status, pipeline_name, pipeline_version, timeout_seconds," +
                " body_hash, created_by, released_at, released_by, discarded_at, discarded_by)" +
                " VALUES (:id, 2, 'DISCARDED', :pipeline, 1, 30, 'h2', :owner, now(), :owner, now(), :owner)",
            mapOf("id" to endpointId, "pipeline" to PIPELINE, "owner" to userId),
        )
        jdbc.update("UPDATE published_endpoints SET current_version = 1 WHERE id = :id", mapOf("id" to endpointId))
    }

    @Test
    fun `an enabled API resolves with ApiEndpoint(true), in one statement, its one pin unchanged`() {
        val resolved = resolvedCounted(identity(version = 1))
        resolved.facts.operational shouldBe Operational.ApiEndpoint(enabled = true)
        withClue("the pin is the one the source answered before #902: PIPELINE_PIN at pipeline_version, exact version 1") {
            provider.outgoing(resolved).map { Triple(it.relationship, it.sourcePath, it.request.selector) } shouldContainExactly
                listOf(Triple(Relationship.PIPELINE_PIN, "pipeline_version", Selector.ExactVersion(1)))
        }
    }

    @Test
    fun `disabling the API flips the fact to ApiEndpoint(false) and changes nothing else on the node`() {
        val before = resolvedCounted(identity(version = 1))
        disable()
        val after = resolvedCounted(identity(version = 1))
        after.facts.operational shouldBe Operational.ApiEndpoint(enabled = false)
        withClue("shown, never hashed (§5.3): identity, body hash, current pointer and lifecycle are the enabled read's") {
            after.identity shouldBe before.identity
            after.facts.bodyHash shouldBe before.facts.bodyHash
            after.facts.currentVersion shouldBe before.facts.currentVersion
            after.facts.lifecycle shouldBe before.facts.lifecycle
            after.body shouldBe before.body
        }
    }

    @Test
    fun `a DISCARDED version of a disabled API still resolves and carries the entity-level ApiEndpoint(false)`() {
        disable()
        val resolved = resolve(identity(version = 2)).shouldBeInstanceOf<Outcome.Resolved<ResolvedNode>>()
        resolved.facts.operational shouldBe Operational.ApiEndpoint(enabled = false)
        withClue("a DISCARDED body's pin is never walked") { provider.outgoing(resolved) shouldBe emptyList() }
    }

    @Test
    fun `an absent version, an absent path and another workspace's path are each Missing - no fact`() {
        withClue("absent version") { resolve(identity(version = 9)) shouldBe Outcome.Missing }
        withClue("absent path") {
            resolve(ReleaseNodeIdentity(workspaceId, ReleaseFamily.ENDPOINT, "/finance/v1/absent", 1)) shouldBe Outcome.Missing
        }
        withClue("another workspace's path") {
            resolve(ReleaseNodeIdentity(UUID.randomUUID(), ReleaseFamily.ENDPOINT, PATH, 1)) shouldBe Outcome.Missing
        }
    }

    private fun disable() {
        jdbc.update("UPDATE published_endpoints SET is_enabled = FALSE WHERE id = :id", mapOf("id" to endpointId))
    }

    /** Resolves [identity], asserting the read issued exactly ONE real statement (the version read carries the fact). */
    private fun resolvedCounted(identity: ReleaseNodeIdentity): Outcome.Resolved<ResolvedNode> {
        statements.observed.clear()
        val outcome = resolve(identity)
        withClue("one statement per resolve: the flag rides the version read, never a second statement") {
            statements.observed shouldHaveSize 1
        }
        return outcome.shouldBeInstanceOf<Outcome.Resolved<ResolvedNode>>()
    }

    private fun resolve(identity: ReleaseNodeIdentity): Outcome<ResolvedNode> {
        val request = ReleaseDependencyFamilies.request(identity)
        return provider.resolveMany(listOf(request), context(identity.workspaceId)).getValue(request)
    }

    private fun identity(version: Int) = ReleaseNodeIdentity(workspaceId, ReleaseFamily.ENDPOINT, PATH, version)

    private fun context(workspace: UUID) =
        ResolveContext(
            workspace,
            MetadataSnapshot.READ_COMMITTED,
            Purpose.RELEASE_PLANNING,
            Authority.System,
            Deadline.none(),
            ReadIsolation.NONE,
        )

    private companion object {
        const val PATH = "/finance/v1/revenue"
        const val PIPELINE = "finance/revenue"
    }
}
