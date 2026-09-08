package co.datapipelines.web.ui

import co.datapipelines.auth.ApiKeyExpiryInvalidException
import co.datapipelines.auth.ApiKeyKind
import co.datapipelines.auth.Scope
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
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
    fun `scope choices are the caller's own scopes and everything below them`() {
        // §7.4: a key's scopes must be a subset of its creator's. The UI filter is convenience —
        // ApiKeyService refuses a superset regardless — but offering a checkbox the server will
        // refuse is a form that lies.
        assertAll(
            { ApiKeyForm.scopeChoices(setOf(Scope.READ)).map { it.wire } shouldBe listOf("read") },
            { ApiKeyForm.scopeChoices(setOf(Scope.EXECUTE)).map { it.wire } shouldBe listOf("read", "execute") },
            { ApiKeyForm.scopeChoices(setOf(Scope.ADMIN)).map { it.wire } shouldBe listOf("read", "execute", "author", "admin") },
            { ApiKeyForm.scopeChoices(emptySet()) shouldBe emptyList() },
        )
    }

    @Test
    fun `every scope has a meaning, and none of them is an HTTP verb`() {
        assertAll(
            { ApiKeyForm.SCOPE_MEANINGS.keys shouldBe Scope.entries.toSet() },
            {
                withClue("a verb leaked into the capability vocabulary") {
                    ApiKeyForm.SCOPE_MEANINGS.values.none { meaning ->
                        listOf("GET", "POST", "PUT", "DELETE").any { meaning.contains(it) }
                    } shouldBe true
                }
            },
        )
    }

    @Test
    fun `the server kind is offered to an admin only`() {
        assertAll(
            { ApiKeyForm.kindChoices(isAdmin = false).map { it.wire } shouldBe listOf("user", "endpoint") },
            { ApiKeyForm.kindChoices(isAdmin = true).map { it.wire } shouldBe listOf("user", "endpoint", "server") },
        )
    }

    @Test
    fun `each kind declares which fields it takes, and the answers match the service's rules`() {
        val byWire = ApiKeyForm.kindChoices(isAdmin = true).associateBy { it.wire }

        assertAll(
            { byWire.getValue(ApiKeyKind.USER.wire).takesScope shouldBe true },
            { byWire.getValue(ApiKeyKind.USER.wire).takesBindings shouldBe false },
            { byWire.getValue(ApiKeyKind.ENDPOINT.wire).takesScope shouldBe false },
            { byWire.getValue(ApiKeyKind.ENDPOINT.wire).takesBindings shouldBe true },
            { byWire.getValue(ApiKeyKind.SERVER.wire).takesScope shouldBe false },
            { byWire.getValue(ApiKeyKind.SERVER.wire).takesBindings shouldBe false },
            // The same statement the issuance service makes, from the other side: a kind that
            // takes no scope is exactly a SCOPELESS kind.
            {
                val scopeless =
                    byWire.values
                        .filterNot { it.takesScope }
                        .map { it.wire }
                        .toSet()
                scopeless shouldBe ApiKeyKind.SCOPELESS.map { it.wire }.toSet()
            },
        )
    }

    @Test
    fun `the user kind is labelled for both surfaces it serves`() {
        // The owner's ruling: an agent's key over MCP and a program's key over REST are ONE
        // kind. Naming them separately would invent a distinction the system does not make.
        ApiKeyForm.kindChoices(isAdmin = false).first().label shouldBe "Agent / API key"
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
