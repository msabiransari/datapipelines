package co.datapipelines.web.dependencies

import co.datapipelines.application.lens.LensedView
import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.dependencies.Capabilities
import co.datapipelines.dependencies.DependencyProvider
import co.datapipelines.dependencies.FamilyDescriptor
import co.datapipelines.dependencies.FamilyKey
import co.datapipelines.dependencies.FamilyRegistry
import co.datapipelines.dependencies.GraphLimits
import co.datapipelines.dependencies.Lifecycle
import co.datapipelines.dependencies.LookupScope
import co.datapipelines.dependencies.NodeFacts
import co.datapipelines.dependencies.ObjectAddress
import co.datapipelines.dependencies.ObjectRequest
import co.datapipelines.dependencies.Outcome
import co.datapipelines.dependencies.OwnerScope
import co.datapipelines.dependencies.ReadIsolation
import co.datapipelines.dependencies.Reference
import co.datapipelines.dependencies.Relationship
import co.datapipelines.dependencies.ResolveContext
import co.datapipelines.dependencies.ResolvedIdentity
import co.datapipelines.dependencies.Selector
import co.datapipelines.dependencies.SelectorKind
import co.datapipelines.dependencies.VersionInfo
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.transaction.support.TransactionOperations
import java.net.URI
import java.net.URLEncoder
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/** The workspace every unit-level tree test reads in. */
internal val TREE_WS: UUID = UUID.fromString("00000000-0000-0000-0000-000000000875")

/** A28's synthetic family: defined in test code only, outside `FamilyKey.PRODUCTION`, with no route. */
internal val TEST_WIDGET = FamilyKey("test_widget")

internal val TREE_CLOCK: Clock = Clock.fixed(Instant.parse("2026-11-11T00:00:00Z"), ZoneOffset.UTC)

/** A clock a test moves by hand — what a provider's read "takes" (#875 §D): time passes only when the test says so. */
internal class MovingTreeClock(
    private var now: Instant = TREE_CLOCK.instant(),
) : Clock() {
    fun advance(by: Duration) {
        now = now.plus(by)
    }

    override fun instant(): Instant = now

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this
}

internal val TREE_LIMITS =
    GraphLimits(depth = 32, nodes = 1024, edges = 4096, diagnostics = 100, wireBytes = 1_048_576, lockWaitSeconds = 5)

/** The native key a test row uses: any non-blank value is canonical as written. */
internal val ANY_KEY = NativeKey { value -> value.takeIf { it.isNotBlank() } }

internal fun exact(
    family: FamilyKey,
    key: String,
    version: Int,
) = ObjectRequest(ObjectAddress(family, LookupScope(TREE_WS), key), Selector.ExactVersion(version))

internal fun entityRequest(
    family: FamilyKey,
    key: String,
) = ObjectRequest(ObjectAddress(family, LookupScope(TREE_WS), key), Selector.Entity)

/**
 * A GET of `/api/v1/dependencies/<segment>` with [rawQuery] AS WRITTEN: the URI overload is not a template, so nothing is
 * re-encoded, and it sets the request's query string — what the handler reads (`.param(...)` would not).
 */
internal fun tree(
    segment: String,
    rawQuery: String,
): MockHttpServletRequestBuilder = MockMvcRequestBuilders.get(URI.create("/api/v1/dependencies/$segment?$rawQuery"))

internal fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)

/** A session member of [TREE_WS] holding [role]. */
internal fun member(
    role: WorkspaceRole = WorkspaceRole.AUTHOR,
    authMethod: AuthMethod = AuthMethod.OIDC,
) = AuthenticatedPrincipal(
    UUID.fromString("00000000-0000-0000-0000-0000000008a5"),
    "member@example.test",
    "Member",
    authMethod,
    workspace = WorkspaceContext(TREE_WS, "acme", role = role),
)

/**
 * One in-memory family: objects by request, every `resolveMany` call recorded, a hook run before each batch (the deadline
 * tests stall or throw there).
 */
internal class StubFamily(
    val key: FamilyKey,
    val capabilities: Capabilities = FamilyKey.PRODUCTION[key] ?: Capabilities.NONE,
) : DependencyProvider<List<Reference>> {
    private val objects = LinkedHashMap<ObjectRequest, Outcome.Resolved<List<Reference>>>()
    val calls = ArrayList<List<ObjectRequest>>()
    var beforeBatch: (List<ObjectRequest>, ResolveContext) -> Unit = { _, _ -> }

    fun put(
        request: ObjectRequest,
        lifecycle: Lifecycle? = Lifecycle.RELEASED,
        vararg targets: ObjectRequest,
    ): ObjectRequest {
        val version = request.selector.number
        objects[request] =
            Outcome.Resolved(
                ResolvedIdentity(key, OwnerScope.Workspace(TREE_WS), entityIdOf(request.address.lookupKey), version),
                NodeFacts(
                    name = request.address.lookupKey,
                    versionInfo = version?.let { VersionInfo.Artifact(it) } ?: VersionInfo.Unversioned,
                    lifecycle = lifecycle,
                    bodyHash = "hash-${request.address.lookupKey}-$version",
                ),
                targets.mapIndexed { i, target ->
                    Reference(
                        request = target,
                        relationship = Relationship.between(key, target.family) ?: Relationship.IMPORT,
                        sourcePath = "refs[$i]",
                        spelledName = target.address.lookupKey,
                    )
                },
            )
        return request
    }

    override fun resolveMany(
        requests: List<ObjectRequest>,
        context: ResolveContext,
    ): Map<ObjectRequest, Outcome<List<Reference>>> {
        calls.add(requests)
        beforeBatch(requests, context)
        return requests.associateWith { objects[it] ?: Outcome.Missing }
    }

    override fun outgoing(resolved: Outcome.Resolved<List<Reference>>): List<Reference> = resolved.body

    fun descriptor(): FamilyDescriptor<List<Reference>> =
        FamilyDescriptor(key, SelectorKind.entries.toSet(), capabilities, 1, this, FamilyDescriptor.NO_SAFE_SUMMARY)
}

/** A stub object's stable entity id: derived from its key, so a test can predict it. */
internal fun entityIdOf(key: String): String = UUID.nameUUIDFromBytes(key.toByteArray()).toString()

/** A transport row for [family]: name + version, any non-blank name, [notFoundCode], [lens]. */
internal fun stubRow(
    family: FamilyKey,
    notFoundCode: String = "${family.wire}.not_found",
    lens: RootLens = RootLens.None,
) = FamilyTransportRow(family, SelectorShape.NAME_AT_VERSION, ANY_KEY, notFoundCode, lens)

/** The tree service over [families] and [rows], with no transaction and no isolation — the walk is the unit under test. */
@Suppress("LongParameterList") // every collaborator a test varies
internal fun treeService(
    families: List<StubFamily>,
    rows: List<FamilyTransportRow>,
    limits: GraphLimits = TREE_LIMITS,
    view: () -> LensedView = { LensedView.EVERYTHING },
    transactions: TransactionOperations = TransactionOperations.withoutTransaction(),
    clock: Clock = TREE_CLOCK,
    descriptors: List<FamilyDescriptor<*>> = families.map { it.descriptor() },
) = DependencyTreeService(
    registry = FamilyRegistry(descriptors),
    transport = DependencyFamilyTransport(rows),
    limits = limits,
    transactions = transactions,
    readIsolation = ReadIsolation.NONE,
    lens = PromoterLens { view() },
    mapper = jacksonObjectMapper(),
    clock = clock,
)
