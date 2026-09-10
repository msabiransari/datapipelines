package co.datapipelines.web.ui

import co.datapipelines.auth.ApiKeyExpiryInvalidException
import co.datapipelines.auth.ApiKeyKind
import co.datapipelines.auth.KEY_SCOPES
import co.datapipelines.auth.Scope
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The API-key form's option lists and its one piece of arithmetic (ui-screens.md §4.18, 091).
 *
 * Extracted from both controllers because the PAGE renders the options and the PARTIAL has to
 * validate what comes back, and the two must agree on the answer. A select whose values the
 * server does not recognise is the classic form defect: it looks right until someone picks the
 * last option.
 *
 * Everything here is a pure function of its arguments — [resolveExpiry] takes `now` — so the
 * matrix is a table test with no clock and no Spring.
 */
object ApiKeyForm {
    /**
     * One kind, as the form's radio cards present it (owner ruling, 091): what it IS, in a
     * sentence, rather than an enum name a reader has to already understand.
     *
     * The `user` label is "Agent / API key" deliberately — one kind, two surfaces. What an
     * agent presents over MCP and what a program presents over REST is the same credential,
     * and giving each surface its own name would invent a distinction the system does not make.
     */
    data class KindChoice(
        val wire: String,
        val label: String,
        val summary: String,
        val takesScope: Boolean,
        val takesBindings: Boolean,
    )

    /** One scope, with the one-line meaning §7.5's table states in prose. */
    data class ScopeChoice(
        val wire: String,
        val label: String,
        val meaning: String,
    )

    /** One expiry preset. [days] is null for both "never" and "custom" — see [resolveExpiry]. */
    data class ExpiryChoice(
        val wire: String,
        val label: String,
        val days: Long?,
    )

    /**
     * The scope hierarchy, each with what it actually lets a key DO (auth.md §7.5).
     *
     * These are CAPABILITIES, not HTTP verbs, and the form offers no verb scopes at all. Two
     * reasons, both worth stating where the strings live: `execute` is a POST that writes
     * nothing (running a released pipeline), so a verb split would put it on the wrong side;
     * and an MCP tool call has no verb to split on. One vocabulary, both surfaces.
     */
    val SCOPE_MEANINGS: Map<Scope, String> =
        mapOf(
            Scope.READ to "list and inspect — runs nothing",
            Scope.EXECUTE to "run released pipelines (includes read)",
            Scope.AUTHOR to "create and change templates, pipelines, datasources (includes execute)",
            // `admin` is deliberately ABSENT (O-2): a key may not hold it, issuance refuses it
            // with `auth.key_scope_unavailable`, and a form that offers a choice the server
            // rejects is a form that teaches people the wrong model.
        )

    /** The presets, in the order the select renders them. */
    val EXPIRY_CHOICES: List<ExpiryChoice> =
        listOf(
            ExpiryChoice(NEVER, "Never", null),
            ExpiryChoice("1", "1 day", 1),
            ExpiryChoice("7", "7 days", 7),
            ExpiryChoice("30", "30 days", 30),
            ExpiryChoice("90", "90 days", 90),
            ExpiryChoice(CUSTOM, "Custom date…", null),
        )

    /**
     * The kinds this caller may mint. `server` appears only for an admin — [ApiKeyService.issue]
     * refuses it for anyone else, and rendering an option the server will refuse is a worse
     * answer than not rendering it (the UI is convenience; the server check is the guard).
     */
    fun kindChoices(isAdmin: Boolean): List<KindChoice> =
        listOfNotNull(
            KindChoice(
                wire = ApiKeyKind.USER.wire,
                label = "Agent / API key",
                summary =
                    "What an agent presents over MCP and what a program presents over REST — one kind, two " +
                        "surfaces. Its scope decides what it may do.",
                takesScope = true,
                takesBindings = false,
            ),
            KindChoice(
                wire = ApiKeyKind.ENDPOINT.wire,
                label = "Endpoint key",
                summary =
                    "Calls published endpoints and nothing else. It carries no scope at all: the paths you bind " +
                        "it to are its whole authority.",
                takesScope = false,
                takesBindings = true,
            ),
            KindChoice(
                wire = ApiKeyKind.SERVER.wire,
                label = "Server key",
                summary =
                    "The credential another deployment presents to promote into this one. No scope, no bindings " +
                        "— it opens the promotion routes and nothing else.",
                takesScope = false,
                takesBindings = false,
            ).takeIf { isAdmin },
        )

    /**
     * The scopes this caller may put on a key: their own, and everything below them (§7.4 —
     * a key's scopes must be a subset of its creator's at issue time).
     */
    fun scopeChoices(held: Set<Scope>): List<ScopeChoice> =
        Scope.entries
            // O-2: `admin` is not a KEY scope. Without this filter an issuer who satisfies it
            // walks off the end of SCOPE_MEANINGS, which no longer has an entry for it, and the
            // form throws where it should simply offer one choice fewer.
            .filter { it in KEY_SCOPES && Scope.satisfies(held, it) }
            .map { ScopeChoice(it.wire, it.wire, SCOPE_MEANINGS.getValue(it)) }

    /**
     * The binding picker's nodes, derived from the workspace's published endpoint patterns.
     *
     * **Only literal prefixes are offered, and that is a correctness rule, not tidiness.**
     * `EndpointAuthorizer` walks the ancestors of the CONCRETE request path (`/nyc/revenue/Manhattan`),
     * so a binding at a node containing a `{variable}` segment could never match any request —
     * it would be a checkbox that authorises nothing. For `/nyc/revenue/{borough}` the offered
     * nodes are therefore `/nyc` and `/nyc/revenue`, and binding the latter covers every borough.
     *
     * The root `/` is always offered and always first: binding there authorises the whole tree,
     * which is a real operator intent, and it is legal as a binding though never as an endpoint.
     */
    fun bindingNodes(publishedPatterns: Collection<String>): List<String> {
        val nodes = sortedSetOf<String>()
        publishedPatterns.forEach { pattern ->
            val segments = pattern.trim('/').split('/').filter { it.isNotEmpty() }
            val literal = segments.takeWhile { !it.startsWith("{") }
            for (depth in 1..literal.size) {
                nodes += "/" + literal.take(depth).joinToString("/")
            }
        }
        return listOf(ROOT) + nodes
    }

    /**
     * The form's expiry, as an instant — or null for "never".
     *
     * A preset is N days from [now]. `custom` takes a `yyyy-MM-dd` date and resolves to the END
     * of that day in UTC (the start of the next), because a human who types a date means "valid
     * through that day", and resolving to its 00:00 would expire the key before the day it names.
     *
     * Every rejection is an [ApiKeyExpiryInvalidException] with a stable `reason`, including an
     * expiry already in the past: minting a key that is dead on arrival is never what was meant,
     * and the alternative — silently clamping to "never" — would hand out a credential BROADER
     * than the one asked for.
     */
    fun resolveExpiry(
        wire: String?,
        customDate: String?,
        now: Instant,
    ): Instant? {
        val chosen = wire?.trim().orEmpty().ifEmpty { NEVER }
        if (chosen == NEVER) return null
        if (chosen == CUSTOM) return resolveCustom(customDate, now)
        val preset =
            EXPIRY_CHOICES.firstOrNull { it.wire == chosen && it.days != null }
                ?: throw ApiKeyExpiryInvalidException("unknown_preset", chosen)
        return now.plusSeconds(checkNotNull(preset.days) * SECONDS_PER_DAY)
    }

    private fun resolveCustom(
        rawDate: String?,
        now: Instant,
    ): Instant {
        val raw = rawDate?.trim().orEmpty()
        // `runCatching`, not a try/catch: the parse failure carries nothing the refusal needs —
        // the offending value is already echoed in `details.value` — and an exception chained
        // into a 400 an operator reads would only add noise.
        val parsed = if (raw.isEmpty()) null else runCatching { LocalDate.parse(raw) }.getOrNull()
        val instant = parsed?.plusDays(1)?.atStartOfDay(ZoneOffset.UTC)?.toInstant()
        customFailure(raw, parsed, instant, now)?.let { reason ->
            throw ApiKeyExpiryInvalidException(reason, raw.ifEmpty { null })
        }
        return checkNotNull(instant)
    }

    /** Which of the three ways a custom date can be unusable, or null when it is fine. */
    private fun customFailure(
        raw: String,
        parsed: LocalDate?,
        instant: Instant?,
        now: Instant,
    ): String? =
        when {
            raw.isEmpty() -> "date_missing"
            parsed == null -> "date_unparseable"
            instant == null || !instant.isAfter(now) -> "date_in_past"
            else -> null
        }

    /** The wire value for "no expiry" — the default when the form sends nothing at all. */
    const val NEVER = "never"

    /** The wire value that turns the date input on. */
    const val CUSTOM = "custom"

    /** The whole-tree binding node (§7.7: legal as a binding, never as an endpoint). */
    const val ROOT = "/"

    private const val SECONDS_PER_DAY = 86_400L
}
