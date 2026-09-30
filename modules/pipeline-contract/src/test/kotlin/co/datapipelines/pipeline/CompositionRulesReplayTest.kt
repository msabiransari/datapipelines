package co.datapipelines.pipeline

import co.datapipelines.pipeline.PipelineErrorCodes.Validation
import com.fasterxml.jackson.databind.JsonNode
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The #264 replay guard: the tightened composition parameter rules (a literal judged by the
 * child's WHOLE declaration; a same-type `${ref}` whose parent descriptor must widen losslessly
 * into the child's) replayed over EVERY pipeline body this repository ships or fixes in test
 * sources — the published demo set (`scripts/sample-data/content/`) and the §16 worked-example
 * fixtures. The "hand-built fixtures can't falsify" lesson: the rules are measured over the real
 * set, as a count, not argued from examples.
 *
 * A body that already saved and would now newly refuse fails here, by rule — so a shipped demo
 * or a documented example can never be silently broken by a tightening. The counts are the
 * evidence and are printed; a corpus that grew a composition mapping this test did not judge
 * would move `judgedMappings`, not hide behind zero.
 *
 * Method (re-runnable): every body from the sources below is parsed with the current model; a
 * body's PIPELINE nodes are resolved against the corpus itself (a pin the corpus cannot resolve
 * is counted `unresolvedMappings` and judged by no rule); every supplied key that names a child
 * DECLARED parameter is one judged mapping (calculator `context_key` targets are typed by the
 * run's contract and carry no declaration to judge — excluded by the same rule the save path
 * uses). `CompositionRules.check` itself is the judge — the production rule, not a copy.
 */
class CompositionRulesReplayTest {
    @Test
    fun `no shipped or fixture pipeline body is refused by the tightened composition parameter rules`() {
        val (corpus, bodies) = loadCorpus()
        withClue("the replay corpus must not silently be empty") { bodies shouldBeGreaterThan 0 }

        val judged = RefusalCounts()
        corpus.values.flatten().forEach { bodyNode -> replayBody(bodyNode, corpus, judged) }

        println(
            "REPLAY bodies=$bodies judgedMappings=${judged.judgedMappings} " +
                "unresolvedMappings=${judged.unresolvedMappings} refused: " +
                "literalConstraint=${judged.literalConstraint} literalType=${judged.literalType} " +
                "narrowing=${judged.narrowing}",
        )
        withClue("the replay must judge compositions, not an empty parameter set") {
            judged.judgedMappings shouldBeGreaterThan 0
        }
        withClue("a shipped or fixture body newly refused by the tightened rules — the tightening is the owner's call") {
            judged.total() shouldBe 0
        }
    }

    /** Every body from [SOURCES], by name; paired with the total body count. */
    private fun loadCorpus(): Pair<Map<String, MutableList<JsonNode>>, Int> {
        val corpus = LinkedHashMap<String, MutableList<JsonNode>>()
        var bodies = 0
        SOURCES.forEach { path ->
            val root = Fixtures.mapper.readTree(Fixtures.repoFile(path))
            val bodiesInFile =
                if (root.has("pipelines")) {
                    root.path("pipelines").toList()
                } else if (root.has("nodes")) {
                    // The §16 worked-example files are bare bodies.
                    listOf(root)
                } else {
                    emptyList()
                }
            bodiesInFile.forEach { body ->
                corpus.merge(body.path("name").asText(), mutableListOf(body)) { a, b -> a.apply { addAll(b) } }
                bodies++
            }
        }
        return corpus to bodies
    }

    /** Resolves a pin against the corpus itself (the pinned version first, then the only body). */
    private fun replayResolver(corpus: Map<String, List<JsonNode>>): PipelineResolver =
        PipelineResolver { _, name, version ->
            corpus[name]
                ?.let { list -> list.firstOrNull { it.path("version").asInt() == version } ?: list.firstOrNull() }
                ?.let { ResolvedPipeline(Fixtures.mapper.treeToValue(it, Pipeline::class.java), false) }
        }

    private fun replayBody(
        bodyNode: JsonNode,
        corpus: Map<String, List<JsonNode>>,
        judged: RefusalCounts,
    ) {
        val pipeline = Fixtures.mapper.treeToValue(bodyNode, Pipeline::class.java)
        pipeline.nodes.filter { it.type == NodeType.PIPELINE }.forEach { node ->
            val ref = node.pipeline
            val child = if (ref == null) null else resolveIn(corpus, ref.name, ref.version)
            if (child == null) {
                judged.unresolvedMappings += node.parameters.orEmpty().size
            } else {
                // The composition check alone: this replay asks only the §12.9 parameter rules,
                // not the whole validator (the corpus bodies cite datasources and templates this
                // harness does not carry, and those verdicts are not this guard's question).
                val into = FailureCollector()
                CompositionRules.check(pipeline, replayResolver(corpus), MAX_DEPTH, UUID.randomUUID(), OrgContext.DEFAULTS, into)
                judged.count(into.toResult().failures)
                judged.judgedMappings += node.parameters.orEmpty().count { it.key in child.pipeline.parameters }
            }
        }
    }

    private fun resolveIn(
        corpus: Map<String, List<JsonNode>>,
        name: String,
        version: Int,
    ): ResolvedPipeline? =
        corpus[name]
            ?.let { list -> list.firstOrNull { it.path("version").asInt() == version } ?: list.firstOrNull() }
            ?.let { ResolvedPipeline(Fixtures.mapper.treeToValue(it, Pipeline::class.java), false) }

    /** The replay's tally, by rule — printed and asserted. */
    private class RefusalCounts {
        var judgedMappings = 0
        var unresolvedMappings = 0
        var literalConstraint = 0
        var literalType = 0
        var narrowing = 0

        fun count(failures: List<ValidationFailure>) {
            failures.forEach { failure ->
                when {
                    failure.code == Validation.PIPELINE_PARAMETER_INVALID && failure.details["reason"] == "narrowing" -> {
                        narrowing++
                    }

                    failure.code == Validation.PIPELINE_PARAMETER_INVALID -> {
                        literalConstraint++
                    }

                    failure.code == Validation.PIPELINE_PARAMETER_TYPE_MISMATCH &&
                        failure.path.startsWith("nodes[") && failure.path.contains(".parameters.") -> {
                        literalType++
                    }
                }
            }
        }

        fun total(): Int = literalConstraint + literalType + narrowing
    }

    private companion object {
        /** The demo pipelines (the lane instance loads exactly these) and the §16 worked examples. */
        val SOURCES =
            listOf(
                "scripts/sample-data/content/examples.json",
                "scripts/sample-data/content/examples-lake.json",
                "modules/pipeline-contract/src/test/resources/examples/spec-16.3-writeback.json",
                "modules/pipeline-contract/src/test/resources/examples/spec-3.1-monthly-revenue-report.json",
                "modules/pipeline-contract/src/test/resources/examples/spec-9.4-zero-caller.json",
                "modules/pipeline-contract/src/test/resources/examples/spec-16.4-ddl-and-16.5-dml.json",
                "modules/pipeline-contract/src/test/resources/examples/spec-16.1-minimal.json",
            )

        /** Composition depth is not this replay's question; the default bound from configuration §3.x. */
        const val MAX_DEPTH = 5
    }
}
