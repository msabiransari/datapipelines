package co.datapipelines.web.ui

import co.datapipelines.auth.ApiKeyExpiryInvalidException
import co.datapipelines.auth.ApiKeyKind
import co.datapipelines.auth.KeyRole
import co.datapipelines.auth.RolePermissions
import co.datapipelines.auth.WorkspaceRole
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.time.Instant

/**
 * 091 — the API-key form's option tables and its expiry arithmetic.
 *
 * The class exists because the PAGE renders these options and the PARTIAL validates what comes
 * back, and a select whose values the server does not recognise is the classic form defect: it
 * looks right until someone picks the last option. Every case below is therefore a statement
 * about BOTH sides at once.
 */
class ApiKeyFormTest {
    private val now = Instant.parse("2026-09-08T12:00:00Z")

    @Test
    fun `every rendered expiry option resolves - the select cannot offer what the server refuses`() {
        // The whole point of one table: iterate the rendered options and resolve each. A new
        // option added to EXPIRY_CHOICES without a resolution rule fails here, not in a modal.
        assertAll(
            ApiKeyForm.EXPIRY_CHOICES.map { choice ->
                {
                    withClue(choice.wire) {
                        val resolved = ApiKeyForm.resolveExpiry(choice.wire, "2026-12-01", now)
                        // "never" is the one option that legitimately resolves to no expiry.
                        (resolved == null) shouldBe (choice.wire == ApiKeyForm.NEVER)
                    }
                }
            },
        )
    }

    @Test
    fun `a preset is N days from now, and an absent value means never`() {
        assertAll(
            { ApiKeyForm.resolveExpiry("1", null, now) shouldBe Instant.parse("2026-09-09T12:00:00Z") },
            { ApiKeyForm.resolveExpiry("7", null, now) shouldBe Instant.parse("2026-09-15T12:00:00Z") },
            { ApiKeyForm.resolveExpiry("30", null, now) shouldBe Instant.parse("2026-10-08T12:00:00Z") },
            { ApiKeyForm.resolveExpiry("90", null, now) shouldBe Instant.parse("2026-12-07T12:00:00Z") },
            // A form that posts nothing at all means "never" — the pre-091 default, so an old
            // client or a scripted POST keeps meaning exactly what it meant.
            { ApiKeyForm.resolveExpiry(null, null, now) shouldBe null },
            { ApiKeyForm.resolveExpiry("", null, now) shouldBe null },
            { ApiKeyForm.resolveExpiry("never", null, now) shouldBe null },
        )
    }

    @Test
    fun `a custom date expires at the END of the day it names`() {
        // A human typing 2026-12-01 means "valid through that day". Resolving to its 00:00
        // would expire the key before the day it names — off by a whole day, in the direction
        // that breaks an agent at midnight.
        ApiKeyForm.resolveExpiry("custom", "2026-12-01", now) shouldBe Instant.parse("2026-12-02T00:00:00Z")
    }

    @Test
    fun `every bad expiry is a catalogued 400 with a reason, never a silently unexpiring key`() {
        // The failure mode this rules out: refusing by falling back to "never" would hand out a
        // credential BROADER than the one asked for, which is the wrong way to fail.
        assertAll(
            { reasonFor { ApiKeyForm.resolveExpiry("3000", null, now) } shouldBe "unknown_preset" },
            { reasonFor { ApiKeyForm.resolveExpiry("custom", null, now) } shouldBe "date_missing" },
            { reasonFor { ApiKeyForm.resolveExpiry("custom", "", now) } shouldBe "date_missing" },
            { reasonFor { ApiKeyForm.resolveExpiry("custom", "next tuesday", now) } shouldBe "date_unparseable" },
            { reasonFor { ApiKeyForm.resolveExpiry("custom", "2026-13-45", now) } shouldBe "date_unparseable" },
            { reasonFor { ApiKeyForm.resolveExpiry("custom", "2020-01-01", now) } shouldBe "date_in_past" },
            // TODAY is not in the past: it resolves to the end of today, which is still ahead.
            { ApiKeyForm.resolveExpiry("custom", "2026-09-08", now) shouldBe Instant.parse("2026-09-09T00:00:00Z") },
        )
    }

    @Test
    fun `the offending value is echoed back, truncated, and only from the caller's own input`() {
        val refused = shouldThrow<ApiKeyExpiryInvalidException> { ApiKeyForm.resolveExpiry("custom", "nope", now) }

        refused.details["reason"] shouldBe "date_unparseable"
        refused.details["value"] shouldBe "nope"
        refused.status shouldBe 400
    }

    @Test
    fun `the cards follow the caller's permissions - the mcp roles are offerable's answer (A14)`() {
        assertAll(
            // An author: the mcp card with `author` only, no endpoint/server cards.
            {
                val choices = ApiKeyForm.kindChoices(listOf(WorkspaceRole.AUTHOR), mayCreateApiKeys = false, isSuperAdmin = false)
                choices.map { it.wire } shouldBe listOf("mcp")
                choices.single().roles.map { it.wire } shouldBe listOf("author")
            },
            // A workspace admin: every mcp role, the endpoint card, no server card.
            {
                val choices =
                    ApiKeyForm.kindChoices(
                        RolePermissions.KEY_OFFERABLE,
                        mayCreateApiKeys = true,
                        isSuperAdmin = false,
                    )
                choices.map { it.wire } shouldBe listOf("mcp", "endpoint")
                choices.first().roles.map { it.wire } shouldBe listOf("author", "promoter", "workspace_admin")
            },
            // A super admin sees the server card too.
            {
                val choices =
                    ApiKeyForm.kindChoices(RolePermissions.KEY_OFFERABLE, mayCreateApiKeys = true, isSuperAdmin = true)
                choices.map { it.wire } shouldBe listOf("mcp", "endpoint", "server")
            },
            // A viewer: no create permission, no mcp card at all (A15 — no card, never an empty choice).
            {
                ApiKeyForm.kindChoices(emptyList(), mayCreateApiKeys = false, isSuperAdmin = false).shouldBeEmpty()
            },
        )
    }

    @Test
    fun `each kind declares which fields it takes, and the answers match the service's rules`() {
        val byWire =
            ApiKeyForm.kindChoices(RolePermissions.KEY_OFFERABLE, mayCreateApiKeys = true, isSuperAdmin = true).associateBy { it.wire }

        assertAll(
            { byWire.getValue(ApiKeyKind.MCP.wire).takesBindings shouldBe false },
            { byWire.getValue(ApiKeyKind.ENDPOINT.wire).takesBindings shouldBe true },
            { byWire.getValue(ApiKeyKind.SERVER.wire).takesBindings shouldBe false },
            // The same statement the issuance service makes, from the other side: every kind
            // the form offers acts as its own identity (keys v2 A13) — and the form offers
            // every kind there is, because the Keys page is the ONE creation path (A15).
            { byWire.keys shouldBe ApiKeyKind.IDENTITY_KINDS.map { it.wire }.toSet() },
        )
    }

    @Test
    fun `the kinds are labelled for a person - MCP key, API key, Server key (A19, D17)`() {
        val byWire =
            ApiKeyForm.kindChoices(RolePermissions.KEY_OFFERABLE, mayCreateApiKeys = true, isSuperAdmin = true).associateBy { it.wire }
        byWire.getValue("mcp").label shouldBe "MCP key"
        byWire.getValue("endpoint").label shouldBe "API key"
        byWire.getValue("server").label shouldBe "Server key"
    }

    @Test
    fun `binding nodes are the literal prefixes, the root, and nothing with a variable in it`() {
        val nodes =
            ApiKeyForm.bindingNodes(
                listOf("/nyc/mobility/briefing", "/nyc/revenue/{borough}", "/lending"),
            )

        assertAll(
            { nodes.first() shouldBe "/" },
            { nodes shouldBe listOf("/", "/lending", "/nyc", "/nyc/mobility", "/nyc/mobility/briefing", "/nyc/revenue") },
            // The rule that makes this correct rather than tidy: EndpointAuthorizer walks the
            // ancestors of the CONCRETE request path, so a node containing `{borough}` could
            // never match a request and would be a checkbox that authorises nothing.
            { withClue("a variable node was offered") { nodes.none { it.contains("{") } shouldBe true } },
        )
    }

    @Test
    fun `a deployment with no published endpoints still offers the whole tree`() {
        // Binding at `/` is a real operator intent (authorise everything, publish later) and is
        // legal as a binding though never as an endpoint.
        ApiKeyForm.bindingNodes(emptyList()) shouldBe listOf("/")
    }

    private fun reasonFor(block: () -> Unit): Any? =
        shouldThrow<ApiKeyExpiryInvalidException> { block() }
            .details["reason"]
}
