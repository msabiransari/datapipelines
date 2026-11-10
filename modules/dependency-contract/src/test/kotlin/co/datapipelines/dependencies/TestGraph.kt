package co.datapipelines.dependencies

import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** The synthetic workspace every contract test reads in. */
internal val WS: UUID = UUID.fromString("00000000-0000-0000-0000-000000000868")

/** The synthetic family of A28: defined in test code only, outside [FamilyKey.PRODUCTION]. */
internal val TEST_WIDGET = FamilyKey("test_widget")

internal val FIXED_CLOCK: Clock = Clock.fixed(Instant.parse("2026-11-10T00:00:00Z"), ZoneOffset.UTC)

internal fun context(
    deadline: Deadline = Deadline.none(),
    purpose: Purpose = Purpose.INTERACTIVE_READ,
) = ResolveContext(WS, MetadataSnapshot.READ_COMMITTED, purpose, Authority.System, deadline)

internal fun exact(
    family: FamilyKey,
    key: String,
    version: Int,
) = ObjectRequest(ObjectAddress(family, LookupScope(WS), key), Selector.ExactVersion(version))

internal fun current(
    family: FamilyKey,
    key: String,
) = ObjectRequest(ObjectAddress(family, LookupScope(WS), key), Selector.Current)

internal fun entity(
    family: FamilyKey,
    key: String,
) = ObjectRequest(ObjectAddress(family, LookupScope(WS), key), Selector.Entity)

/** The canonical loaded body of a fake object: its references, in body order. */
internal data class FakeBody(
    val references: List<Reference>,
)

/**
 * One in-memory family: objects by request, every `resolveMany` call recorded (the batch call-count tests), and hooks to
 * run before a batch (the deadline and snapshot tests) or to fail one (the infrastructure-failure test).
 */
internal class FakeFamily(
    val key: FamilyKey,
    private val capabilities: Capabilities = FamilyKey.PRODUCTION[key] ?: Capabilities.NONE,
    private val rootSelectors: Set<SelectorKind> = SelectorKind.entries.toSet(),
) : DependencyProvider<FakeBody> {
    private val objects = LinkedHashMap<ObjectRequest, Outcome.Resolved<FakeBody>>()
    val calls = ArrayList<List<ObjectRequest>>()
    var beforeBatch: (List<ObjectRequest>) -> Unit = {}
    var unsupported: Set<ObjectRequest> = emptySet()

    /** Requests a batch holding them FAILS on (an infrastructure failure, `metadata source unavailable: <key>`). */
    var failing: Set<ObjectRequest> = emptySet()

    /** Requests the provider leaves out of its answer — a contract defect, not a failure. */
    var omitted: Set<ObjectRequest> = emptySet()

    fun put(
        request: ObjectRequest,
        facts: NodeFacts,
        references: List<Reference>,
        identity: ResolvedIdentity =
            ResolvedIdentity(key, OwnerScope.Workspace(WS), "id-${request.address.lookupKey}", request.selector.number),
    ) {
        objects[request] = Outcome.Resolved(identity, facts, FakeBody(references))
    }

    override fun resolveMany(
        requests: List<ObjectRequest>,
        context: ResolveContext,
    ): Map<ObjectRequest, Outcome<FakeBody>> {
        calls.add(requests)
        beforeBatch(requests)
        requests.firstOrNull { it in failing }?.let { error("metadata source unavailable: ${it.address.lookupKey}") }
        return requests
            .filter { it !in omitted }
            .associateWith { if (it in unsupported) Outcome.Unsupported else objects[it] ?: Outcome.Missing }
    }

    override fun outgoing(resolved: Outcome.Resolved<FakeBody>): List<Reference> = resolved.body.references

    /** Every request this family was asked to resolve, across calls. */
    fun requested(): List<ObjectRequest> = calls.flatten()

    fun descriptor(extractorSchemaVersion: Int = 1): FamilyDescriptor<FakeBody> =
        FamilyDescriptor(key, rootSelectors, capabilities, extractorSchemaVersion, this, FamilyDescriptor.NO_SAFE_SUMMARY)
}

/**
 * A small graph builder over [FakeFamily]s: `node(…)` stores an object whose references are `pin(…)` occurrences, each
 * with its §6 relationship when one exists (a synthetic family's edges borrow `IMPORT`).
 */
internal class TestGraph {
    val families = LinkedHashMap<FamilyKey, FakeFamily>()

    fun family(key: FamilyKey): FakeFamily = families.getOrPut(key) { FakeFamily(key) }

    fun node(
        request: ObjectRequest,
        lifecycle: Lifecycle? = Lifecycle.DRAFT,
        vararg pins: Reference,
        facts: NodeFacts = facts(request, lifecycle),
    ): ObjectRequest {
        family(request.family).put(request, facts, pins.toList())
        return request
    }

    fun registry(): FamilyRegistry = FamilyRegistry(families.values.map { it.descriptor() })

    fun walker(limits: GraphLimits = LIMITS): DependencyWalker = DependencyWalker(registry(), limits, FIXED_CLOCK)

    fun walk(
        vararg roots: ObjectRequest,
        limits: GraphLimits = LIMITS,
    ): GraphSnapshot = walker(limits).walk(roots.toList(), context())

    companion object {
        val LIMITS = GraphLimits(depth = 32, nodes = 1024, edges = 4096, diagnostics = 100, wireBytes = 1_048_576, lockWaitSeconds = 5)

        fun facts(
            request: ObjectRequest,
            lifecycle: Lifecycle?,
        ) = NodeFacts(
            name = "acme/${request.address.lookupKey}",
            versionInfo = request.selector.number?.let { VersionInfo.Artifact(it) } ?: VersionInfo.Unversioned,
            lifecycle = lifecycle,
            bodyHash = "hash-${request.address.lookupKey}-${request.selector.number}",
        )
    }
}

internal fun pin(
    from: FamilyKey,
    target: ObjectRequest,
    path: String,
    vararg constraints: Constraint,
) = Reference(
    request = target,
    relationship = Relationship.between(from, target.family) ?: Relationship.IMPORT,
    sourcePath = path,
    constraints = constraints.toList(),
    spelledName = "acme/${target.address.lookupKey}",
)

/** The node ids of [snapshot] whose lookup key is [key] — a readable assertion target. */
internal fun GraphSnapshot.idOf(key: String): String = nodes.single { it.requestedAddress.lookupKey == key }.nodeId

internal fun GraphSnapshot.keysOf(ids: List<String>?): List<String> = ids.orEmpty().map { node(it).requestedAddress.lookupKey }

/** The repository root, found from the test's working directory (the module directory under Gradle). */
internal object RepoFiles {
    val root: File =
        generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }

    fun file(path: String): File = File(root, path)
}
