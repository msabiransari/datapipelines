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
     * One family's fixture: requests that exist, requests that do not, and — per reference-bearing request — the source
     * paths its body populates, each of which must yield a reference (the "every reference-bearing body field" rule). A
     * production family's references must also be §6 rows ([Relationship.between]); a synthetic family's are not checked.
     */
    data class Fixture<B : Any>(
        val descriptor: FamilyDescriptor<B>,
        val context: ResolveContext,
        val present: List<ObjectRequest>,
        val absent: List<ObjectRequest>,
        val referenceFields: Map<ObjectRequest, Set<String>>,
    )

    /** The violations of [fixture]'s family, empty when the provider conforms. */
    fun <B : Any> check(fixture: Fixture<B>): List<String> {
        val violations = ArrayList<String>()
        val family = fixture.descriptor.key
        if (fixture.present.isEmpty()) violations += "$family: the fixture has no present request (vacuous)"
        if (fixture.absent.isEmpty()) violations += "$family: the fixture has no absent request (MISSING never exercised)"
        if (fixture.referenceFields.isEmpty() || fixture.referenceFields.values.any { it.isEmpty() }) {
            violations += "$family: the fixture declares no reference-bearing field (the suite would be empty)"
        }
        val requests = fixture.present + fixture.absent
        requests.filter { it.family != family }.forEach { violations += "$family: fixture request $it is another family's" }
        val answers = fixture.descriptor.provider.resolveMany(requests, fixture.context)
        if (answers.keys != requests.toSet()) {
            violations += "$family: ${answers.size} outcomes for ${requests.toSet().size} requests — one per request is required"
        }
        fixture.absent.forEach {
            if (answers[it] !=
                Outcome.Missing
            ) {
                violations += "$family: absent $it answered ${answers[it]}, not MISSING"
            }
        }
        fixture.present.forEach { request ->
            when (val outcome = answers[request]) {
                is Outcome.Resolved -> violations += resolved(fixture, request, outcome)
                else -> violations += "$family: present $request answered $outcome"
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
        val paths = first.mapNotNull { it.sourcePath }.toSet()
        fixture.referenceFields[request].orEmpty().filter { it !in paths }.forEach {
            violations += "$family: reference-bearing field $it of $request produced no reference"
        }
        return violations
    }
}
