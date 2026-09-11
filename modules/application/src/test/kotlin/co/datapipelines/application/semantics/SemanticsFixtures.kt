package co.datapipelines.application.semantics

import co.datapipelines.auth.AuditEventSink
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.MembershipFlags
import co.datapipelines.auth.Scope
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.semantics.FactRef
import co.datapipelines.datasources.semantics.LearnedFact
import co.datapipelines.datasources.semantics.LearnedFactKind
import co.datapipelines.datasources.semantics.LearnedFactScope
import co.datapipelines.datasources.semantics.LearnedFactTrust
import co.datapipelines.typesystem.Dialect
import java.time.Instant
import java.util.UUID

/** Shared builders for the learned-semantics suites. */
internal object SemanticsFixtures {
    val USER: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    val ACME: UUID = UUID.fromString("44444444-4444-4444-4444-444444444444")
    val GLOBEX: UUID = UUID.fromString("55555555-5555-5555-5555-555555555555")
    val PIPELINE: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")

    val warehouse =
        Datasource(
            name = "warehouse",
            displayName = "Warehouse",
            dialect = Dialect.POSTGRES,
            jdbcUrl = "jdbc:postgresql://db.internal:5432/app",
            username = "app",
            secret = "secret",
        )

    fun principal(
        workspaceId: UUID = ACME,
        flags: MembershipFlags = MembershipFlags(author = true),
        keyId: String? = "dpk_ABCDEFGHIJKL",
    ): AuthenticatedPrincipal =
        AuthenticatedPrincipal(
            userId = USER,
            email = "agent@example.test",
            displayName = "Agent",
            scopes = setOf(Scope.AUTHOR),
            authMethod = if (keyId == null) AuthMethod.OIDC else AuthMethod.API_KEY,
            keyId = keyId,
            workspace = WorkspaceContext(workspaceId, if (workspaceId == ACME) "acme" else "globex", flags),
        )

    fun fact(
        id: UUID = UUID.randomUUID(),
        scope: LearnedFactScope = LearnedFactScope.DATASOURCE,
        kind: LearnedFactKind = LearnedFactKind.UNIT,
        refs: List<FactRef> = listOf(FactRef(null, "orders", "amount")),
        text: String = "amount is in cents",
        trust: LearnedFactTrust = LearnedFactTrust.OBSERVED,
        recordedIn: UUID = ACME,
        sourcePipelineId: UUID? = null,
        fingerprint: String = "orders=abc",
    ): LearnedFact =
        LearnedFact(
            id = id,
            scope = scope,
            workspaceId = if (scope == LearnedFactScope.WORKSPACE) recordedIn else null,
            datasourceName = warehouse.name,
            kind = kind,
            fact = text,
            refs = refs,
            evidenceSql = "SELECT amount FROM orders LIMIT 5",
            evidenceSummary = "amount=1250 | amount=300",
            trust = trust,
            schemaFingerprint = fingerprint,
            recordedBy = USER,
            recordedVia = "mcp",
            recordedIn = recordedIn,
            sourcePipelineId = sourcePipelineId,
            sourceVersion = null,
            recordedAt = Instant.parse("2026-09-11T10:00:00Z"),
            verifiedBy = null,
            verifiedAt = null,
            supersedes = null,
            retiredAt = null,
            retiredReason = null,
        )
}

/**
 * A REAL in-memory sink, never a strict mock: the contract under test is "you must CALL me",
 * and a strict mock passes precisely when the call is missing (MISTAKES: "a strict mock makes a
 * missing call unobservable"). The suites read the recorded rows.
 */
internal class RecordingAuditSink : AuditEventSink {
    data class Row(
        val event: String,
        val userId: UUID?,
        val keyId: String?,
        val details: Map<String, Any?>,
    )

    val rows = mutableListOf<Row>()

    override fun log(
        event: String,
        userId: UUID?,
        keyId: String?,
        sourceIp: String?,
        userAgent: String?,
        details: Map<String, Any?>,
    ) {
        rows += Row(event, userId, keyId, details)
    }
}
