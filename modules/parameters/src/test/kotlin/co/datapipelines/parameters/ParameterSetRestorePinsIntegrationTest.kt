package co.datapipelines.parameters

import co.datapipelines.parameters.ParametersTestDb.AUTHOR
import co.datapipelines.parameters.ParametersTestDb.OTHER_WORKSPACE
import co.datapipelines.parameters.ParametersTestDb.WORKSPACE
import co.datapipelines.pipeline.CreateLifecycle
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.TemplateType
import co.datapipelines.templates.TemplateDraft
import co.datapipelines.templates.TemplateJson
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * #517 round three / #719 — a parameter-set RESTORE rechecks the restored body's selector-template pins under the
 * workspace writer guard (release-dependencies record §12.1 "Restore DISCARDED", RULED 2026-10-25): a restore lands the
 * version RELEASED without a human's consent, and a DISCARDED version protects nothing — the template version it pins
 * may have been discarded or purged meanwhile. A missing or DISCARDED pin refuses with the family's existing validation
 * code (400, the D7 precedent); a present-but-DRAFT pin refuses `parameter.release.dependency_not_released` (409, the
 * import-landing precedent) in the shared blockers shape. Only dependency failures block: a rule that tightened since is
 * not a dangling pin, and the recheck never runs the selector dry run.
 *
 * Real rows throughout (the [ParametersHarness]: the set repository and the template tables); every refusal asserts the
 * version is still DISCARDED, the pointer unchanged and the stored body untouched.
 */
class ParameterSetRestorePinsIntegrationTest {
    private lateinit var h: ParametersHarness

    @BeforeEach
    fun reset() {
        ParametersTestDb.reset()
        h = ParametersHarness()
    }

    private val templateName = "acme/sales/states_of_country.sql"

    /** The pinned selector template, RELEASED, and a set pinning it at parameters[1] — released, then DISCARDED. */
    private fun discardedSet(): Pair<UUID, TemplateRef> {
        val pin = h.template(templateName, "SELECT :country AS value, :country AS display_value")
        val created =
            h.create(
                h.document(
                    ParameterSetFixtures.setJson(
                        ParameterSetFixtures.countryJson(),
                        ParameterSetFixtures.stateJson().replace("\"version\": 3", "\"version\": ${pin.version}"),
                    ),
                ),
            )
        h.service.release(WORKSPACE, created.record.id, created.detail.bodyHash, AUTHOR)
        h.service.discardVersion(WORKSPACE, created.record.id, 1, AUTHOR)
        return created.record.id to pin
    }

    private data class Stored(
        val status: PipelineVersionStatus,
        val current: Int?,
        val body: String,
        val discardedAt: Any?,
    )

    private fun stored(id: UUID): Stored {
        val row =
            h.jdbc.queryForMap(
                "SELECT v.status, s.current_version, v.body_json::text AS body, v.discarded_at FROM parameter_set_versions v " +
                    "JOIN parameter_sets s ON s.id = v.parameter_set_id WHERE v.parameter_set_id = :id AND v.version = 1",
                mapOf("id" to id),
            )
        return Stored(
            PipelineVersionStatus.valueOf(row.getValue("status").toString()),
            row["current_version"] as Int?,
            row.getValue("body").toString(),
            row["discarded_at"],
        )
    }

    /** The refusal, with the proof that nothing moved: status, pointer, body and discard stamp all as before. */
    private fun refusedWithoutWrite(id: UUID): DatapipelinesException {
        val before = stored(id)
        val error = shouldThrow<DatapipelinesException> { h.service.restoreVersion(WORKSPACE, id, 1) }
        withClue("a refused restore writes nothing") { stored(id) shouldBe before }
        before.status shouldBe PipelineVersionStatus.DISCARDED
        return error
    }

    private fun failureDetails(error: DatapipelinesException): List<Map<*, *>> =
        (error.details["failures"] as List<*>).map { (it as Map<*, *>)["details"] as Map<*, *> }

    private fun discardTemplate() {
        h.jdbc.update(
            "UPDATE template_versions SET status = 'DISCARDED', discarded_at = NOW(), discarded_by = :author",
            mapOf("author" to AUTHOR),
        )
    }

    @Test
    fun `control - a discarded set whose selector template is alive restores and the pointer comes back`() {
        val (id, _) = discardedSet()

        val moved = h.service.restoreVersion(WORKSPACE, id, 1)

        moved.pointer shouldBe PointerMove(before = null, after = 1)
        stored(id).status shouldBe PipelineVersionStatus.RELEASED
    }

    @Test
    fun `a set whose pinned template version was discarded meanwhile is refused template_version_not_found`() {
        val (id, pin) = discardedSet()
        discardTemplate()

        val error = refusedWithoutWrite(id)

        error.shouldBeInstanceOf<ParameterSetValidationException>()
        error.code shouldBe ParameterErrorCodes.TEMPLATE_VERSION_NOT_FOUND
        error.result.failures.map { it.path } shouldContainExactly listOf("parameters[1].source.template")
        failureDetails(error) shouldContainExactly
            listOf(mapOf("template_id" to pin.id, "template_version" to pin.version, "reason" to "discarded"))
    }

    @Test
    fun `a set whose template vanished entirely is refused template_not_found`() {
        val (id, _) = discardedSet()
        h.jdbc.update("DELETE FROM template_versions", emptyMap<String, Any>())
        h.jdbc.update("DELETE FROM templates", emptyMap<String, Any>())

        val error = refusedWithoutWrite(id)

        error.code shouldBe ParameterErrorCodes.TEMPLATE_NOT_FOUND
    }

    // ---------------------------------------------------------------- #737 a template the entity row knows without version 1

    private val v1Less = "acme/sales/imported_states.sql"

    /**
     * The §9.2 preserved-number import of [id] at [version], through the repository verbs `TemplateImportService` lands it
     * with: `importTemplateVersion` onto an absent entity, `insertReleasedVersion` beside the versions it already holds.
     * The import and purge SERVICES live in `web`, out of this harness's reach; `TemplateVersionProbeIntegrationTest`
     * (iii) and (iv) prove the shipped verbs leave exactly these rows.
     */
    private fun importPreserved(
        id: String,
        version: Int,
    ) {
        val draft =
            TemplateDraft(
                id = id,
                type = TemplateType.SQL,
                dialect = Dialect.POSTGRES,
                displayName = id,
                description = "",
                body = "SELECT :country AS value, :country AS display_value",
            )
        val hash =
            h.templates.computeBodyHash(draft.engine, draft.dialect?.wire, draft.isLibrary, TemplateJson.writeImports(draft.imports), draft.body)
        if (h.templates.existsId(WORKSPACE, id)) {
            checkNotNull(h.templates.insertReleasedVersion(WORKSPACE, id, draft, version, hash, null, AUTHOR))
        } else {
            h.templates.importTemplateVersion(WORKSPACE, draft, version, hash, null, AUTHOR)
        }
    }

    /**
     * A released-then-DISCARDED set whose parameters[1] pin is rewritten BY SQL to [pin]: no shipped verb leaves a
     * released body pinning a version the registry lacks (a release requires RELEASED pins, a RELEASED version is never
     * purged), so the dangling state the recheck defends against is planted — the DRAFT case's precedent below.
     */
    private fun discardedSetPinning(pin: TemplateRef): UUID {
        val (id, _) = discardedSet()
        h.jdbc.update(
            "UPDATE parameter_set_versions SET body_json = jsonb_set(body_json, '{parameters,1,source,template}', " +
                "jsonb_build_object('id', :pin::text, 'version', :version::int)) WHERE parameter_set_id = :id AND version = 1",
            mapOf("pin" to pin.id, "version" to pin.version, "id" to id),
        )
        return id
    }

    private fun shapeIsV1Less() {
        withClue("the shape under test: the entity is registered and holds no version 1") {
            h.templates.existsId(WORKSPACE, v1Less) shouldBe true
            h.statuses.statusOf(WORKSPACE, v1Less, 1) shouldBe null
        }
    }

    private fun refusedMissingVersion(pin: TemplateRef) {
        val error = refusedWithoutWrite(discardedSetPinning(pin))

        error.code shouldBe ParameterErrorCodes.TEMPLATE_VERSION_NOT_FOUND
        error.shouldBeInstanceOf<ParameterSetValidationException>()
        error.result.failures.map { it.path } shouldContainExactly listOf("parameters[1].source.template")
        failureDetails(error) shouldContainExactly listOf(mapOf("template_id" to pin.id, "template_version" to pin.version))
    }

    @Test
    fun `a pin on a missing version of a template imported without version 1 is template_version_not_found`() {
        importPreserved(v1Less, version = 3)
        shapeIsV1Less()

        refusedMissingVersion(TemplateRef(v1Less, 2))
    }

    @Test
    fun `the same answer for the template a draft purge left without version 1`() {
        h.template(v1Less, "SELECT :country AS value, :country AS display_value", CreateLifecycle.DRAFT)
        importPreserved(v1Less, version = 2)
        h.templates.purgeDraft(WORKSPACE, v1Less, expectedHash = null, draftEligible = true) shouldBe true
        shapeIsV1Less()

        refusedMissingVersion(TemplateRef(v1Less, 5))
    }

    @Test
    fun `a pin on version 1 itself of a v1-less template is template_version_not_found`() {
        importPreserved(v1Less, version = 3)
        shapeIsV1Less()

        refusedMissingVersion(TemplateRef(v1Less, 1))
    }

    @Test
    fun `a v1-less template whose every version is DISCARDED is still registered - a missing pin is a missing version`() {
        importPreserved(v1Less, version = 3)
        discardTemplate()
        shapeIsV1Less()

        refusedMissingVersion(TemplateRef(v1Less, 2))
    }

    @Test
    fun `control - a template whose every version is DISCARDED, version 1 included, answers a missing pin the same way`() {
        h.template(v1Less, "SELECT :country AS value, :country AS display_value")
        discardTemplate()

        refusedMissingVersion(TemplateRef(v1Less, 2))
    }

    @Test
    fun `control - a template id that is not registered is template_not_found`() {
        val error = refusedWithoutWrite(discardedSetPinning(TemplateRef(v1Less, 2)))

        error.code shouldBe ParameterErrorCodes.TEMPLATE_NOT_FOUND
        failureDetails(error) shouldContainExactly listOf(mapOf("template_id" to v1Less, "template_version" to 2))
    }

    @Test
    fun `control - the same id registered in ANOTHER workspace only is template_not_found here`() {
        // Version 1 RELEASED there: an unscoped probe — at version 1 or at the entity — would call the id known.
        h.template(v1Less, "SELECT :country AS value, :country AS display_value", workspaceId = OTHER_WORKSPACE)

        withClue("another workspace's registry must not make the id 'known' (workspaces design §3)") {
            refusedWithoutWrite(discardedSetPinning(TemplateRef(v1Less, 2))).code shouldBe ParameterErrorCodes.TEMPLATE_NOT_FOUND
        }
    }

    @Test
    fun `control - pinning the version the v1-less template does hold restores`() {
        importPreserved(v1Less, version = 3)
        val id = discardedSetPinning(TemplateRef(v1Less, 3))

        h.service.restoreVersion(WORKSPACE, id, 1).pointer shouldBe PointerMove(before = null, after = 1)
        stored(id).status shouldBe PipelineVersionStatus.RELEASED
    }

    @Test
    fun `a rule that tightened since is not a dangling pin - an invisible datasource does not block the restore`() {
        val (id, _) = discardedSet()
        // The datasource the selector names no longer resolves: the save rules would refuse the body today
        // (`datasource_not_found`), but the pin itself is alive — only dependency failures block a restore.
        val tightened = ParametersHarness(datasources = FakeDatasources(emptyMap()))

        tightened.service
            .restoreVersion(WORKSPACE, id, 1)
            .pointer shouldBe PointerMove(before = null, after = 1)
    }

    @Test
    fun `a version that is not discarded keeps the lifecycle's own refusal - the recheck does not answer for it`() {
        val (id, _) = discardedSet()
        h.service.restoreVersion(WORKSPACE, id, 1)
        discardTemplate()

        shouldThrow<DatapipelinesException> { h.service.restoreVersion(WORKSPACE, id, 1) }
            .code shouldBe ParameterErrorCodes.VERSION_NOT_DISCARDED
        shouldThrow<DatapipelinesException> { h.service.restoreVersion(WORKSPACE, UUID.randomUUID(), 1) }
            .code shouldBe ParameterErrorCodes.NOT_FOUND
    }

    @Test
    fun `defensive - a pin present but DRAFT (state planted by SQL) refuses dependency_not_released in the shared shape`() {
        val (id, _) = discardedSet()
        // Unreachable for a once-released version today (a RELEASED version is never purged, names are unique forever):
        // the stored body is rewritten BY SQL to pin a DRAFT template version, the state this branch defends against.
        val draft = h.template("acme/sales/draft_states.sql", "SELECT :country AS value, :country AS display_value", CreateLifecycle.DRAFT)
        h.jdbc.update(
            "UPDATE parameter_set_versions " +
                "SET body_json = jsonb_set(body_json, '{parameters,1,source,template,id}', to_jsonb(:pin::text)) " +
                "WHERE parameter_set_id = :id AND version = 1",
            mapOf("pin" to draft.id, "id" to id),
        )
        val name = checkNotNull(h.repository.findRecord(WORKSPACE, id)).name

        val error = refusedWithoutWrite(id)

        error.code shouldBe ParameterErrorCodes.RELEASE_DEPENDENCY_NOT_RELEASED
        error.details shouldBe
            mapOf(
                "root" to mapOf("node_id" to "n0", "family" to "parameter_set", "name" to name, "version" to 1),
                "blockers" to
                    listOf(
                        mapOf(
                            "node_id" to "n1",
                            "family" to "template",
                            "name" to draft.id,
                            "version" to draft.version,
                            "status" to "DRAFT",
                            "reason" to "not_released",
                            "paths" to listOf("parameters[1].source.template"),
                        ),
                    ),
                "blockers_truncated" to false,
            )
    }

    @Test
    fun `the recheck is metadata only - no selector render and no customer probe while the guard is held`() {
        val (id, _) = discardedSet()
        h.probe.renders.clear()
        h.probe.probes.clear()

        h.service.restoreVersion(WORKSPACE, id, 1)
        discardTemplate()
        h.service.discardVersion(WORKSPACE, id, 1, AUTHOR)
        shouldThrow<DatapipelinesException> { h.service.restoreVersion(WORKSPACE, id, 1) }

        withClue("neither the passing nor the refusing restore touches the selector probe") {
            h.probe.renders shouldBe emptyList()
            h.probe.probes shouldBe emptyList()
        }
    }
}
