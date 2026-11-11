package co.datapipelines.dependencies

/**
 * The inverse of [DependencyProvider.outgoing] (record §14, #877): "who references this?" — answered by the TARGET
 * family's provider, one dependent family per query, one bounded statement per page.
 *
 * **One definition, two directions (§7.1).** The inverse of family F into target T answers exactly the references F's
 * extraction emits toward T from a stored body — same stored fields, same node-type rule — for the dependent versions
 * the [IncomingScope] selects. A difference between the two is an A27 finding, never a second rule.
 *
 * **Workspace-scoped and UNLENSED.** A query answers every occurrence in the context's workspace. No lens, hidden flag
 * or count is part of this contract: the view boundary is the adapter method or refusal builder above it (§12), and a
 * lifecycle guard must read the whole workspace to decide.
 *
 * **Never a lookup.** The target is not resolved: a target that does not exist (an absent entity, a vanished version)
 * still has its stored references answered. Whether the caller may see the target is asked before the query, with the
 * family's native lookup.
 */
data class IncomingQuery(
    val target: IncomingTarget,
    /** The ONE dependent family this page reads; an adapter asks each arm in turn. */
    val dependents: FamilyKey,
    val scope: IncomingScope,
    /** Where the previous page ended; null for the first page. Bound to this query's target, family and scope. */
    val cursor: IncomingCursor?,
    val limit: Int = GraphLimits.DEFAULT_RESOLVE_BATCH_SIZE,
) {
    init {
        require(limit in 1..GraphLimits.MAX_RESOLVE_BATCH_SIZE) {
            "an incoming page holds 1..${GraphLimits.MAX_RESOLVE_BATCH_SIZE} occurrences, got $limit"
        }
        if (cursor != null) {
            require(cursor.target == target && cursor.dependents == dependents && cursor.scope == scope) {
                "a cursor answers only the query that produced it (target, dependent family and scope)"
            }
        }
    }

    /** The query for the page after [next] — same target, family, scope and limit. */
    fun after(next: IncomingCursor): IncomingQuery = copy(cursor = next)
}

/** What the inverse asks about (§A.2): the two target forms, independent of the dependent [IncomingScope]. */
sealed interface IncomingTarget {
    val family: FamilyKey
    val ownerScope: OwnerScope

    /** The family's lookup key exactly as stored references carry it (a name for every production family). */
    val key: String

    /** The references whose STORED pin names exactly [version] — the full positive Int range (ledger `786-r6`). */
    data class ExactVersion(
        override val family: FamilyKey,
        override val ownerScope: OwnerScope,
        override val key: String,
        val version: Int,
    ) : IncomingTarget {
        init {
            require(version >= 1) { "an exact target version is at least 1, got $version" }
        }
    }

    /**
     * Every reference naming the entity, whatever version it names — versions that no longer exist included — and every
     * unversioned reference. The only form an unversioned family (datasource, by name — erratum (7)) admits.
     */
    data class Entity(
        override val family: FamilyKey,
        override val ownerScope: OwnerScope,
        override val key: String,
    ) : IncomingTarget
}

/**
 * Which DEPENDENT versions a query reads — `ArtifactDependents.PinScope`'s three questions (versioning §3.5). Each
 * family keeps its native history rule; nothing is harmonized here.
 */
enum class IncomingScope {
    /** Each live dependent's working version: its draft when one exists, else its current pointer. */
    WORKING,

    /** Every DRAFT or RELEASED dependent version. */
    LIVE,

    /** The family's native any-version rule (for most families: every stored version of an entity that is still live). */
    ANY,
}

/**
 * The position after the last occurrence of a page (§A.3): the full order key — dependent stable id, dependent version
 * (0 for an unversioned dependent), relationship, source path — bound to the query that produced it. An in-process value:
 * no surface serializes it in this lane, so it carries no encoding.
 */
data class IncomingCursor(
    val target: IncomingTarget,
    val dependents: FamilyKey,
    val scope: IncomingScope,
    val dependentId: String,
    val dependentVersion: Int,
    val relationship: Relationship,
    val sourcePath: String,
)

/**
 * One stored reference occurrence pointing at the target (§A.3) — the page unit. [storedVersion] is the version the
 * dependent's body names (null for an unversioned or CURRENT-selector reference); [elementId] is the dependent body's
 * own element id where a port reports one (a pipeline node's `id`), null elsewhere.
 */
data class IncomingEdge(
    val dependent: ResolvedIdentity,
    val dependentName: String,
    /** The dependent version's lifecycle; null for an unversioned dependent (a schedule). */
    val dependentLifecycle: Lifecycle?,
    val relationship: Relationship,
    val sourcePath: String,
    val storedVersion: Int?,
    val elementId: String? = null,
) {
    /** The occurrence's order key (§A.3), version component 0 when the dependent is unversioned. */
    val dependentVersion: Int get() = dependent.artifactVersion ?: 0
}

/** One page of a [DependencyProvider.findIncoming] answer, or the explicit refusal to answer. */
sealed interface IncomingResult {
    /**
     * Up to `limit` occurrences in the total order; [next] is non-null exactly when a further occurrence exists. An empty
     * page with a null [next] is "nobody references the target" — never the same as [Unsupported].
     */
    data class Page(
        val edges: List<IncomingEdge>,
        val next: IncomingCursor?,
    ) : IncomingResult

    /**
     * The provider cannot answer this dependent family. Never an empty answer: every adapter throws on it, so an
     * unanswered guard question rolls its verb back.
     */
    data object Unsupported : IncomingResult
}

/** The order-key comparator of §A.3 — text under byte order (COLLATE "C"), the cursor's whole content. */
object IncomingOrder {
    val EDGES: Comparator<IncomingEdge> =
        Comparator { a, b ->
            compareKeys(
                a.dependent.stableEntityId,
                a.dependentVersion,
                a.relationship,
                a.sourcePath,
                b.dependent.stableEntityId,
                b.dependentVersion,
                b.relationship,
                b.sourcePath,
            )
        }

    /** True when [edge] sorts strictly after [cursor]. */
    fun isAfter(
        edge: IncomingEdge,
        cursor: IncomingCursor,
    ): Boolean =
        compareKeys(
            edge.dependent.stableEntityId,
            edge.dependentVersion,
            edge.relationship,
            edge.sourcePath,
            cursor.dependentId,
            cursor.dependentVersion,
            cursor.relationship,
            cursor.sourcePath,
        ) > 0

    @Suppress("LongParameterList") // the four key components of two occurrences
    private fun compareKeys(
        aId: String,
        aVersion: Int,
        aRelationship: Relationship,
        aPath: String,
        bId: String,
        bVersion: Int,
        bRelationship: Relationship,
        bPath: String,
    ): Int {
        val byId = bytewise(aId, bId)
        if (byId != 0) return byId
        val byVersion = aVersion.compareTo(bVersion)
        if (byVersion != 0) return byVersion
        val byRelationship = bytewise(aRelationship.wire, bRelationship.wire)
        if (byRelationship != 0) return byRelationship
        return bytewise(aPath, bPath)
    }

    /** UTF-8 byte order — what PostgreSQL's COLLATE "C" compares. */
    private fun bytewise(
        a: String,
        b: String,
    ): Int {
        val x = a.toByteArray(Charsets.UTF_8)
        val y = b.toByteArray(Charsets.UTF_8)
        for (i in 0 until minOf(x.size, y.size)) {
            val d = (x[i].toInt() and BYTE_MASK) - (y[i].toInt() and BYTE_MASK)
            if (d != 0) return d
        }
        return x.size - y.size
    }

    private const val BYTE_MASK = 0xFF
}

/**
 * Builds the page of [query] from up to `limit + 1` rows already in the total order: the first `limit` are the page, a
 * further row only says another page exists, and the cursor is the last returned occurrence's key.
 */
fun incomingPage(
    query: IncomingQuery,
    rows: List<IncomingEdge>,
): IncomingResult.Page {
    val page = rows.take(query.limit)
    val last = page.lastOrNull()
    val next =
        if (rows.size > query.limit && last != null) {
            IncomingCursor(
                target = query.target,
                dependents = query.dependents,
                scope = query.scope,
                dependentId = last.dependent.stableEntityId,
                dependentVersion = last.dependentVersion,
                relationship = last.relationship,
                sourcePath = last.sourcePath,
            )
        } else {
            null
        }
    return IncomingResult.Page(page, next)
}

/**
 * Reads [query] to exhaustion — the adapters' complete answer (§C.1): page after page until `next` is null. An
 * [IncomingResult.Unsupported] answer is an [IllegalStateException], never an empty list (§A.4).
 */
fun <B : Any> DependencyProvider<B>.findAllIncoming(
    query: IncomingQuery,
    context: ResolveContext,
): List<IncomingEdge> {
    val edges = ArrayList<IncomingEdge>()
    var page: IncomingQuery? = query
    while (page != null) {
        when (val answer = findIncoming(page, context)) {
            IncomingResult.Unsupported -> {
                error("the ${query.target.family} provider does not answer incoming references from ${query.dependents}")
            }

            is IncomingResult.Page -> {
                edges += answer.edges
                page = answer.next?.let(page::after)
            }
        }
    }
    return edges
}
