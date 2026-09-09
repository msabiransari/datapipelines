package co.datapipelines.pipeline

import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.util.UUID
import kotlin.random.Random

/**
 * **101's acceptance gate** (versioning §13): a model-based property test over the version
 * lifecycle — real Postgres ([SharedPostgres], the shipped migrations), the real
 * repositories and services of this module, both postures.
 *
 * ## Placement (a named deviation from the round's brief)
 *
 * The brief said `tests/integration-tests`; the build's layering law
 * (`allowedInternalDependencies`) allows that module `:modules:app` ONLY — and it tests
 * through HTTP, which is the wrong lane for 2000×25 service-level events. This module owns
 * the aggregate, the services, the real-Postgres harness and the §3.5 parser — the test
 * lives with what it proves. E2E/REST coverage of the verbs is `TemplatesControllerTest` /
 * `PipelinesControllerTest` and 102's UI round.
 *
 * ## What it proves, and how
 *
 * 1. **The doc's rows, replayed.** Every row of versioning.md §3.5.2's lifecycle table —
 *    parsed through the same [VersionLifecycleTable] the drift guard reads, never a copy —
 *    is materialized in the database and its event fired at the real surface. The outcome
 *    code, the After state (versions + pointer) and the entity verdict must equal the row.
 *    Four rows are excluded with reasons: the template-twin rows and the two pin-guard rows
 *    whose evidence is a template registry read (their codes are `template.*`; the template
 *    suites own them), and the restated §5.3 draft-template-pin release refusal (035's
 *    rule, proven by its own suite; the stub registry here answers RELEASED).
 * 2. **Random sequences.** A seeded RNG (seed printed in every failure) generates event
 *    sequences (length 1–25) per posture; after EVERY event the database state must equal
 *    the reference model's state and the §13 invariants must hold. The gate runs
 *    `-DversionLifecycle.sequences=N` sequences per posture (default 2000) with a bounded,
 *    measured runtime, printed at the end.
 * 3. **Shrinking.** A failing sequence is delta-debugged to a minimal counterexample
 *    before it is reported — reproduction is a re-run, never a guess.
 *
 * ## The reference model
 *
 * [RefModel] is §3 re-stated as a pure function: `(state, event, posture) → (outcome,
 * state)`. It holds the SAME rules the services implement; the test's entire claim is that
 * the two cannot disagree. Falsify by commenting a rule out of the SERVICE (§13 names the
 * canonical one: eligibility under hardened) — the property must fail within the run; the
 * round's handback carries the transcript.
 *
 * ## Scope decisions, stated
 *
 * - **Release** fires at [PipelineService.release] over a permissive validator double —
 *   §12's validation is release's own pre-existing ceremony with its own suites; restubbing
 *   the whole registry here would test the stub, not the rule.
 * - **Import** fires at [PipelineRepository.appendReleasedVersion] — the pointer rule lives
 *   there; `PipelineImportService` (web) is its parser.
 * - **Edges**: the generator adds parent pins and executions as events, so graph rule 1
 *   and the purge-deletes-executions invariant are exercised randomly, not only through
 *   the table's edge rows.
 * - **Hardened posture** shares the repository but a second service graph with
 *   [AuthoringGuard]`(false)` — the capability flag IS the posture (§3.4) — and entities
 *   materialize as import-shaped (released-only) states, the only shape a receiver holds.
 */
@Suppress("LargeClass") // the acceptance gate in one place, the same argument the repository suite makes
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VersionLifecycleModelTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var repository: PipelineRepository
    private val actor: UUID = UUID.randomUUID()
    private val deserializer = PipelineDeserializer()

    private val dev: PipelineService by lazy { serviceFor(authoring = true) }
    private val hard: PipelineService by lazy { serviceFor(authoring = false) }

    @BeforeAll
    fun connect() {
        jdbc = NamedParameterJdbcTemplate(SharedPostgres.pooledDataSource())
    }

    @BeforeEach
    fun setUp() {
        repository = PipelineRepository(jdbc)
        jdbc.jdbcTemplate.execute("TRUNCATE pipelines, users CASCADE")
        jdbc.jdbcTemplate.execute(
            "INSERT INTO workspaces (id, name, display_name)" +
                " VALUES ('defa0000-0000-0000-0000-000000000001', 'default', 'Default')",
        )
        jdbc.update(
            """
            INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin)
            VALUES (:id, 'lifecycle-model@datapipelines.test', 'Lifecycle Model', 'test', 'lifecycle-model-sub', TRUE, TRUE)
            """.trimIndent(),
            mapOf("id" to actor),
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Phase 1 — the doc's rows, replayed
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `every lifecycle-table row replays against the real surface`() {
        val rows = VersionLifecycleTable.parse(Fixtures.repoFile(SPEC_PATH).readText())
        withClue("the table must parse (the drift guard owns the floor)") { rows.size shouldBe 67 }

        rows.forEachIndexed { index, row ->
            if (excluded(row)) return@forEachIndexed
            listOf("dev", "hard").forEach { posture ->
                if (row.posture != "both" && row.posture != posture) return@forEach
                withClue("row #${index + 1} ${row.shape} `${row.before}` ${row.event} [$posture]") {
                    replayRow(row, posture)
                }
            }
        }
    }

    /** Stated exclusions (KDoc): template-side rows and the restated 035 release-pin rule. */
    private fun excluded(row: VersionLifecycleTable.Row): Boolean =
        row.edge.contains("template twin") ||
            row.citedCodes.any { it.startsWith("template.") } ||
            row.outcome.contains("template_not_released")

    private fun replayRow(
        row: VersionLifecycleTable.Row,
        posture: String,
    ) {
        val id = materialize(row.versionsBefore, row.pointerBefore)
        val pinTarget =
            Regex("pins v(\\d+)").find(row.edge)?.groupValues?.get(1)?.toInt()
                ?: if (row.outcome.contains("pinned")) {
                    Regex("\\(v(\\d+)").find(row.event)?.groupValues?.get(1)?.toInt()
                } else {
                    null
                }
        if (pinTarget != null) insertParentPinning(id, nameOf(id), pinTarget)

        val outcome = fireEvent(id, row.event, posture)
        if (row.allowed) {
            outcome.code shouldBe null
        } else {
            outcome.code shouldBe row.citedCodes.first()
        }
        compareAgainstRow(id, row)
    }

    private fun compareAgainstRow(
        id: UUID,
        row: VersionLifecycleTable.Row,
    ) {
        val (versions, pointer, exists) = readState(id)
        if (row.after.contains("entity gone")) {
            exists shouldBe false
            return
        }
        exists shouldBe true
        val after = row.after.trim('`')
        withClue("After versions") {
            versions.entries.joinToString(" ") { "${it.key}${it.value}" } shouldBe after.substringBefore("cur=").trim()
        }
        val expectedPointer =
            Regex("cur=(∅|\\d+)").find(after)?.groupValues?.get(1)?.let { if (it == "∅") null else it.toInt() }
        withClue("After pointer") { pointer shouldBe expectedPointer }
    }

    // ---------------------------------------------------------------------------------------------
    // Phase 2 — random sequences per posture
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `random event sequences satisfy the model and the invariants in both postures`() {
        val sequences = System.getProperty("versionLifecycle.sequences")?.toIntOrNull() ?: DEFAULT_SEQUENCES
        val started = System.nanoTime()
        var events = 0L

        listOf("dev" to dev, "hard" to hard).forEach { (posture, service) ->
            val seedBase = SEED_BASE + (if (posture == "dev") 0L else 1_000_000_000L)
            repeat(sequences) { seq ->
                val seed = seedBase + seq
                val failure = runSequence(seed, posture, service) { events += it }
                if (failure != null) {
                    val shrunk = shrink(failure, posture)
                    throw AssertionError(
                        buildString {
                            appendLine("version-lifecycle model violation (seed=$seed, posture=$posture)")
                            appendLine("shrunk counterexample:")
                            shrunk.events.forEachIndexed { i, ev -> appendLine("  $i. $ev ${if (i == shrunk.index) "-> FAILED here" else ""}") }
                            append("expected code=${shrunk.expected} actual=${shrunk.actual}; ")
                            append("expected state=${shrunk.expectedState} actual=${shrunk.actualState}; ")
                            appendLine("diagnostics: ${shrunk.diagnostics}")
                        },
                    )
                }
            }
        }

        val seconds = (System.nanoTime() - started) / 1_000_000_000.0
        println(
            "VersionLifecycleModelTest: $sequences sequences/posture, $events events total, " +
                String.format("%.1fs", seconds),
        )
    }

    /** One generated sequence; null on success, the failure (for shrinking) on violation. */
    private fun runSequence(
        seed: Long,
        posture: String,
        service: PipelineService,
        countEvents: (Int) -> Unit = {},
    ): SeqFailure? {
        val random = Random(seed)
        val length = random.nextInt(1, 26)
        val name = "test/lc$seed/p"
        val id = materializeInitialState(posture, random, name)
        val ref = RefModel(posture == "dev")
        var model = readState(id).let { (versions, pointer, _) -> ref.initial(versions, pointer) }
        val fired = mutableListOf<Ev>()
        val successfulPurges = mutableSetOf<Int>()

        repeat(length) {
            val ev = generateEvent(random, model)
            val outcome = applyEvent(id, ev, service)
            fired.add(ev)
            countEvents(1)
            if (ev is Ev.Purge && outcome.code == null) successfulPurges.add(ev.version)

            val (expectedCode, expectedState) = ref.step(model, ev)
            val (versions, pointer, exists) = readState(id)
            // Pins are environment edges the child's rows cannot show; actual carries the
            // fired set, the same source the model stepped from.
            val firedPins = fired.filterIsInstance<Ev.AddPin>().map { it.version }.toSet()
            val actualState = RefModel.State(LinkedHashMap(versions), pointer, !exists, firedPins)

            if (outcome.code != expectedCode || actualState != expectedState) {
                // Diagnostics: what the pin scan sees at the moment of divergence — the
                // counterexample's first reader.
                val diag = diagnostics(id)
                return SeqFailure(seed, fired.toList(), fired.lastIndex, expectedCode, outcome.code, expectedState, actualState, diag)
            }
            assertInvariants(id, expectedState, posture, fired, successfulPurges)
            model = expectedState
        }
        return null
    }

    /** Delta-debugging shrink: drop events while the failure reproduces. */
    private fun shrink(
        failure: SeqFailure,
        posture: String,
    ): SeqFailure {
        var current = failure
        var changed = true
        val service = if (posture == "dev") dev else hard
        while (changed) {
            changed = false
            val candidate = current.events.toMutableList()
            for (i in candidate.indices.reversed()) {
                if (i == candidate.lastIndex) continue // keep the failing terminal event
                val trial = candidate.filterIndexed { index, _ -> index != i }
                var repro: SeqFailure? = null
                // Re-seed and replay the TRIAL only: same initial state, shorter prefix. The
                // previous attempt's entity is deleted first — the name is the address, and a
                // leftover row would collide on re-materialize (D59: names are unique forever).
                val shrinkName = "test/lc${failure.seed}s/p"
                // Executions first: they reference pipelines WITHOUT a cascade (V1's FK is
                // NO ACTION — the same order the real entity purge deletes in).
                jdbc.update(
                    "DELETE FROM pipeline_executions WHERE pipeline_id IN (SELECT id FROM pipelines WHERE name = :n AND workspace_id = :ws)",
                    mapOf("n" to shrinkName, "ws" to WORKSPACE),
                )
                jdbc.update("DELETE FROM pipelines WHERE name = :n AND workspace_id = :ws", mapOf("n" to shrinkName, "ws" to WORKSPACE))
                val id = materializeInitialState(posture, Random(failure.seed), shrinkName)
                val ref = RefModel(posture == "dev")
                var model = readState(id).let { (versions, pointer, _) -> ref.initial(versions, pointer) }
                run loop@{
                    val trialPins = mutableSetOf<Int>()
                    trial.forEachIndexed { index, ev ->
                        val outcome = applyEvent(id, ev, service)
                        val (expectedCode, expectedState) = ref.step(model, ev)
                        val (versions, pointer, exists) = readState(id)
                        if (ev is Ev.AddPin) trialPins.add(ev.version)
                        val actualState = RefModel.State(LinkedHashMap(versions), pointer, !exists, trialPins.toSet())
                        if (outcome.code != expectedCode || actualState != expectedState) {
                            repro =
                                SeqFailure(
                                    failure.seed, trial.toList(), index, expectedCode, outcome.code,
                                    expectedState, actualState, diagnostics(id),
                                )
                            return@loop
                        }
                        model = expectedState
                    }
                }
                repro?.let {
                    current = it
                    candidate.removeAt(i)
                    changed = true
                }
            }
        }
        return current
    }

    // ---------------------------------------------------------------------------------------------
    // The events
    // ---------------------------------------------------------------------------------------------

    internal sealed interface Ev {
        /** The entity is gone — both sides agree nothing happens (sequences may trail off). */
        data object Noop : Ev

        data object Release : Ev

        data class Discard(
            val version: Int,
        ) : Ev

        data class Restore(
            val version: Int,
        ) : Ev

        data class Purge(
            val version: Int,
        ) : Ev

        data object PurgeEntity : Ev

        data class Switch(
            val version: Int,
        ) : Ev

        data object Import : Ev

        data class AddPin(
            val version: Int,
        ) : Ev

        data class AddExecution(
            val version: Int,
        ) : Ev
    }

    private fun generateEvent(
        random: Random,
        model: RefModel.State,
    ): Ev {
        if (model.gone) return Ev.Noop
        val versions = model.versions.keys.toList()
        return when (random.nextInt(10)) {
            0 -> Ev.Release
            1 -> Ev.Discard(versions.random(random))
            2 -> Ev.Restore(versions.random(random))
            3 -> Ev.Purge(versions.random(random))
            4 -> Ev.PurgeEntity
            5 -> Ev.Switch(versions.random(random))
            6, 7 -> Ev.Import
            8 ->
                model.versions
                    .filterValues { it == 'R' }
                    .keys
                    .randomOrNull(random)
                    ?.let { Ev.AddPin(it) }
                    ?: Ev.Noop // no released version: a pin would name a draft, which D58 refuses at save
            else -> Ev.AddExecution(model.versions.filterValues { it != 'X' }.keys.randomOrNull(random) ?: versions.first())
        }
    }

    /** The counterexample's first reader: row name, pin scan, raw pin edges at divergence. */
    private fun diagnostics(id: UUID): String {
        val rowName: String? = jdbc.queryForObject("SELECT name FROM pipelines WHERE id = :id", mapOf("id" to id), String::class.java)
        val scan = repository.findLiveParentsPinningVersion(WORKSPACE, rowName ?: "?", 2).map { it.pipelineName }
        val rawPins =
            jdbc.query(
                """SELECT pp.name AS parent, pnode->'pipeline'->>'name' AS pinned_name,
                          (pnode->'pipeline'->>'version') AS pinned_version, pv.status AS pv_status
                     FROM pipelines pp JOIN pipeline_versions pv ON pv.pipeline_id = pp.id
                    CROSS JOIN LATERAL jsonb_array_elements(pv.body_json->'nodes') AS pnode
                    WHERE pp.workspace_id = :ws AND pnode->'pipeline'->>'name' IS NOT NULL""",
                mapOf("ws" to WORKSPACE),
            ) { rs, _ -> "${rs.getString("parent")}->${rs.getString("pinned_name")}@${rs.getString("pinned_version")}(${rs.getString("pv_status")})" }
        return "rowName=$rowName pinScan(v2)=$scan rawPins=$rawPins"
    }

    private data class Outcome(val code: String?)

    /** Fires one event at the REAL surface, normalizing the outcome to a code-or-null. */
    private fun applyEvent(
        id: UUID,
        ev: Ev,
        service: PipelineService,
    ): Outcome =
        try {
            when (ev) {
                Ev.Noop -> Outcome(null)
                Ev.Release -> {
                    val draft = repository.findDraftDetail(WORKSPACE, id)
                    service.release(WORKSPACE, id, draft?.bodyHash ?: "irrelevant", actor)
                    Outcome(null)
                }
                is Ev.Discard -> {
                    service.discardVersion(WORKSPACE, id, ev.version, actor)
                    Outcome(null)
                }
                is Ev.Restore -> {
                    service.restoreVersion(WORKSPACE, id, ev.version)
                    Outcome(null)
                }
                is Ev.Purge -> {
                    service.purgeVersion(WORKSPACE, id, ev.version)
                    Outcome(null)
                }
                Ev.PurgeEntity -> {
                    service.purgeEntity(WORKSPACE, id, includeExclusiveDraftTemplates = false)
                    Outcome(null)
                }
                is Ev.Switch -> {
                    service.switchCurrent(WORKSPACE, id, ev.version)
                    Outcome(null)
                }
                Ev.Import -> {
                    val next = (readState(id).first.keys.maxOrNull() ?: 0) + 1
                    val body = pipelineBody(nameOf(id), next)
                    repository.appendReleasedVersion(WORKSPACE, id, deserializer.readOrThrow(body), body, actor)
                    Outcome(null)
                }
                is Ev.AddPin -> {
                    insertParentPinning(id, nameOf(id), ev.version)
                    Outcome(null)
                }
                is Ev.AddExecution -> {
                    insertExecution(id, ev.version)
                    Outcome(null)
                }
            }
        } catch (e: DatapipelinesException) {
            Outcome(e.code)
        }

    /** The row replay's dispatch — parses the doc's event cell. */
    private fun fireEvent(
        id: UUID,
        eventCell: String,
        posture: String,
    ): Outcome {
        val service = if (posture == "dev") dev else hard
        val version = Regex("v(\\d+)").find(eventCell)?.groupValues?.get(1)?.toInt()
        return try {
            when {
                eventCell.startsWith("release") -> {
                    val draft = repository.findDraftDetail(WORKSPACE, id)
                    service.release(WORKSPACE, id, draft?.bodyHash ?: "irrelevant", actor)
                    Outcome(null)
                }
                eventCell.startsWith("discard") -> {
                    service.discardVersion(WORKSPACE, id, checkNotNull(version), actor)
                    Outcome(null)
                }
                eventCell.startsWith("restore") -> {
                    service.restoreVersion(WORKSPACE, id, checkNotNull(version))
                    Outcome(null)
                }
                eventCell.startsWith("purge(entity)") -> {
                    service.purgeEntity(WORKSPACE, id, includeExclusiveDraftTemplates = false)
                    Outcome(null)
                }
                eventCell.startsWith("purge") -> {
                    service.purgeVersion(WORKSPACE, id, checkNotNull(version))
                    Outcome(null)
                }
                eventCell.startsWith("switch") -> {
                    service.switchCurrent(WORKSPACE, id, checkNotNull(version))
                    Outcome(null)
                }
                eventCell.startsWith("import") -> {
                    val next = (readState(id).first.keys.maxOrNull() ?: 0) + 1
                    val body = pipelineBody(nameOf(id), next)
                    repository.appendReleasedVersion(WORKSPACE, id, deserializer.readOrThrow(body), body, actor)
                    Outcome(null)
                }
                else -> throw IllegalStateException("unparsed event cell: $eventCell")
            }
        } catch (e: DatapipelinesException) {
            Outcome(e.code)
        }
    }

    private data class SeqFailure(
        val seed: Long,
        val events: List<Ev>,
        val index: Int,
        val expected: String?,
        val actual: String?,
        val expectedState: RefModel.State,
        val actualState: RefModel.State,
        val diagnostics: String = "",
    )

    // ---------------------------------------------------------------------------------------------
    // §13's invariants, asserted after EVERY random event
    // ---------------------------------------------------------------------------------------------

    private fun assertInvariants(
        id: UUID,
        model: RefModel.State,
        posture: String,
        fired: List<Ev>,
        successfulPurges: Set<Int>,
    ) {
        val (versions, pointer, exists) = readState(id)
        if (model.gone) {
            withClue("INV the purged entity is gone") { exists shouldBe false }
            return
        }
        withClue("INV ≥1 version (D57)") { versions.isNotEmpty() shouldBe true }
        withClue("INV ≤1 DRAFT") { versions.values.count { it == 'D' } shouldBe (if ('D' in versions.values) 1 else 0) }
        // NOT "the draft is the highest number": an import may land a release above a draft
        // (§3.5's `{D}` first-import row). The allocation invariant is unobservable post-hoc;
        // "at most one draft" above is the checkable half (§3.3's amended wording).
        if (pointer != null) {
            val status = versions[pointer]
            withClue("INV pointer names a live version") {
                (status == 'R' || (posture == "dev" && status == 'D')) shouldBe true
            }
        }
        // Graph rule 1's invariant: no live parent pin points at a DISCARDED/purged version.
        fired.filterIsInstance<Ev.AddPin>().map { it.version }.toSet().forEach { v ->
            withClue("INV pins point at live targets (v$v)") { (versions[v]?.let { it != 'X' } ?: false) shouldBe true }
        }
        // Purge leaves zero orphan execution rows — scoped to executions added BEFORE the
        // purge: a purged draft's number can be re-allocated (§3.1) and legitimately
        // executed again, and those new rows are not the old purge's orphans.
        val addedSincePurge = mutableMapOf<Int, Int>()
        fired.forEach { ev ->
            when (ev) {
                is Ev.Purge -> if (ev.version in successfulPurges) addedSincePurge[ev.version] = 0
                is Ev.AddExecution -> addedSincePurge[ev.version] = (addedSincePurge[ev.version] ?: 0) + 1
                else -> Unit
            }
        }
        // A REFUSED purge (last_release) deletes nothing — only successful ones must leave zero.
        fired.filterIsInstance<Ev.Purge>().filter { it.version in successfulPurges }.forEach { purged ->
            val remaining =
                checkNotNull(
                    jdbc.queryForObject(
                        "SELECT COUNT(*) FROM pipeline_executions WHERE pipeline_id = :id AND pipeline_version = :v",
                        mapOf("id" to id, "v" to purged.version),
                        Int::class.java,
                    ),
                )
            withClue("INV purge leaves zero execution rows (v${purged.version})") {
                remaining shouldBe (addedSincePurge[purged.version] ?: 0)
            }
        }
        // Entity status = the §3.2 derivation; all-discarded ⇒ pointer NULL.
        if (versions.values.all { it == 'X' }) {
            withClue("INV all-discarded ⇒ pointer NULL") { pointer shouldBe null }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // State materialization and reads — direct SQL: the model test builds STATES, not journeys
    // ---------------------------------------------------------------------------------------------

    private fun materialize(
        versions: Map<Int, Char>,
        pointer: Int?,
        name: String = "test/lc${UUID.randomUUID().toString().substring(0, 8)}/p",
    ): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO pipelines (id, name, display_name, description, owner_id, workspace_id, current_version)
            VALUES (:id, :name, 'M', 'd', :owner, :ws, :pointer)
            """.trimIndent(),
            mapOf("id" to id, "name" to name, "owner" to actor, "ws" to WORKSPACE, "pointer" to pointer),
        )
        versions.forEach { (version, letter) ->
            val status =
                when (letter) {
                    'D' -> "DRAFT"
                    'R' -> "RELEASED"
                    'X' -> "DISCARDED"
                    else -> throw IllegalArgumentException("letter $letter")
                }
            jdbc.update(
                """
                INSERT INTO pipeline_versions
                    (pipeline_id, version, body_json, body_hash, status, created_by,
                     released_at, released_by, discarded_at, discarded_by)
                VALUES (:id, :version, CAST(:body AS jsonb),
                        encode(sha256(convert_to(CAST(:body AS jsonb)::text, 'UTF8')), 'hex'),
                        :status, :owner,
                        CASE WHEN :status IN ('RELEASED','DISCARDED') THEN NOW() END, :owner,
                        CASE WHEN :status = 'DISCARDED' THEN NOW() END,
                        CASE WHEN :status = 'DISCARDED' THEN :owner END)
                """.trimIndent(),
                mapOf(
                    "id" to id,
                    "version" to version,
                    "body" to pipelineBody(name, version),
                    "status" to status,
                    "owner" to actor,
                ),
            )
        }
        return id
    }

    /**
     * The entity lands under its FINAL name — the bodies carry it too (§3.7: the row
     * indexes the body, and a release re-adopts the body's name), so a post-hoc row rename
     * would desynchronize row and body exactly when the next release fires, orphaning
     * parent pins that named the renamed entity.
     */
    private fun materializeInitialState(
        posture: String,
        random: Random,
        name: String,
    ): UUID =
        if (posture == "dev") {
            materialize(mapOf(1 to 'D'), null, name)
        } else {
            // A receiver's shape: import-built releases only.
            val releases = if (random.nextBoolean()) mapOf(1 to 'R') else mapOf(1 to 'R', 2 to 'R')
            materialize(releases, releases.keys.max(), name)
        }

    private fun letterOf(status: String): Char =
        when (status) {
            "DRAFT" -> 'D'
            "RELEASED" -> 'R'
            "DISCARDED" -> 'X'
            else -> throw IllegalStateException("unknown status $status")
        }

    private fun nameOf(id: UUID): String =
        checkNotNull(
            jdbc.queryForObject("SELECT name FROM pipelines WHERE id = :id", mapOf("id" to id), String::class.java),
        )

    /** (versions by number, pointer, entity-exists). */
    private fun readState(id: UUID): Triple<Map<Int, Char>, Int?, Boolean> {
        val exists =
            checkNotNull(
                jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM pipelines WHERE id = :id)",
                    mapOf("id" to id),
                    Boolean::class.java,
                ),
            )
        if (!exists) return Triple(emptyMap(), null, false)
        val versions =
            jdbc
                .query(
                    "SELECT version, status FROM pipeline_versions WHERE pipeline_id = :id ORDER BY version",
                    mapOf("id" to id),
                ) { rs, _ -> rs.getInt("version") to letterOf(rs.getString("status")) }
                .toMap()
        val pointer =
            jdbc
                .query(
                    "SELECT current_version FROM pipelines WHERE id = :id",
                    mapOf("id" to id),
                ) { rs, _ -> rs.getInt("current_version").takeUnless { rs.wasNull() } }
                .singleOrNull()
        return Triple(versions, pointer, true)
    }

    private fun insertExecution(
        id: UUID,
        version: Int,
    ) {
        jdbc.update(
            """
            INSERT INTO pipeline_executions
                (execution_id, pipeline_id, pipeline_version, status, parameters_json, triggered_by,
                 triggered_via, root_execution_id)
            VALUES (:execution, :id, :version, 'SUCCESS', '{}', :owner, 'REST', :execution)
            """.trimIndent(),
            mapOf("execution" to UUID.randomUUID(), "id" to id, "version" to version, "owner" to actor),
        )
    }

    /** A live parent DRAFT version whose body exact-pins `child@version` (graph rule 1's edge). */
    private fun insertParentPinning(
        childId: UUID,
        childName: String,
        version: Int,
    ) {
        val parentId = UUID.randomUUID()
        val parentName = "test/lc${parentId.toString().substring(0, 8)}/parent"
        jdbc.update(
            """
            INSERT INTO pipelines (id, name, display_name, description, owner_id, workspace_id, current_version)
            VALUES (:id, :name, 'P', 'd', :owner, :ws, NULL)
            """.trimIndent(),
            mapOf("id" to parentId, "name" to parentName, "owner" to actor, "ws" to WORKSPACE),
        )
        val body =
            """
            {"schema_version":1,"name":"$parentName","display_name":"P","description":"pin",
             "parameters":{},"settings":{"tempdb":{"engine":"H2"}},
             "nodes":[{"id":"child","type":"PIPELINE","pipeline":{"name":"$childName","version":$version},"depends_on":[]}]}
            """.trimIndent()
        jdbc.update(
            """
            INSERT INTO pipeline_versions (pipeline_id, version, body_json, body_hash, status, created_by, updated_by, updated_at)
            VALUES (:id, 1, CAST(:body AS jsonb),
                    encode(sha256(convert_to(CAST(:body AS jsonb)::text, 'UTF8')), 'hex'),
                    'DRAFT', :owner, :owner, NOW())
            """.trimIndent(),
            mapOf("id" to parentId, "body" to body, "owner" to actor),
        )
    }

    private fun pipelineBody(
        name: String,
        version: Int,
    ): String =
        """
        {"schema_version":1,"name":"$name","display_name":"M","description":"v$version",
         "parameters":{},"settings":{"tempdb":{"engine":"H2"}},
         "nodes":[{"id":"n1","type":"DQL","source":"pg","template":{"id":"test/lifecycle.sql","version":1},
                   "depends_on":[]}]}
        """.trimIndent()

    private fun serviceFor(authoring: Boolean): PipelineService {
        val guard = AuthoringGuard(authoring)
        val validator =
            PipelineValidator(
                DatasourceRegistry { name -> if (name == "pg") DatasourceFacts(co.datapipelines.typesystem.Dialect.H2) else null },
                PermissiveTemplates,
                PipelineResolver { _, _, _ -> null },
                5,
            )
        return PipelineService(
            pipelines = repository,
            validator = validator,
            drafts = PipelineDraftService(repository, guard),
            releases =
                PipelineReleaseService(
                    repository,
                    TemplateVersionStatuses { _, _, _ -> PipelineVersionStatus.RELEASED },
                    validator,
                    guard,
                ),
            authoring = guard,
            draftTemplates = NoExclusiveDraftTemplates,
        )
    }

    private companion object {
        const val SPEC_PATH = "docs/versioning.md"

        /**
         * The gate's default: 2000 sequences per posture (versioning §13). Override with
         * `-DversionLifecycle.sequences=N` for a quick local pass; the round's gate ran the
         * default and prints the count and the time.
         */
        const val DEFAULT_SEQUENCES = 2000

        /** In every failure report — reproduction is a re-run, never a guess. */
        const val SEED_BASE = 2026_09_08_101L

        val WORKSPACE: UUID = UUID.fromString("defa0000-0000-0000-0000-000000000001")

        val PermissiveTemplates =
            object : TemplateDryRenderer {
                override fun lookup(
                    workspaceId: UUID,
                    ref: TemplateRef,
                ): TemplateLookup = TemplateLookup.Found(dialect = co.datapipelines.typesystem.Dialect.H2, type = TemplateType.SQL)

                override fun dryRender(
                    workspaceId: UUID,
                    ref: TemplateRef,
                    context: Map<String, Any?>,
                ): DryRenderOutcome = DryRenderOutcome.Success

                override fun interpolatedParameters(
                    workspaceId: UUID,
                    ref: TemplateRef,
                    declared: Set<String>,
                    guarded: Set<String>,
                ): List<String> = emptyList()

                override fun boundParameters(
                    workspaceId: UUID,
                    ref: TemplateRef,
                ): List<String> = emptyList()
            }

        val NoExclusiveDraftTemplates =
            object : ExclusiveDraftTemplates {
                override fun exclusiveIds(
                    workspaceId: UUID,
                    pipelineId: UUID,
                ) = emptyList<String>()

                override fun purge(
                    workspaceId: UUID,
                    templateId: String,
                ) = Unit
            }
    }
}

// -------------------------------------------------------------------------------------------------
// The reference model — §3 as a pure function. The whole test is the claim that the real
// services cannot disagree with this.
// -------------------------------------------------------------------------------------------------

/** §3's rules over [VersionLifecycleModelTest.Ev]'s event set. */
internal class RefModel(
    private val developmentPosture: Boolean,
) {
    data class State(
        val versions: LinkedHashMap<Int, Char>,
        val pointer: Int?,
        val gone: Boolean,
        /** Versions exact-pinned by a LIVE parent version (graph rule 1's evidence). */
        val pinned: Set<Int> = emptySet(),
    ) {
        override fun equals(other: Any?): Boolean =
            other is State &&
                other.versions == versions &&
                other.pointer == pointer &&
                other.gone == gone &&
                other.pinned == pinned

        override fun hashCode(): Int = versions.hashCode() * 31 + (pointer ?: 0) * 7 + pinned.hashCode() + if (gone) 1 else 0

        override fun toString(): String =
            (if (gone) "gone" else versions.entries.joinToString(" ") { "${it.key}${it.value}" } + " cur=" + (pointer?.toString() ?: "∅")) +
                (if (pinned.isEmpty()) "" else " pinned=$pinned")
    }

    fun initial(
        versions: Map<Int, Char>,
        pointer: Int?,
    ): State = State(LinkedHashMap(versions), pointer, gone = versions.isEmpty())

    /** `(state, event) → (expected outcome code, next state)`. */
    fun step(
        state: State,
        ev: VersionLifecycleModelTest.Ev,
    ): Pair<String?, State> {
        if (state.gone) return null to state

        fun requireAuthoring(): String? =
            if (developmentPosture) null else PipelineErrorCodes.Versioning.AUTHORING_DISABLED

        return when (ev) {
            VersionLifecycleModelTest.Ev.Noop -> null to state
            VersionLifecycleModelTest.Ev.Release -> {
                val refused = requireAuthoring()
                if (refused != null) {
                    refused to state
                } else {
                    val draft = state.versions.entries.singleOrNull { it.value == 'D' }
                    if (draft == null) {
                        PipelineErrorCodes.Versioning.NOT_DRAFT to state
                    } else {
                        val next = LinkedHashMap(state.versions)
                        next[draft.key] = 'R'
                        null to State(next, draft.key, false, state.pinned)
                    }
                }
            }
            is VersionLifecycleModelTest.Ev.Discard -> {
                val refused = requireAuthoring()
                when {
                    refused != null -> refused to state
                    ev.version !in state.versions -> PipelineErrorCodes.Execution.NOT_FOUND to state
                    state.versions[ev.version] != 'R' -> PipelineErrorCodes.Versioning.NOT_RELEASED to state
                    // Graph rule 1: a live parent's exact pin refuses the discard (the
                    // service's statement guard and this model rule are the same rule).
                    ev.version in state.pinned -> PipelineErrorCodes.Versioning.PINNED to state
                    else -> {
                        val next = LinkedHashMap(state.versions)
                        next[ev.version] = 'X'
                        val pointer =
                            if (state.pointer == ev.version) {
                                highestEligible(next.filterKeys { it != ev.version })
                            } else {
                                state.pointer
                            }
                        null to State(next, pointer, false, state.pinned)
                    }
                }
            }
            is VersionLifecycleModelTest.Ev.Restore -> {
                val refused = requireAuthoring()
                when {
                    refused != null -> refused to state
                    ev.version !in state.versions -> PipelineErrorCodes.Execution.NOT_FOUND to state
                    state.versions[ev.version] != 'X' -> PipelineErrorCodes.Versioning.NOT_DISCARDED to state
                    else -> {
                        val next = LinkedHashMap(state.versions)
                        next[ev.version] = 'R'
                        // D60: current = x only if x > current or current is NULL.
                        val pointer = if (state.pointer == null || ev.version > state.pointer) ev.version else state.pointer
                        null to State(next, pointer, false, state.pinned)
                    }
                }
            }
            is VersionLifecycleModelTest.Ev.Purge -> {
                val refused = requireAuthoring()
                when {
                    refused != null -> refused to state
                    ev.version !in state.versions -> PipelineErrorCodes.Execution.NOT_FOUND to state
                    state.versions[ev.version] != 'D' -> PipelineErrorCodes.Versioning.LAST_RELEASE to state
                    else -> {
                        val next = LinkedHashMap(state.versions)
                        next.remove(ev.version)
                        if (next.isEmpty()) {
                            null to State(next, null, gone = true) // the sole-draft entity purge (D57)
                        } else {
                            val pointer = if (state.pointer == ev.version) highestEligible(next) else state.pointer
                            null to State(next, pointer, false, state.pinned)
                        }
                    }
                }
            }
            VersionLifecycleModelTest.Ev.PurgeEntity -> {
                val refused = requireAuthoring()
                when {
                    refused != null -> refused to state
                    state.versions.size != 1 || state.versions.values.single() != 'D' ->
                        PipelineErrorCodes.Versioning.LAST_RELEASE to state
                    else -> null to State(LinkedHashMap(), null, gone = true, state.pinned)
                }
            }
            is VersionLifecycleModelTest.Ev.Switch ->
                when {
                    ev.version !in state.versions -> PipelineErrorCodes.Execution.NOT_FOUND to state
                    !eligible(state.versions[ev.version]) -> PipelineErrorCodes.Versioning.NOT_ELIGIBLE to state
                    else -> null to State(LinkedHashMap(state.versions), ev.version, false, state.pinned)
                }
            VersionLifecycleModelTest.Ev.Import -> {
                // Version-less import: allocation max+1, pointer set only when NULL (D60).
                val nextNumber = (state.versions.keys.maxOrNull() ?: 0) + 1
                val next = LinkedHashMap(state.versions)
                next[nextNumber] = 'R'
                null to State(next, state.pointer ?: nextNumber, false, state.pinned)
            }
            // Environment events, not lifecycle verbs — the state they touch is asserted by
            // the invariants (pin targets stay live; purge clears executions).
            is VersionLifecycleModelTest.Ev.AddPin -> {
                // The generator pins only currently-RELEASED versions; the pin persists until
                // its parent goes (nothing in a sequence touches parents).
                null to State(LinkedHashMap(state.versions), state.pointer, state.gone, state.pinned + ev.version)
            }
            is VersionLifecycleModelTest.Ev.AddExecution -> null to state
        }
    }

    private fun eligible(status: Char?): Boolean = status == 'R' || (developmentPosture && status == 'D')

    private fun highestEligible(versions: Map<Int, Char>): Int? = versions.filterValues { eligible(it) }.keys.maxOrNull()
}
