package co.datapipelines.dependencies.conformance

import co.datapipelines.dependencies.FamilyDescriptor
import co.datapipelines.dependencies.FamilyKey
import co.datapipelines.dependencies.ObjectRequest
import co.datapipelines.dependencies.Outcome
import co.datapipelines.dependencies.Relationship
import co.datapipelines.dependencies.ResolveContext

/**
 * The provider conformance harness (record §7.1: "registration tests require a nonempty conformance suite for every family
 * and every reference-bearing body field"). Every family's test — the synthetic `test_widget` in G1, the five artifact
 * providers in G2, the API and schedule providers in #786/G3 — calls [check] with a fixture of its own and asserts the
 * answer is empty.
 *
 * It lives in MAIN, framework-free (it returns violations rather than asserting), because a `testFixtures` source set
 * would be a project self-edge the root `verifyModuleDependencies` refuses; any test framework can assert on the list.
 */
object ProviderConformance {
    /**
     * One family's fixture: requests that exist, requests that do not, and what the present bodies reference
     * ([references]). The suite is never vacuous: at least one present and one absent request, and a family that HAS
     * reference-bearing fields declares them — a leaf family declares [References.None] instead of inventing an edge.
     */
    data class Fixture<B : Any>(
        val descriptor: FamilyDescriptor<B>,
        val context: ResolveContext,
        val present: List<ObjectRequest>,
        val absent: List<ObjectRequest>,
        val references: References,
    )

    /** What a fixture's present bodies reference — stated exactly, so a missing or an extra reference is a violation. */
    sealed interface References {
        /**
         * A leaf family: its bodies carry no reference-bearing field, so every present request yields NO reference. A
         * production family may declare it only when record §6 gives it no outgoing row (datasource).
         */
        data object None : References

        /**
         * Per present request whose body references anything, the exact set of source paths its references occupy
         * ("every reference-bearing body field", §7.1): each must yield a reference and no other path may. A present
         * request not named here must yield none. At least one request, and every set nonempty.
         */
        data class Fields(
            val byRequest: Map<ObjectRequest, Set<String>>,
        ) : References
    }

    /** The violations of [fixture]'s family, empty when the provider conforms. */
    fun <B : Any> check(fixture: Fixture<B>): List<String> {
        val family = fixture.descriptor.key
        val violations = ArrayList<String>(declaration(fixture))
        val requests = fixture.present + fixture.absent
        requests.filter { it.family != family }.forEach { violations += "$family: fixture request $it is another family's" }
        val answers = fixture.descriptor.provider.resolveMany(requests, fixture.context)
        if (answers.keys != requests.toSet()) {
            violations += "$family: ${answers.size} outcomes for ${requests.toSet().size} requests — one per request is required"
        }
        fixture.absent.filter { answers[it] != Outcome.Missing }.forEach {
            violations += "$family: absent $it answered ${answers[it]}, not MISSING"
        }
        fixture.present.forEach { request ->
            when (val outcome = answers[request]) {
                is Outcome.Resolved -> violations += resolved(fixture, request, outcome)
                else -> violations += "$family: present $request answered $outcome"
            }
        }
        return violations
    }

    /** The fixture's own non-vacuity and its §6 consistency, before any provider call. */
    private fun declaration(fixture: Fixture<*>): List<String> {
        val family = fixture.descriptor.key
        val violations = ArrayList<String>()
        if (fixture.present.isEmpty()) violations += "$family: the fixture has no present request (vacuous)"
        if (fixture.absent.isEmpty()) violations += "$family: the fixture has no absent request (MISSING never exercised)"
        val hasSection6Rows = Relationship.entries.any { it.from == family }
        when (val references = fixture.references) {
            References.None -> {
                if (family in FamilyKey.PRODUCTION && hasSection6Rows) {
                    violations += "$family: section 6 gives $family outgoing rows, so the fixture declares its reference fields"
                }
            }

            is References.Fields -> {
                if (references.byRequest.isEmpty() || references.byRequest.values.any { it.isEmpty() }) {
                    violations += "$family: the fixture declares no reference-bearing field (an empty suite; a leaf declares None)"
                }
                references.byRequest.keys.filter { it !in fixture.present }.forEach {
                    violations += "$family: declared fields of $it, which is not a present request"
                }
                if (family in FamilyKey.PRODUCTION && !hasSection6Rows) {
                    violations += "$family: section 6 gives $family no outgoing row, so its fixture declares None"
                }
            }
        }
        return violations
    }

    private fun <B : Any> resolved(
        fixture: Fixture<B>,
        request: ObjectRequest,
        outcome: Outcome.Resolved<B>,
    ): List<String> {
        val family = fixture.descriptor.key
        val violations = ArrayList<String>()
        if (outcome.identity.family != family) violations += "$family: $request resolved a ${outcome.identity.family} identity"
        val first = fixture.descriptor.provider.outgoing(outcome)
        val second = fixture.descriptor.provider.outgoing(outcome)
        if (first != second) violations += "$family: outgoing($request) is not deterministic"
        if (family in FamilyKey.PRODUCTION) {
            // A production family's references are §6 rows: the token's from/to families are the edge's.
            first.filter { it.relationship != Relationship.between(family, it.request.family) }.forEach {
                violations += "$family: reference ${it.relationship} at ${it.sourcePath} to ${it.request.family} is not a §6 row"
            }
        }
        val declared = (fixture.references as? References.Fields)?.byRequest?.get(request).orEmpty()
        val produced = first.map { it.sourcePath }
        declared.filter { it !in produced }.forEach {
            violations += "$family: reference-bearing field $it of $request produced no reference"
        }
        produced.filter { it !in declared }.forEach {
            violations += "$family: $request produced a reference at undeclared field $it"
        }
        return violations
    }
}
