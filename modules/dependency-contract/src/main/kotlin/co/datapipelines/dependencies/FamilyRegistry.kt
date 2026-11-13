package co.datapipelines.dependencies

/**
 * One family's registration (record §3.2): the stable wire [key], the root selectors it admits, its §4 [capabilities],
 * the extractor schema version (part of the internal fingerprint, §5.3), the [provider], and the safe-summary projector
 * hook G4 supplies (the per-family allowlisted summary; G1's descriptors pass [NO_SAFE_SUMMARY]).
 */
class FamilyDescriptor<B : Any>(
    val key: FamilyKey,
    val rootSelectors: Set<SelectorKind>,
    val capabilities: Capabilities,
    val extractorSchemaVersion: Int,
    val provider: DependencyProvider<B>,
    val safeSummary: (Node) -> Map<String, Any?>,
) {
    init {
        require(rootSelectors.isNotEmpty()) { "family $key admits no root selector" }
        require(extractorSchemaVersion >= 1) { "family $key's extractor schema version is at least 1" }
    }

    /**
     * The provider's answer for [requests] (all of this family), unchecked — the ONE call that touches infrastructure, so
     * the walk can tell a provider's failure (an exception from here) from a contract defect ([bind]'s checks).
     */
    internal fun fetch(
        requests: List<ObjectRequest>,
        context: ResolveContext,
    ): Map<ObjectRequest, Outcome<B>> = provider.resolveMany(requests, context)

    /**
     * Binds each of [answers]' resolved outcomes to this provider's [DependencyProvider.outgoing], so the walk holds no
     * generic body type. Refuses (a defect, never swallowed) a provider that answered a different key set than it was
     * asked or resolved another family's identity.
     */
    internal fun bind(
        requests: List<ObjectRequest>,
        answers: Map<ObjectRequest, Outcome<B>>,
    ): Map<ObjectRequest, Loaded> {
        check(answers.keys == requests.toSet()) {
            "family $key answered ${answers.size} outcomes for ${requests.toSet().size} requests; every request needs exactly one"
        }
        return requests.associateWith { request ->
            when (val outcome = answers.getValue(request)) {
                is Outcome.Resolved -> {
                    check(outcome.identity.family == key) { "family $key resolved a ${outcome.identity.family} identity" }
                    Loaded.Found(outcome.identity, outcome.facts) { provider.outgoing(outcome) }
                }

                Outcome.Missing -> {
                    Loaded.Missing
                }

                Outcome.Unsupported -> {
                    Loaded.Unsupported
                }
            }
        }
    }

    override fun toString(): String = "FamilyDescriptor($key)"

    companion object {
        /** The projector of a family whose safe summary G4 has not yet allowlisted: it summarizes nothing. */
        val NO_SAFE_SUMMARY: (Node) -> Map<String, Any?> = { emptyMap() }
    }
}

/** A request's outcome bound to its provider — the walk's non-generic view of [Outcome]. */
internal sealed interface Loaded {
    class Found(
        val identity: ResolvedIdentity,
        val facts: NodeFacts,
        val references: () -> List<Reference>,
    ) : Loaded

    data object Missing : Loaded

    data object Unsupported : Loaded
}

/**
 * The DI-assembled family registry (§3.2). A duplicate key throws at construction — the Spring wiring turns that into a
 * startup failure. A key with no descriptor is not an error here: the walk answers it `UNSUPPORTED` with an
 * `UNSUPPORTED_FAMILY` diagnostic, never an empty dependency set.
 */
class FamilyRegistry(
    descriptors: Collection<FamilyDescriptor<*>>,
) {
    private val byKey: Map<FamilyKey, FamilyDescriptor<*>>

    init {
        val duplicates = descriptors.groupBy { it.key }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "duplicate family registration: ${duplicates.joinToString { it.wire }}" }
        byKey = descriptors.associateBy { it.key }
    }

    /** The registered keys, in registration order. */
    val keys: Set<FamilyKey> get() = byKey.keys

    operator fun get(key: FamilyKey): FamilyDescriptor<*>? = byKey[key]

    companion object {
        /**
         * The PRODUCTION assembly: [descriptors] must be unique, every key must be one of [FamilyKey.PRODUCTION] and declare
         * that key's §4 capability pair. A synthetic or misdeclared family refuses startup here; tests build a plain
         * [FamilyRegistry] instead.
         */
        fun production(descriptors: Collection<FamilyDescriptor<*>>): FamilyRegistry {
            descriptors.forEach { descriptor ->
                val declared =
                    requireNotNull(FamilyKey.PRODUCTION[descriptor.key]) {
                        "family ${descriptor.key} is not a production family (${FamilyKey.PRODUCTION.keys.joinToString { it.wire }})"
                    }
                require(descriptor.capabilities == declared) {
                    "family ${descriptor.key} declares ${descriptor.capabilities}, the record's §4 pair is $declared"
                }
            }
            return FamilyRegistry(descriptors)
        }
    }
}
