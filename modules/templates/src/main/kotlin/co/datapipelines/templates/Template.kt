package co.datapipelines.templates

import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.RetiredFactCitation
import co.datapipelines.pipeline.TemplateType
import co.datapipelines.typesystem.Dialect
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant
import java.util.UUID

/**
 * The public projection of one stored template version (templates.md §3).
 *
 * This is an **outbound** shape: every field, including the server-assigned ones
 * ([version], [createdAt], [createdBy]), is present because a reader is entitled to see what
 * the server stored. Inbound create/update payloads use [TemplateDraft], which deliberately
 * omits those fields (the DTO rule: server-assigned fields are *absent* from inbound shapes,
 * not filtered out after binding).
 *
 * ## No parameter schema (D3)
 *
 * There is no `params_schema` field anywhere in this module. A template declares no parameters
 * of its own; the calling pipeline's `parameters` map (defaults applied) is the render context
 * and the single declaration point (templates.md §2.5). [description] is the only place a
 * template can hint at what it expects, which is why it is required.
 *
 * ## The wire keys are pinned, not inferred
 *
 * templates.md §3.1 is snake_case and §11.1 freezes it, so every field carries an explicit
 * `@JsonProperty` on all three use-site targets. Three independent Jackson behaviours would
 * otherwise rewrite these keys silently: the Java-Beans `^[a-z][A-Z]` rule (`displayName` →
 * `displayname` under a naming strategy), Kotlin's `is`-prefix getter rule (`isLibrary` →
 * bean property `library`), and whatever naming strategy an upstream mapper happens to carry —
 * and the outbound mapper at run time is the *web* module's, not this module's. The
 * annotations are the contract; no naming strategy is relied on anywhere.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class Template(
    @field:JsonProperty("schema_version") @get:JsonProperty("schema_version") @param:JsonProperty("schema_version")
    val schemaVersion: Int = SUPPORTED_SCHEMA_VERSION,
    @field:JsonProperty("id") @get:JsonProperty("id") @param:JsonProperty("id")
    val id: String,
    @field:JsonProperty("version") @get:JsonProperty("version") @param:JsonProperty("version")
    val version: Int,
    @field:JsonProperty("engine") @get:JsonProperty("engine") @param:JsonProperty("engine")
    val engine: String = FREEMARKER_ENGINE,
    /**
     * The template's kind (046, template-hierarchy-design §5.4; enums.md §6A) — `sql` or
     * `html`, fixed at creation and identical on every version (§5.3). Defaulted so the
     * pre-046 constructors keep compiling and a read that does not select the column still
     * renders the value every pre-V8 row carries. `dialect` below is non-null exactly when
     * this is [TemplateType.SQL].
     */
    @field:JsonProperty("type") @get:JsonProperty("type") @param:JsonProperty("type")
    val type: TemplateType = TemplateType.SQL,
    @field:JsonProperty("dialect") @get:JsonProperty("dialect") @param:JsonProperty("dialect")
    val dialect: Dialect?,
    @field:JsonProperty("display_name") @get:JsonProperty("display_name") @param:JsonProperty("display_name")
    val displayName: String,
    @field:JsonProperty("description") @get:JsonProperty("description") @param:JsonProperty("description")
    val description: String,
    @field:JsonProperty("imports") @get:JsonProperty("imports") @param:JsonProperty("imports")
    val imports: List<TemplateImport> = emptyList(),
    @field:JsonProperty("body") @get:JsonProperty("body") @param:JsonProperty("body")
    val body: String,
    @field:JsonProperty("is_library") @get:JsonProperty("is_library") @param:JsonProperty("is_library")
    val isLibrary: Boolean = false,
    @field:JsonProperty("created_at") @get:JsonProperty("created_at") @param:JsonProperty("created_at")
    val createdAt: Instant,
    @field:JsonProperty("created_by") @get:JsonProperty("created_by") @param:JsonProperty("created_by")
    val createdBy: UUID,
    /**
     * The version's lifecycle status (versioning §3.1/§6, since V6) — `RELEASED` on the
     * pre-lifecycle projection; defaulted so existing constructors keep compiling, and so a
     * read that does not select the column still renders an honest value.
     */
    val status: PipelineVersionStatus = PipelineVersionStatus.RELEASED,
    /** The version's SHA-256 content hash (versioning §4) — the mutation precondition token. */
    @field:JsonProperty("body_hash") @get:JsonProperty("body_hash") @param:JsonProperty("body_hash")
    val bodyHash: String = "",
    /**
     * The three transform blocks (7b, transform-nodes design §2.2) — present exactly when
     * [type] is a transform type; null on `sql`/`html`. They are part of the version's content
     * (inside `body_hash`), so the export/import and promotion shapes carry them.
     */
    @field:JsonProperty("contract") @get:JsonProperty("contract") @param:JsonProperty("contract")
    val contract: TransformContract? = null,
    @field:JsonProperty("invariants") @get:JsonProperty("invariants") @param:JsonProperty("invariants")
    val invariants: List<TransformInvariant>? = null,
    @field:JsonProperty("tests") @get:JsonProperty("tests") @param:JsonProperty("tests")
    val tests: List<TransformTestCase>? = null,
    /**
     * The learned facts this version cites as implementing (7e, transform-nodes design §2.3) —
     * fact ids, sorted; `[]` on a transform version that cites none, null on `sql`/`html`
     * (like the blocks above: the field belongs to the transform types). NOT content: outside
     * `body_hash`, stored in `template_implements`, carried by export/import/promotion.
     */
    @field:JsonProperty("implements") @get:JsonProperty("implements") @param:JsonProperty("implements")
    val implements: List<String>? = null,
    /**
     * The cited facts that are RETIRED (7e, §8.2) — computed on read by the projection's own
     * query, never stored; each names its reason and, when superseded, the successor. Omitted
     * from the wire when empty. What [needsReview] is derived from.
     */
    @field:JsonProperty("retired_facts") @get:JsonProperty("retired_facts") @param:JsonProperty("retired_facts")
    @field:JsonInclude(JsonInclude.Include.NON_EMPTY)
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val retiredFacts: List<RetiredFactCitation> = emptyList(),
) {
    /**
     * §8.2 — true when any cited fact is retired: the version is served MARKED, never edited and
     * never re-mapped; clearing it is a deliberate `implements` write. Derived from
     * [retiredFacts] so the flag and its detail cannot disagree, and serialized (never read
     * back — an inbound `needs_review` is ignored like every server-computed field).
     */
    @get:JsonProperty("needs_review")
    val needsReview: Boolean get() = retiredFacts.isNotEmpty()

    companion object {
        /** The only `schema_version` v1 accepts (templates.md §3.2). */
        const val SUPPORTED_SCHEMA_VERSION = 1

        /** The `engine` of an `sql`/`html` template (enums.md §6). */
        const val FREEMARKER_ENGINE = "freemarker"

        /** The `engine` of a transform template (transform-nodes design §2.1: no rendering engine). */
        const val NONE_ENGINE = "none"
    }
}

/**
 * One `template_versions` row — the persistence-facing per-version record (metadata-db §4.9).
 *
 * [TemplateEngine] and [LibraryResolver] resolve imports at an exact `{id, version}` against
 * this shape: it carries the [body], the [imports] array, and the [isLibrary] flag that
 * import validation checks, all pinned to one version.
 *
 * ## Which rows are immutable (templates.md §5.1, since 117/132)
 *
 * A RELEASED or DISCARDED version is never written again: no statement in
 * [TemplateRepository] targets the content fields of a non-DRAFT row, and the entity purge
 * (101) is refused unless the sole version is a DRAFT. The ONE exception is not content: a
 * version's `implements` citations (7e, [TemplateImplementsRepository]) are outside the hash
 * and editable on a RELEASED version — nothing the engine renders or caches reads them. The **DRAFT** is the one mutable key —
 * `templates_update` / `PUT /templates` overwrite it IN PLACE (same id, same version number,
 * new content), and `templates_purge_draft` deletes it. So a [TemplateVersion] is safe to
 * cache by its [key] exactly when [status] is not [PipelineVersionStatus.DRAFT]; a draft
 * must be re-read on every lookup ([RepositoryTemplateRegistry]), and the parsed-template
 * cache keys on [bodyHash] rather than on the key alone ([InterruptibleConfiguration]).
 *
 * [bodyHash] is the row's `body_hash` — the SHA-256 the database computes over the
 * version-owned fields (`engine`, `dialect`, `is_library`, `imports`, `body`), i.e. over
 * everything the effective Freemarker source depends on. Two rows with equal hashes parse to
 * the same tree; a draft overwrite always changes it. [updatedAt] is the draft's last write
 * stamp (null on rows that were never a draft) — what the loader reports as `lastModified`.
 *
 * [type] (046) selects which of the engine's two Freemarker configurations the version
 * renders through ([TemplateEngine]); [dialect] is null exactly when the type is `html`.
 */
data class TemplateVersion(
    val id: String,
    val version: Int,
    val engine: String = Template.FREEMARKER_ENGINE,
    val type: TemplateType = TemplateType.SQL,
    val dialect: Dialect? = null,
    val isLibrary: Boolean,
    val imports: List<TemplateImport>,
    val body: String,
    val createdAt: Instant,
    val createdBy: UUID,
    /** Defaulted RELEASED so the pre-132 constructors keep compiling — and keep their immutable reading. */
    val status: PipelineVersionStatus = PipelineVersionStatus.RELEASED,
    /**
     * The row's content hash (versioning §4). Blank only on a hand-built instance; the engine
     * treats a blank hash as "no identity" and never caches the parsed tree (parses every load).
     */
    val bodyHash: String = "",
    val updatedAt: Instant? = null,
    /**
     * The three transform blocks as stored jsonb text (7b) — non-null exactly when [type] is a
     * transform type. Text, not the bound model: this record serves the engine seam's callers
     * (the test runner, `templates_evaluate`), which bind through [TransformBlocks] exactly
     * once, and the row's own hash was computed over the stored text.
     */
    val contractJson: String? = null,
    val invariantsJson: String? = null,
    val testsJson: String? = null,
) {
    /** The registry lookup key, `"{id}@{version}"`. */
    val key: String get() = "$id@$version"

    /** True for the one mutable status — the row a write can overwrite or delete in place. */
    val isDraft: Boolean get() = status == PipelineVersionStatus.DRAFT

    /** The stamp a loader reports as last-modified: the draft's last write, else the row's creation. */
    val lastModified: Instant get() = updatedAt ?: createdAt
}
