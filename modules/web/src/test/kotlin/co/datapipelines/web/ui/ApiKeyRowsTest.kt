package co.datapipelines.web.ui

import co.datapipelines.application.dashboards.DashboardKeyBindingRepository
import co.datapipelines.application.endpoints.EndpointKeyBinding
import co.datapipelines.application.endpoints.EndpointKeyBindingRepository
import co.datapipelines.auth.ApiKey
import co.datapipelines.auth.ApiKeyKind
import co.datapipelines.auth.KeyRole
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.time.Instant
import java.util.UUID

/**
 * 091 — the one row model the key table's three renders share (page, post-create refresh,
 * post-revoke rows).
 *
 * The derived values are what this pins: a dead key is dead whether it was revoked or simply
 * expired, and the ORDER puts live keys first — both of them decisions a template cannot make
 * and a reader would otherwise have to make by squinting at dates.
 */
class ApiKeyRowsTest {
    private val bindings = mockk<EndpointKeyBindingRepository>()
    private val dashboardBindings = mockk<DashboardKeyBindingRepository>()
    private val rows = ApiKeyRows(bindings, dashboardBindings)
    private val now = Instant.parse("2026-09-08T12:00:00Z")
    private val userId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()

    @Test
    fun `a key renders its role, who it acts as, who created it, a prefix, and relative-plus-absolute timestamps`() {
        every { bindings.findByKey("dpk_RGAXQ7T2MKLP") } returns emptyList()
        val identity = UUID.randomUUID()
        val row =
            rows
                .of(
                    listOf(
                        key(
                            id = "dpk_RGAXQ7T2MKLP",
                            kind = ApiKeyKind.ENDPOINT,
                            createdAt = Instant.parse("2026-09-05T09:00:00Z"),
                            lastUsedAt = Instant.parse("2026-09-08T10:00:00Z"),
                            expiresAt = Instant.parse("2026-12-01T00:00:00Z"),
                        ).copy(userId = identity, createdBy = userId),
                    ),
                    now,
                    userLabels = mapOf(identity to "ci (API key)", userId to "Alice"),
                ).single()

        assertAll(
            // `dpk_` plus eight characters (12 total, D16's top-bar length) — enough to
            // recognise a key, useless to anyone else.
            { row.prefix shouldBe "dpk_RGAXQ7T2…" },
            // #215: the key role's label, its own identity, and its creator — three different facts.
            { row.role shouldBe "api caller" },
            { row.actsAs shouldBe "ci (API key)" },
            { row.createdBy shouldBe "Alice" },
            { row.createdRelative shouldBe "3 days ago" },
            { row.createdAbsolute shouldBe "2026-09-05 09:00 UTC" },
            { row.lastUsedRelative shouldBe "2 hours ago" },
            // 83 days and 12 hours — ROUNDED, as `RelativeTime` does inside its unit.
            { row.expiresRelative shouldBe "in 84 days" },
            { row.expiresAbsolute shouldBe "2026-12-01 00:00 UTC" },
            { row.isLive shouldBe true },
        )
    }

    @Test
    fun `never is the word for both null cases, and the hover value is absent with it`() {
        val row = rows.of(listOf(key()), now).single()

        assertAll(
            { row.lastUsedRelative shouldBe "never" },
            { row.lastUsedAbsolute shouldBe null },
            { row.expiresRelative shouldBe "never" },
            { row.expiresAbsolute shouldBe null },
        )
    }

    @Test
    fun `an expired key is as dead as a revoked one, and says which`() {
        // §7.3 step 5 refuses an expired key exactly as step 3 refuses a revoked one. A table
        // that showed a live-looking row with a past date in it would be the screen disagreeing
        // with the authenticator.
        val expired = rows.of(listOf(key(expiresAt = Instant.parse("2026-09-01T00:00:00Z"))), now).single()
        val revoked = rows.of(listOf(key(isRevoked = true)), now).single()

        assertAll(
            { expired.isExpired shouldBe true },
            { expired.isRevoked shouldBe false },
            { expired.expiresRelative shouldBe "expired" },
            { expired.isLive shouldBe false },
            { revoked.isLive shouldBe false },
        )
    }

    @Test
    fun `live keys come first, newest first within each group`() {
        val ordered =
            rows.of(
                listOf(
                    key(id = "dpk_OLDLIVE0001", createdAt = Instant.parse("2026-08-01T00:00:00Z")),
                    key(id = "dpk_DEADKEY0001", createdAt = Instant.parse("2026-09-07T00:00:00Z"), isRevoked = true),
                    key(id = "dpk_NEWLIVE0001", createdAt = Instant.parse("2026-09-06T00:00:00Z")),
                ),
                now,
            )

        ordered.map { it.id } shouldBe listOf("dpk_NEWLIVE0001", "dpk_OLDLIVE0001", "dpk_DEADKEY0001")
    }

    @Test
    fun `bindings are read for endpoint keys ONLY`() {
        every { bindings.findByKey("dpk_ENDPOINT001") } returns
            listOf(binding("/nyc/mobility"), binding("/lending"))

        val built =
            rows.of(
                listOf(
                    key(id = "dpk_ENDPOINT001", kind = ApiKeyKind.ENDPOINT),
                    key(id = "dpk_USERKEY0001"),
                    key(id = "dpk_SERVERKEY01", kind = ApiKeyKind.SERVER),
                ),
                now,
            )

        assertAll(
            { built.first { it.id == "dpk_ENDPOINT001" }.boundPaths shouldBe listOf("/lending", "/nyc/mobility") },
            { built.first { it.id == "dpk_USERKEY0001" }.boundPaths shouldBe emptyList() },
            { built.first { it.id == "dpk_SERVERKEY01" }.boundPaths shouldBe emptyList() },
            // The strict mock IS the assertion: a per-row query for kinds that cannot have
            // bindings would throw "no answer found" here rather than quietly costing a page
            // of empty reads.
            { verify(exactly = 1) { bindings.findByKey(any()) } },
        )
    }

    @Test
    fun `a dashboard key reads Dashboard key and its FOLDERS from its own table (L5)`() {
        every { dashboardBindings.findByKey("dpk_DASHBOARD1") } returns
            listOf(folder("finance/dashboards"), folder("/"))

        val built =
            rows.of(
                listOf(key(id = "dpk_DASHBOARD1", kind = ApiKeyKind.DASHBOARD)),
                now,
            )

        assertAll(
            { built.single().kindLabel shouldBe "Dashboard key" },
            { built.single().boundPaths shouldBe listOf("/", "finance/dashboards") },
            { verify(exactly = 0) { bindings.findByKey(any()) } },
            { verify(exactly = 1) { dashboardBindings.findByKey(any()) } },
        )
    }

    @Suppress("LongParameterList") // a row, spelled out
    private fun key(
        id: String = "dpk_RGAXQ7T2MKLP",
        kind: ApiKeyKind = ApiKeyKind.MCP,
        createdAt: Instant = Instant.parse("2026-09-05T09:00:00Z"),
        lastUsedAt: Instant? = null,
        expiresAt: Instant? = null,
        isRevoked: Boolean = false,
    ) = ApiKey(
        id = id,
        userId = userId,
        name = "key",
        keyHash = "hash",
        isRevoked = isRevoked,
        createdAt = createdAt,
        lastUsedAt = lastUsedAt,
        expiresAt = expiresAt,
        workspaceId = workspaceId,
        workspaceName = "acme",
        kind = kind,
        role = KeyRole.forKind(kind) ?: KeyRole.AUTHOR,
    )

    private fun binding(path: String) = EndpointKeyBinding(path, "dpk_ENDPOINT001", workspaceId, userId, now)

    private fun folder(prefix: String) =
        co.datapipelines.application.dashboards
            .DashboardKeyBinding(prefix, "dpk_DASHBOARD1", workspaceId, userId, now)
}
