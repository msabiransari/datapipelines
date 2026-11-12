package co.datapipelines.web.dependencies

import co.datapipelines.application.dependencies.StoredVersion
import co.datapipelines.application.dependencies.parameterset.ParameterSetReleaseBody
import co.datapipelines.dependencies.Authority
import co.datapipelines.dependencies.Capabilities
import co.datapipelines.dependencies.Constraint
import co.datapipelines.dependencies.Deadline
import co.datapipelines.dependencies.DependencyProvider
import co.datapipelines.dependencies.DependencyWalker
import co.datapipelines.dependencies.FamilyDescriptor
import co.datapipelines.dependencies.FamilyKey
import co.datapipelines.dependencies.FamilyRegistry
import co.datapipelines.dependencies.GraphLimits
import co.datapipelines.dependencies.GraphSnapshot
import co.datapipelines.dependencies.Lifecycle
import co.datapipelines.dependencies.MetadataSnapshot
import co.datapipelines.dependencies.NodeFacts
import co.datapipelines.dependencies.ObjectRequest
import co.datapipelines.dependencies.Outcome
import co.datapipelines.dependencies.OwnerScope
import co.datapipelines.dependencies.Purpose
import co.datapipelines.dependencies.ReadIsolation
import co.datapipelines.dependencies.Reference
import co.datapipelines.dependencies.Relationship
import co.datapipelines.dependencies.ResolveContext
import co.datapipelines.dependencies.ResolvedIdentity
import co.datapipelines.dependencies.SelectorKind
import co.datapipelines.dependencies.VersionInfo
import co.datapipelines.parameters.ParameterDefinition
import co.datapipelines.parameters.ParameterKind
import co.datapipelines.parameters.ParameterSetBody
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.pipeline.release.ReleaseNodeIdentity
import co.datapipelines.typesystem.LogicalType
import co.datapipelines.web.api.ApiExceptionHandler
import co.datapipelines.web.config.ReleaseDependencyProperties
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Clock
import java.time.Duration
import java.util.UUID
import kotlin.random.Random

/**
 * **`unmet_names` below the controller** (#892; record §11.1 `parameter_names` row, §12, Draft 10 erratum (11); A34) —
 * over a test-built registry: a dashboard family whose set edge carries the `parameter_names` constraint, and a
 * parameter-set family that answers each `resolveMany` CALL by its index (call 0 is the walk's; later calls are the
 * stage's), so the walk and the stage can be shown different sets. Cases 5, 8 and 13 drive [UnmetParameterNames] over a
 * graph the real [DependencyWalker] produced; 9–12 go through [DependencyTreeService] (10 also through the real
 * controller and [ApiExceptionHandler]).
 */
class UnmetParameterNamesTest {
    private val boards = BoardFamily()
    private val sets = SetFamily()

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    @Test
    fun `case 5 - for arbitrary names and declared sets, unmet_names is the ordered subset of names the set lacks, absent when empty`() {
        val random = Random(SEED)
        var unmetCases = 0
        var metCases = 0
        repeat(SAMPLES) { sample ->
            boards.clear()
            sets.clear()
            val names = POOL.shuffled(random).take(random.nextInt(1, POOL.size + 1))
            val declared = POOL.filter { random.nextBoolean() }
            val set = sets.put("acme/set_$sample", declared)
            val root = boards.put("acme/board_$sample", set, names)
            val graph = walk(root)

            val unmet = stage().evaluate(graph, context()).of(SET_EDGE, Constraint.ParameterNames(names))

            withClue("names=$names declared=$declared") {
                val expected = names.filter { it !in declared }
                if (expected.isEmpty()) {
                    metCases++
                    unmet shouldBe null
                } else {
                    unmetCases++
                    val list = checkNotNull(unmet)
                    list shouldBe expected
                    (list.toSet() - names.toSet()).shouldBeEmpty()
                    list.distinct() shouldBe list
                    list shouldBe names.filter { it in list }
                }
            }
        }
        withClue("both branches sampled") {
            unmetCases shouldBeGreaterThanOrEqual MIN_BRANCH_SAMPLES
            metCases shouldBeGreaterThanOrEqual MIN_BRANCH_SAMPLES
        }
    }

    @Test
    fun `case 8 - a shared set is read once and three targets go in two resolveMany calls at batch size 2`() {
        val shared = sets.put("acme/shared", listOf("region"))
        val second = sets.put("acme/second", listOf("region"))
        val third = sets.put("acme/third", listOf("region"))
        val dashboards =
            listOf(shared, shared, second, third).mapIndexed { i, set -> boards.put("acme/board_$i", set, listOf("region", "since")) }
        val widgets = WidgetFamily()
        val root = widgets.put("acme/root", dashboards)
        val registry = registry(widgets.descriptor())
        val graph = DependencyWalker(registry, TREE_LIMITS.copy(resolveBatchSize = 2), TREE_CLOCK).walk(listOf(root), context())
        val sharedNode = graph.nodes.single { it.name == shared.address.lookupKey }.nodeId
        withClue("two real dashboard -> set edges share one target") { graph.edges.count { it.toNodeId == sharedNode } shouldBe 2 }
        sets.calls.clear()

        val unmet = UnmetParameterNames(registry, batchSize = 2).evaluate(graph, context())

        withClue("the shared target is read once") { sets.calls.flatten().count { it == shared } shouldBe 1 }
        withClue("three distinct targets at batch size 2") { sets.calls.map { it.size } shouldBe listOf(2, 1) }
        sets.calls.flatten().toSet() shouldBe setOf(shared, second, third)
        graph.edges
            .filter { it.constraints.isNotEmpty() }
            .map { unmet.of(it.edgeId, it.constraints.single() as Constraint.ParameterNames) } shouldBe List(4) { listOf("since") }
    }

    @Test
    fun `case 9 - the property follows the stage's read - added, removed, and gone is absent with a 200`() {
        boards.put(BOARD, SET, listOf("region", "since"))
        // The walk sees since; the stage's read does not: unmet.
        sets.answers(SET, listOf(listOf("region", "since"), listOf("region")))
        edgeConstraint(service().tree(member(), FamilyKey.DASHBOARD, QUERY)).unmetNames() shouldBe listOf("since")
        sets.calls.size shouldBe 2
        // The reverse: the walk sees since missing, the stage sees it declared: no property.
        sets.answers(SET, listOf(listOf("region"), listOf("region", "since")))
        edgeConstraint(service().tree(member(), FamilyKey.DASHBOARD, QUERY)).containsKey(UNMET) shouldBe false
        // Gone on the stage's read: no property, no new diagnostic, still a 200 tree.
        sets.answers(SET, listOf(listOf("region")), goneAfterWalk = true)
        val gone = service().tree(member(), FamilyKey.DASHBOARD, QUERY)
        edgeConstraint(gone).containsKey(UNMET) shouldBe false
        gone.diagnostics.shouldBeEmpty()
        gone.complete shouldBe true
    }

    @Test
    fun `case 10 - a stage read that throws is the standard 503 with Retry-After and no graph data`() {
        boards.put(BOARD, SET, listOf("region", "since"))
        sets.answers(SET, listOf(listOf("region"), listOf("region")))
        sets.beforeCall = { index -> if (index == 1) throw DataAccessResourceFailureException(FAILURE) }

        val result = mvc(service()).perform(tree("dashboard", QUERY)).andReturn()

        result.retryable503()
        sets.calls.size shouldBe 2
        result.response.contentAsString shouldNotContain FAILURE
        result.response.contentAsString shouldNotContain "acme/"
    }

    @Test
    fun `case 10 - a deadline spent on the stage's read, or before it, is the same 503`() {
        boards.put(BOARD, SET, listOf("region", "since"))
        val clock = MovingTreeClock()
        val limits = deadlineLimits()
        sets.answers(SET, listOf(listOf("region"), listOf("region")))
        sets.beforeCall = { index -> if (index == 1) clock.advance(Duration.ofMillis(OVERRUN_MILLIS)) }
        mvc(service(limits, clock)).perform(tree("dashboard", QUERY)).andReturn().retryable503()
        sets.calls.size shouldBe 2

        // Spent on the walk's last read: the stage starts no batch at all.
        sets.answers(SET, listOf(listOf("region"), listOf("region")))
        sets.beforeCall = { index -> if (index == 0) clock.advance(Duration.ofMillis(OVERRUN_MILLIS)) }
        mvc(service(limits, clock)).perform(tree("dashboard", QUERY)).andReturn().retryable503()
        sets.calls.size shouldBe 1
    }

    @Test
    fun `case 11 - a DISCARDED set is evaluated - unmet_names beside the walker's DISCARDED diagnostic, complete false for it alone`() {
        boards.put(BOARD, SET, listOf("region", "since"))
        sets.answers(SET, listOf(listOf("region", "since")), lifecycle = Lifecycle.DISCARDED)
        val control = service().tree(member(), FamilyKey.DASHBOARD, QUERY)
        edgeConstraint(control).containsKey(UNMET) shouldBe false

        sets.answers(SET, listOf(listOf("region")), lifecycle = Lifecycle.DISCARDED)
        val discarded = service().tree(member(), FamilyKey.DASHBOARD, QUERY)

        edgeConstraint(discarded).unmetNames() shouldBe listOf("since")
        discarded.diagnostics.map { it.code } shouldBe listOf("DISCARDED")
        discarded.diagnostics shouldBe control.diagnostics
        discarded.complete shouldBe false
        discarded.complete shouldBe control.complete
        discarded.acyclic shouldBe control.acyclic
    }

    @Test
    fun `case 13 - a set the walk did not resolve is never read again, though a reread would find it lacking`() {
        val root = boards.put(BOARD, SET, listOf("region", "since"))
        sets.answers(SET, listOf(null, listOf("other")))
        val graph = walk(root)
        sets.calls.size shouldBe 1

        val unmet = stage().evaluate(graph, context())

        sets.calls.size shouldBe 1
        unmet.of(SET_EDGE, Constraint.ParameterNames(listOf("region", "since"))) shouldBe null
        // The same world through the service: the walk's one read, and none by the stage.
        sets.answers(SET, listOf(null, listOf("other")))
        val tree = service().tree(member(), FamilyKey.DASHBOARD, QUERY)
        sets.calls.size shouldBe 1
        edgeConstraint(tree).containsKey(UNMET) shouldBe false
        tree.diagnostics.map { it.code } shouldBe listOf("MISSING")
    }

    @Test
    fun `case 12 - the byte budget is judged on the payload WITH unmet_names - just below it, the tree is reduced`() {
        boards.put(BOARD, SET, listOf("region", "since"))
        sets.answers(SET, listOf(listOf("region")))
        val whole = service().tree(member(), FamilyKey.DASHBOARD, QUERY)
        edgeConstraint(whole).unmetNames() shouldBe listOf("since")
        val withProperty = MAPPER.writeValueAsBytes(whole).size
        val budget = withProperty - 1
        withClue("the payload without the property fits the budget, so only a stage BEFORE the check can be over it") {
            val without = DependencyTreeProjection.project(walk(exact(FamilyKey.DASHBOARD, BOARD, 1)))
            MAPPER.writeValueAsBytes(without).size shouldBeLessThanOrEqual budget
        }

        val bounded = service(TREE_LIMITS.copy(wireBytes = budget)).tree(member(), FamilyKey.DASHBOARD, QUERY)

        MAPPER.writeValueAsBytes(bounded).size shouldBeLessThanOrEqual budget
        bounded.diagnostics.map { it.code } shouldBe listOf("LIMIT")
        bounded.complete shouldBe false
        bounded.edges.shouldBeEmpty()
    }

    // ------------------------------------------------------------------------------- the registry and its families

    private fun registry(vararg extra: FamilyDescriptor<*>) = FamilyRegistry(listOf(boards.descriptor(), sets.descriptor()) + extra)

    private fun stage() = UnmetParameterNames(registry(), TREE_LIMITS.resolveBatchSize)

    private fun walk(root: ObjectRequest): GraphSnapshot =
        DependencyWalker(registry(), TREE_LIMITS, TREE_CLOCK).walk(listOf(root), context())

    private fun context() =
        ResolveContext(
            TREE_WS,
            MetadataSnapshot.READ_COMMITTED,
            Purpose.INTERACTIVE_READ,
            Authority.Member(UUID(0, 1)),
            Deadline.none(),
            ReadIsolation.NONE,
        )

    private fun service(
        limits: GraphLimits = TREE_LIMITS,
        clock: Clock = TREE_CLOCK,
    ) = treeService(emptyList(), listOf(DASHBOARD_ROW), limits, clock = clock, descriptors = listOf(boards.descriptor(), sets.descriptor()))

    private fun mvc(service: DependencyTreeService) =
        MockMvcBuilders
            .standaloneSetup(DependenciesController(service))
            .setControllerAdvice(ApiExceptionHandler())
            .build()
            .also { SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(member(), null, emptyList()) }

    private fun deadlineLimits(): GraphLimits =
        ReleaseDependencyProperties(metadataTimeoutSeconds = 1, statementTimeoutSeconds = 1).let {
            DependenciesConfiguration.treeLimits(it.toBounds(), it)
        }

    private fun edgeConstraint(data: DependencyTreeData): Map<String, Any> =
        data.edges
            .single()
            .constraints
            .single()

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any>.unmetNames(): List<String> = getValue(UNMET) as List<String>

    private fun MvcResult.retryable503() {
        val body: JsonNode = MAPPER.readTree(response.contentAsString)
        withClue(response.contentAsString) {
            response.status shouldBe HTTP_UNAVAILABLE
            body.path("error").path("code").asText() shouldBe PipelineErrorCodes.Dependencies.METADATA_UNAVAILABLE
            response.getHeader("Retry-After") shouldBe "5"
            body.fieldNames().asSequence().toSet() shouldBe setOf("schema_version", "correlation_id", "error")
        }
    }

    /** Dashboards: each version references its set at `parameter_set`, constrained by the names given, in that order. */
    private class BoardFamily : DependencyProvider<List<Reference>> {
        private val objects = LinkedHashMap<ObjectRequest, Outcome.Resolved<List<Reference>>>()

        fun clear() = objects.clear()

        fun put(
            key: String,
            set: ObjectRequest,
            names: List<String>,
        ): ObjectRequest {
            val request = exact(FamilyKey.DASHBOARD, key, 1)
            val reference =
                Reference(set, Relationship.PARAMETER_SET, "parameter_set", listOf(Constraint.ParameterNames(names)), set.address.lookupKey)
            objects[request] = resolved(FamilyKey.DASHBOARD, key, Lifecycle.DRAFT, listOf(reference))
            return request
        }

        override fun resolveMany(
            requests: List<ObjectRequest>,
            context: ResolveContext,
        ): Map<ObjectRequest, Outcome<List<Reference>>> = requests.associateWith { objects[it] ?: Outcome.Missing }

        override fun outgoing(resolved: Outcome.Resolved<List<Reference>>): List<Reference> = resolved.body

        fun descriptor(): FamilyDescriptor<List<Reference>> = descriptorOf(FamilyKey.DASHBOARD, this)
    }

    /** A28's synthetic family as a root over several dashboards — the only way one graph holds several set edges. */
    private class WidgetFamily : DependencyProvider<List<Reference>> {
        private val objects = LinkedHashMap<ObjectRequest, Outcome.Resolved<List<Reference>>>()

        fun put(
            key: String,
            dashboards: List<ObjectRequest>,
        ): ObjectRequest {
            val request = exact(TEST_WIDGET, key, 1)
            val references =
                dashboards.mapIndexed { i, d -> Reference(d, Relationship.IMPORT, "boards[$i]", spelledName = d.address.lookupKey) }
            objects[request] = resolved(TEST_WIDGET, key, Lifecycle.RELEASED, references)
            return request
        }

        override fun resolveMany(
            requests: List<ObjectRequest>,
            context: ResolveContext,
        ): Map<ObjectRequest, Outcome<List<Reference>>> = requests.associateWith { objects[it] ?: Outcome.Missing }

        override fun outgoing(resolved: Outcome.Resolved<List<Reference>>): List<Reference> = resolved.body

        fun descriptor(): FamilyDescriptor<List<Reference>> = descriptorOf(TEST_WIDGET, this)
    }

    /**
     * Parameter sets as the production provider answers them (a [StoredVersion] of a [ParameterSetReleaseBody]). Each set
     * answers its N-th request with the N-th declared list of its script (null = Missing); the last entry repeats unless
     * [answers] says the set is gone after the walk. Every call is recorded; [beforeCall] runs with the call's index.
     */
    private class SetFamily : DependencyProvider<StoredVersion<ParameterSetReleaseBody>> {
        private val scripts = HashMap<ObjectRequest, Script>()
        val calls = ArrayList<List<ObjectRequest>>()
        var beforeCall: (Int) -> Unit = {}

        private class Script(
            val declared: List<List<String>?>,
            val lifecycle: Lifecycle,
            val goneAfterWalk: Boolean,
            var served: Int = 0,
        )

        fun clear() {
            scripts.clear()
            calls.clear()
            beforeCall = {}
        }

        fun put(
            key: String,
            declared: List<String>,
        ): ObjectRequest = exact(FamilyKey.PARAMETER_SET, key, 1).also { scripts[it] = Script(listOf(declared), Lifecycle.DRAFT, false) }

        /** A fresh script for [request]; the call log and the hook are reset with it. */
        fun answers(
            request: ObjectRequest,
            declared: List<List<String>?>,
            lifecycle: Lifecycle = Lifecycle.DRAFT,
            goneAfterWalk: Boolean = false,
        ) {
            calls.clear()
            beforeCall = {}
            scripts[request] = Script(declared, lifecycle, goneAfterWalk)
        }

        override fun resolveMany(
            requests: List<ObjectRequest>,
            context: ResolveContext,
        ): Map<ObjectRequest, Outcome<StoredVersion<ParameterSetReleaseBody>>> {
            val index = calls.size
            calls.add(requests)
            beforeCall(index)
            return requests.associateWith { request ->
                val script = scripts[request] ?: return@associateWith Outcome.Missing
                val served = script.served++
                val declared =
                    if (script.goneAfterWalk && served > 0) null else script.declared[minOf(served, script.declared.lastIndex)]
                declared?.let { setOf(request, it, script.lifecycle) } ?: Outcome.Missing
            }
        }

        override fun outgoing(resolved: Outcome.Resolved<StoredVersion<ParameterSetReleaseBody>>): List<Reference> = emptyList()

        fun descriptor(): FamilyDescriptor<StoredVersion<ParameterSetReleaseBody>> = descriptorOf(FamilyKey.PARAMETER_SET, this)

        private fun setOf(
            request: ObjectRequest,
            declared: List<String>,
            lifecycle: Lifecycle,
        ): Outcome.Resolved<StoredVersion<ParameterSetReleaseBody>> {
            val key = request.address.lookupKey
            val body =
                ParameterSetBody(
                    displayName = "Filters",
                    parameters = declared.map { ParameterDefinition(it, it, type = LogicalType.STRING, kind = ParameterKind.INPUT) },
                )
            val stored =
                StoredVersion(
                    identity = ReleaseNodeIdentity(TREE_WS, ReleaseFamily.PARAMETER_SET, key, 1),
                    entityUuid = UUID.fromString(entityIdOf(key)),
                    name = key,
                    status = PipelineVersionStatus.DRAFT,
                    bodyHash = "hash-$key",
                    currentVersion = null,
                    body = ParameterSetReleaseBody(body),
                )
            return Outcome.Resolved(identityOf(FamilyKey.PARAMETER_SET, key), factsOf(key, lifecycle), stored)
        }
    }

    private companion object {
        const val BOARD = "acme/board"
        const val UNMET = DependencyTreeProjection.UNMET_NAMES
        const val SET_EDGE = "e0"
        const val FAILURE = "metadata replica pg-metadata-2 refused the connection"
        const val HTTP_UNAVAILABLE = 503
        const val OVERRUN_MILLIS = 1_100L
        const val SEED = 892L
        const val SAMPLES = 200
        const val MIN_BRANCH_SAMPLES = 10
        val SET = exact(FamilyKey.PARAMETER_SET, "acme/filters", 1)
        val QUERY = "name=${enc(BOARD)}&version=1"
        val POOL = listOf("since", "region", "country", "a_b", "zeta", "alpha")
        val MAPPER = jacksonObjectMapper()
        val DASHBOARD_ROW = DependencyFamilyTransport.production(DependenciesRequestTest.LENSES).row(FamilyKey.DASHBOARD)

        fun <B : Any> descriptorOf(
            key: FamilyKey,
            provider: DependencyProvider<B>,
        ) = FamilyDescriptor(
            key,
            SelectorKind.entries.toSet(),
            FamilyKey.PRODUCTION[key] ?: Capabilities.NONE,
            1,
            provider,
            FamilyDescriptor.NO_SAFE_SUMMARY,
        )

        fun identityOf(
            family: FamilyKey,
            key: String,
        ) = ResolvedIdentity(family, OwnerScope.Workspace(TREE_WS), entityIdOf(key), 1)

        fun factsOf(
            key: String,
            lifecycle: Lifecycle,
        ) = NodeFacts(name = key, versionInfo = VersionInfo.Artifact(1), lifecycle = lifecycle, bodyHash = "hash-$key")

        fun <B : Any> resolved(
            family: FamilyKey,
            key: String,
            lifecycle: Lifecycle,
            body: B,
        ) = Outcome.Resolved(identityOf(family, key), factsOf(key, lifecycle), body)
    }
}
