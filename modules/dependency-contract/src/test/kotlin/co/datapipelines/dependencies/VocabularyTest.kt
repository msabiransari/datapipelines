package co.datapipelines.dependencies

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Every closed vocabulary enumerated with its exact count (A47 type half, non-vacuity): a value added or dropped is a red
 * test here before it is a wire change, and G4 generates its payload goldens from these same enumerations.
 */
class VocabularyTest {
    @Test
    fun `FamilyKey PRODUCTION is exactly the eight record section 4 families with their capability pairs`() {
        FamilyKey.PRODUCTION.size shouldBe 8
        FamilyKey.PRODUCTION.map { (key, caps) -> "${key.wire}:${caps.release.wire}/${caps.promotion.wire}" } shouldContainExactly
            listOf(
                "pipeline:ARTIFACT_RELEASE/VERSIONED_ARTIFACT",
                "template:ARTIFACT_RELEASE/VERSIONED_ARTIFACT",
                "parameter_set:ARTIFACT_RELEASE/VERSIONED_ARTIFACT",
                "visualization:ARTIFACT_RELEASE/VERSIONED_ARTIFACT",
                "dashboard:ARTIFACT_RELEASE/VERSIONED_ARTIFACT",
                "api_endpoint:ARTIFACT_RELEASE/VERSIONED_ARTIFACT",
                "schedule:NONE/NONE",
                "datasource:NONE/NONE",
            )
        FamilyKey.PRODUCTION.keys shouldNotContain TEST_WIDGET
    }

    @Test
    fun `a family key is a lower-case snake_case token`() {
        listOf("", "Pipeline", "api-endpoint", "1x", "a".repeat(65)).forEach { bad ->
            runCatching { FamilyKey(bad) }.isFailure shouldBe true
        }
        FamilyKey("test_widget").toString() shouldBe "test_widget"
    }

    @Test
    fun `the section 11_1 relationship tokens are exactly the twelve section 6 rows`() {
        Relationship.entries.map { "${it.wire}:${it.from}->${it.to}" } shouldContainExactly
            listOf(
                "TEMPLATE:pipeline->template",
                "CHILD_PIPELINE:pipeline->pipeline",
                "DATASOURCE:pipeline->datasource",
                "IMPORT:template->template",
                "PARAMETER_SOURCE_TEMPLATE:parameter_set->template",
                "PARAMETER_SOURCE_DATASOURCE:parameter_set->datasource",
                "TRANSFORM_TEMPLATE:visualization->template",
                "PARAMETER_SET:dashboard->parameter_set",
                "SOURCE_PIPELINE:dashboard->pipeline",
                "VISUALIZATION:dashboard->visualization",
                "PIPELINE_PIN:api_endpoint->pipeline",
                "EXECUTOR_TARGET:schedule->pipeline",
            )
        Relationship.entries
            .map { it.from to it.to }
            .distinct()
            .size shouldBe 12 // one token per family pair
        Relationship.between(FamilyKey.TEMPLATE, FamilyKey.PIPELINE) shouldBe null
    }

    @Test
    fun `the constraint, diagnostic and limit vocabularies are closed and counted`() {
        ConstraintKind.entries.map { it.wire } shouldContainExactly listOf("parameter_names", "datasource_access", "datasource_dialect")
        listOf(
            Constraint.ParameterNames(listOf("region")),
            Constraint.DatasourceAccess(AccessMode.READ),
            Constraint.DatasourceDialect("postgresql"),
        ).map { it.kind } shouldContainExactly ConstraintKind.entries
        AccessMode.entries.map { it.wire } shouldContainExactly listOf("read", "write")
        DiagnosticKind.entries.map { it.wire } shouldContainExactly
            listOf("MISSING", "DISCARDED", "UNSUPPORTED_FAMILY", "CYCLE", "LIMIT", "INCOMPLETE_VIEW")
        LimitKind.entries.map { it.wire } shouldContainExactly listOf("depth", "nodes", "edges", "diagnostics", "wire_bytes")
    }

    @Test
    fun `the status dimensions are separate closed types and Resolution has no hidden value`() {
        Resolution.entries.map { it.wire } shouldContainExactly listOf("resolved", "missing", "unsupported")
        Lifecycle.entries.map { it.wire } shouldContainExactly listOf("DRAFT", "RELEASED", "DISCARDED")
        ScheduleCondition.entries.map { it.wire } shouldContainExactly listOf("enabled", "paused", "blocked")
        OperationalKind.entries.map { it.family.wire } shouldContainExactly
            listOf("schedule", "api_endpoint", "datasource", "visualization")
        listOf(
            Operational.Schedule(ScheduleCondition.ENABLED),
            Operational.ApiEndpoint(enabled = true),
            Operational.Datasource(readonly = false),
            Operational.Visualization(evidenceReady = true),
        ).map { it.kind } shouldContainExactly OperationalKind.entries
        SelectorKind.entries.map { it.wire } shouldContainExactly listOf("exact_version", "current", "entity")
        listOf(Selector.ExactVersion(1), Selector.Current, Selector.Entity).map { it.kind } shouldContainExactly SelectorKind.entries
        VersionKind.entries.map { it.wire } shouldContainExactly listOf("artifact", "revision", "unversioned")
        listOf(VersionInfo.Artifact(1), VersionInfo.Revision(7), VersionInfo.Unversioned).map { it.kind } shouldContainExactly
            VersionKind.entries
        ReleaseCapability.entries.map { it.wire } shouldContainExactly listOf("ARTIFACT_RELEASE", "NONE")
        PromotionCapability.entries.map { it.wire } shouldContainExactly listOf("VERSIONED_ARTIFACT", "NONE")
        Purpose.entries.map { it.name } shouldContainExactly listOf("INTERACTIVE_READ", "RELEASE_PLANNING", "LIFECYCLE_GUARD")
    }

    @Test
    fun `the provider context carries server-built authority and no client flag`() {
        val fields =
            ResolveContext::class.java.declaredFields
                .filterNot { it.isSynthetic }
                .map { it.name }
        // readIsolation (#868 R1, round 3) is chosen by the server code that opened the transaction, never by a client.
        fields shouldContainExactly listOf("workspaceId", "snapshot", "purpose", "authority", "deadline", "readIsolation")
        fields.none { Regex("(?i)ignore|permission|flag|client|override").containsMatchIn(it) } shouldBe true
    }

    @Test
    fun `the provider context carries the section 25_4 statement bound on its deadline - min(statement limit, time left)`() {
        val clock = SettableClock(java.time.Instant.parse("2026-11-11T00:00:00Z"))
        val deadline = Deadline.after(Duration.ofSeconds(30), clock).withStatementLimit(TestGraph.LIMITS.statementTimeout)
        val context =
            ResolveContext(
                java.util.UUID.randomUUID(),
                MetadataSnapshot.READ_COMMITTED,
                Purpose.RELEASE_PLANNING,
                Authority.System,
                deadline,
                ReadIsolation.NONE,
            )
        context.statementBound() shouldBe Duration.ofSeconds(5)
        clock.advance(Duration.ofSeconds(28))
        context.statementBound() shouldBe Duration.ofSeconds(2)
    }

    @Test
    fun `GraphLimits carries the section 25_4 defaults as named constants and refuses values outside their ranges`() {
        val limits = TestGraph.LIMITS
        limits.resolveBatchSize shouldBe 256
        limits.metadataTimeout shouldBe Duration.ofSeconds(30)
        limits.statementTimeout shouldBe Duration.ofSeconds(5)
        listOf<() -> GraphLimits>(
            { limits.copy(resolveBatchSize = 0) },
            { limits.copy(resolveBatchSize = 257) },
            { limits.copy(metadataTimeout = Duration.ofSeconds(121)) },
            { limits.copy(statementTimeout = Duration.ofSeconds(31)) },
            { limits.copy(metadataTimeout = Duration.ofSeconds(4)) }, // statement 5 s > metadata 4 s
            { limits.copy(depth = 0) },
            { limits.copy(wireBytes = 0) },
        ).forEach { runCatching(it).isFailure shouldBe true }
    }

    @Test
    fun `a deadline counts down on its clock and none never expires`() {
        val clock = SettableClock(java.time.Instant.parse("2026-11-10T00:00:00Z"))
        val deadline = Deadline.after(Duration.ofSeconds(30), clock)
        deadline.remaining() shouldBe Duration.ofSeconds(30)
        deadline.expired() shouldBe false
        clock.advance(Duration.ofSeconds(31))
        deadline.remaining() shouldBe Duration.ZERO
        deadline.expired() shouldBe true
        Deadline.none().remaining() shouldBe null
        Deadline.none().expired() shouldBe false
        Deadline.none().unbounded shouldBe true
        deadline.unbounded shouldBe false
        runCatching { Deadline.after(Duration.ofSeconds(-1), clock) }.isFailure shouldBe true
    }

    @Test
    fun `snapshot invariants refuse a guessed acyclic flag or an order for an incomplete graph`() {
        val base =
            GraphSnapshot(1, emptyList(), emptyList(), emptyList(), emptyList(), true, true, emptyList(), FIXED_CLOCK.instant(), "fp1:x")
        runCatching { base.copy(acyclic = null) }.isFailure shouldBe true
        runCatching { base.copy(complete = false) }.isFailure shouldBe true
        runCatching { base.copy(dependencyFirst = null) }.isFailure shouldBe true
        runCatching { Diagnostic(DiagnosticKind.LIMIT, null, null, "x") }.isFailure shouldBe true
        runCatching { Selector.ExactVersion(0) }.isFailure shouldBe true
    }
}
