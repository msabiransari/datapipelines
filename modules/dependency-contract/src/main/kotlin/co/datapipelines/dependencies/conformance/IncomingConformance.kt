package co.datapipelines.dependencies.conformance

import co.datapipelines.dependencies.FamilyDescriptor
import co.datapipelines.dependencies.FamilyKey
import co.datapipelines.dependencies.GraphLimits
import co.datapipelines.dependencies.IncomingEdge
import co.datapipelines.dependencies.IncomingOrder
import co.datapipelines.dependencies.IncomingQuery
import co.datapipelines.dependencies.IncomingResult
import co.datapipelines.dependencies.IncomingScope
import co.datapipelines.dependencies.IncomingTarget
import co.datapipelines.dependencies.Relationship
import co.datapipelines.dependencies.ReleaseCapability
import co.datapipelines.dependencies.ResolveContext

/**
 * The provider conformance harness's INVERSE arm (#877 §A): every target family's fixture states, per query, the exact
 * stored occurrences the inverse must answer, and [check] returns the violations — empty when the provider conforms.
 * Framework-free, like [ProviderConformance].
 *
 * What it proves, per fixture: (1) every returned edge answers the queried target — the dependent family asked, the §6
 * relationship between the two, exactly the declared occurrences, each once, in the §A.3 total order, at page size one
 * and at the default page size alike; (2) exact-version isolation — a versioned family declares two exact targets of
 * one key, each answering only the references stored at its version; (3) an entity-wide query answers a reference whose
 * stored version names a version that does not exist; (4) an unversioned family (datasource) answers an entity query by
 * name and refuses an exact target with [IllegalArgumentException]; (5) a target nobody references answers an empty
 * page with no cursor — distinct from [IncomingResult.Unsupported]; (6) a dependent family the provider does not declare
 * answers [IncomingResult.Unsupported].
 */
object IncomingConformance {
    /** A dependent family no production provider answers for — the (6) probe. */
    val UNDECLARED_DEPENDENT = FamilyKey("conformance_probe")

    /** One stored occurrence as a fixture declares it: the §A.3 key plus the stored version. */
    data class Occurrence(
        val dependentId: String,
        val dependentVersion: Int,
        val relationship: Relationship,
        val sourcePath: String,
        val storedVersion: Int?,
    )

    /**
     * One query and its exact answer. [vanishedVersion] marks an entity-wide case whose every declared occurrence
     * names that version of the target, which the fixture guarantees does NOT exist (check 3).
     */
    data class Case(
        val target: IncomingTarget,
        val dependents: FamilyKey,
        val scope: IncomingScope,
        val expected: List<Occurrence>,
        val vanishedVersion: Int? = null,
    )

    data class Fixture<B : Any>(
        val descriptor: FamilyDescriptor<B>,
        val context: ResolveContext,
        val cases: List<Case>,
        /** A target of this family that no stored body references (check 5). */
        val unreferenced: IncomingTarget,
    )

    fun <B : Any> check(fixture: Fixture<B>): List<String> {
        val family = fixture.descriptor.key
        val provider = fixture.descriptor.provider
        val violations = ArrayList<String>(declaration(fixture))
        fixture.cases.forEach { violations += case(fixture, it) }
        provider.incomingFrom.forEach { dependents ->
            IncomingScope.entries.forEach { scope ->
                val answer = provider.findIncoming(IncomingQuery(fixture.unreferenced, dependents, scope, null), fixture.context)
                if (answer != IncomingResult.Page(emptyList(), null)) {
                    violations += "$family: unreferenced ${fixture.unreferenced.key} from $dependents ($scope) answered $answer"
                }
            }
        }
        val probe = IncomingQuery(fixture.unreferenced, UNDECLARED_DEPENDENT, IncomingScope.ANY, null)
        val undeclared = provider.findIncoming(probe, fixture.context)
        if (undeclared != IncomingResult.Unsupported) {
            violations += "$family: an undeclared dependent family answered $undeclared, not Unsupported"
        }
        if (!versioned(fixture)) violations += exactRefused(fixture)
        return violations
    }

    /** The fixture's non-vacuity and its §6 consistency, before any query. */
    private fun declaration(fixture: Fixture<*>): List<String> {
        val family = fixture.descriptor.key
        val declared = fixture.descriptor.provider.incomingFrom
        val violations = ArrayList<String>()
        val inbound =
            Relationship.entries
                .filter { it.to == family }
                .map { it.from }
                .toSet()
        if (family in FamilyKey.PRODUCTION && declared != inbound) {
            violations += "$family: incomingFrom $declared is not section 6's inbound families $inbound"
        }
        if (fixture.unreferenced.family != family) violations += "$family: the unreferenced target is another family's"
        declared.forEach { dependents ->
            if (fixture.cases.none { it.dependents == dependents && it.expected.isNotEmpty() }) {
                violations += "$family: no nonempty case for dependent family $dependents (vacuous)"
            }
        }
        violations += coverage(fixture)
        return violations
    }

    /** Checks 2-4's non-vacuity: what the fixture must exercise for a versioned or an unversioned family. */
    private fun coverage(fixture: Fixture<*>): List<String> {
        val family = fixture.descriptor.key
        val violations = ArrayList<String>()
        if (versioned(fixture)) {
            val exact = fixture.cases.filter { it.target is IncomingTarget.ExactVersion && it.expected.isNotEmpty() }
            val isolated =
                exact.any { a ->
                    exact.any { b ->
                        a.dependents == b.dependents && a.target.key == b.target.key &&
                            (a.target as IncomingTarget.ExactVersion).version != (b.target as IncomingTarget.ExactVersion).version
                    }
                }
            if (!isolated) violations += "$family: no two nonempty exact targets of one key (isolation never exercised)"
            if (fixture.cases.none { it.vanishedVersion != null && it.expected.isNotEmpty() }) {
                violations += "$family: no entity case over a vanished version (check 3 never exercised)"
            }
        } else if (fixture.cases.none { it.target is IncomingTarget.Entity && it.expected.isNotEmpty() }) {
            violations += "$family: no nonempty entity case (an unversioned family answers by name)"
        }
        return violations
    }

    private fun <B : Any> case(
        fixture: Fixture<B>,
        case: Case,
    ): List<String> {
        val family = fixture.descriptor.key
        val label = "$family: ${case.target} from ${case.dependents} (${case.scope})"
        val violations = ArrayList<String>()
        if (case.target.family != family) return listOf("$label: the target is another family's")
        val pages = listOf(1, GraphLimits.DEFAULT_RESOLVE_BATCH_SIZE).map { it to read(fixture, case, it, violations, label) }
        val (_, byOne) = pages.first()
        pages.drop(1).filter { it.second != byOne }.forEach { violations += "$label: page size ${it.first} answers differently" }
        val produced = byOne.map(::occurrence)
        if (produced.toSet().size != produced.size) violations += "$label: an occurrence was answered more than once"
        if (byOne != byOne.sortedWith(IncomingOrder.EDGES)) violations += "$label: occurrences are not in the total order"
        case.expected.filter { it !in produced }.forEach { violations += "$label: missing $it" }
        produced.filter { it !in case.expected }.forEach { violations += "$label: unexpected $it" }
        val relationship = Relationship.between(case.dependents, family)
        byOne.filter { it.dependent.family != case.dependents || it.relationship != relationship }.forEach {
            violations += "$label: ${occurrence(it)} is not a ${case.dependents} -> $family reference"
        }
        val target = case.target
        if (target is IncomingTarget.ExactVersion) {
            byOne.filter { it.storedVersion != target.version }.forEach {
                violations += "$label: ${occurrence(it)} stores another version"
            }
        }
        case.vanishedVersion?.let { vanished ->
            byOne.filter { it.storedVersion != vanished }.forEach { violations += "$label: ${occurrence(it)} is not the vanished pin" }
        }
        return violations
    }

    /** Reads the case to exhaustion at [limit], checking each page's size and cursor. */
    private fun <B : Any> read(
        fixture: Fixture<B>,
        case: Case,
        limit: Int,
        violations: MutableList<String>,
        label: String,
    ): List<IncomingEdge> {
        val edges = ArrayList<IncomingEdge>()
        var query: IncomingQuery? = IncomingQuery(case.target, case.dependents, case.scope, null, limit)
        var pages = 0
        while (query != null && pages <= MAX_PAGES) {
            pages++
            when (val answer = fixture.descriptor.provider.findIncoming(query, fixture.context)) {
                IncomingResult.Unsupported -> {
                    violations += "$label: Unsupported for a declared dependent family"
                    return edges
                }

                is IncomingResult.Page -> {
                    if (answer.edges.size > limit) violations += "$label: a page of ${answer.edges.size} at limit $limit"
                    if (answer.next != null && answer.edges.size < limit) violations += "$label: a short page with a cursor"
                    edges += answer.edges
                    query = answer.next?.let(query::after)
                }
            }
        }
        if (query != null) violations += "$label: more than $MAX_PAGES pages (a cursor that never ends)"
        return edges
    }

    private fun <B : Any> exactRefused(fixture: Fixture<B>): List<String> {
        val family = fixture.descriptor.key
        val exact = IncomingTarget.ExactVersion(family, fixture.unreferenced.ownerScope, fixture.unreferenced.key, 1)
        return fixture.descriptor.provider.incomingFrom.mapNotNull { dependents ->
            val refused =
                try {
                    fixture.descriptor.provider.findIncoming(IncomingQuery(exact, dependents, IncomingScope.ANY, null), fixture.context)
                    false
                } catch (_: IllegalArgumentException) {
                    true
                }
            if (refused) null else "$family: an exact-version target from $dependents was not refused (unversioned family)"
        }
    }

    private fun versioned(fixture: Fixture<*>): Boolean = fixture.descriptor.capabilities.release != ReleaseCapability.NONE

    private fun occurrence(edge: IncomingEdge) =
        Occurrence(edge.dependent.stableEntityId, edge.dependentVersion, edge.relationship, edge.sourcePath, edge.storedVersion)

    private const val MAX_PAGES = 10_000
}
