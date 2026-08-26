package co.datapipelines.templates

import co.datapipelines.pipeline.PipelineErrorCodes

/**
 * Resolves a template's import closure and enforces the §6.4 import rules (templates.md).
 *
 * Every rule here is a save-time check (D2): an alias collision, a missing or non-library
 * import, a cycle, or a closure deeper than [MAX_IMPORT_DEPTH] is rejected before the row is
 * written, so a render never has to cope with a broken graph.
 *
 * The traversal is over **exact `{id, version}` keys** (templates.md §6.4: no ranges, no
 * "latest"). A cycle is therefore a repeated key on the current path, and two different
 * versions of the same library on one path are *not* a cycle — that is a legal DAG, exactly
 * as version pinning intends.
 */
class LibraryResolver(
    private val registryFor: (java.util.UUID) -> TemplateRegistry,
) {
    /**
     * Adds every §6.4 import failure of [imports] to [collector]. Imports resolve within
     * [workspaceId] (design 2026-08-16-workspaces §3: cross-workspace references do not exist).
     *
     * [imports] is the array under validation (a draft's, or a library's during recursion);
     * duplicate-alias is checked on this array only, because each library's own array was
     * validated at its own save.
     */
    fun validate(
        workspaceId: java.util.UUID,
        imports: List<TemplateImport>,
        collector: MutableList<TemplateValidationFailure>,
    ) {
        addUnsafeEntryFailures(imports, collector)
        addDuplicateAliasFailures(imports, collector)
        walk(registryFor(workspaceId), imports, depth = 1, path = emptySet(), state = WalkState(), collector = collector)
    }

    /**
     * Rejects an `imports` entry whose `id` or `alias` cannot be safely interpolated into the
     * engine-synthesized `<#import "{id}@{version}" as {alias}>` prologue (templates.md §6.3).
     *
     * The prologue is template **source** built from author-controlled JSON, and the body scan
     * never sees it — so without this check the `imports` array is an unscanned path into the
     * engine (see [IMPORT_ALIAS] for the full argument). [RegistryTemplateLoader] repeats the
     * check at render time, so the two layers hold independently exactly as §4.2/§4.3 require
     * of every other construct.
     */
    private fun addUnsafeEntryFailures(
        imports: List<TemplateImport>,
        collector: MutableList<TemplateValidationFailure>,
    ) {
        imports
            .withIndex()
            .filterNot { (_, imp) -> imp.isSafeToSynthesize() }
            .forEach { (index, _) ->
                // templates.md §6.3 (frozen): "the refusal message never echoes the offending
                // value back into logs". The positional index is the locator an author needs to
                // find the entry; the id and alias are the attacker's own strings and stay out of
                // the message, the details map and therefore the log line — exactly as
                // RegistryTemplateLoader's refusal already does on the read path.
                collector +=
                    TemplateValidationFailure(
                        code = PipelineErrorCodes.Template.DANGEROUS_CONSTRUCT,
                        message =
                            "Import entry at index $index is not a plain {id, version, alias} triple " +
                                "and cannot be bound to a namespace.",
                        details = mapOf("construct" to "imports[]", "index" to index),
                    )
            }
    }

    private fun addDuplicateAliasFailures(
        imports: List<TemplateImport>,
        collector: MutableList<TemplateValidationFailure>,
    ) {
        imports
            .groupBy { it.alias }
            .filterValues { it.size > 1 }
            .keys
            .forEach { alias ->
                collector +=
                    TemplateValidationFailure(
                        code = PipelineErrorCodes.Template.DUPLICATE_ALIAS,
                        message = "Import alias '${alias.truncateForError()}' is used by more than one import.",
                        details = mapOf("alias" to alias.truncateForError()),
                    )
            }
    }

    /**
     * Mutable traversal state carried across the **whole** walk, not down one branch.
     *
     * [expanded] is what makes the traversal a DAG walk rather than a tree walk. Without it a
     * diamond is re-expanded once per path that reaches it, so a chain of libraries each
     * importing the previous one under 10 aliases costs 10^9 steps — one save taking minutes,
     * from a legal-looking `imports` array. Memoizing on `"{key}#{depth}"` rather than on the key
     * alone keeps the depth verdict exact: the same library reached at a different depth carries
     * a different remaining budget, so it is still expanded, and the work stays bounded by
     * distinct-libraries × [MAX_IMPORT_DEPTH].
     *
     * [expansions] is the backstop for a graph shape the memo does not bound, and [failures]
     * keeps one broken graph from producing a response of unbounded size.
     */
    private class WalkState {
        val expanded = mutableSetOf<String>()
        var expansions = 0
        var failures = 0
        var stopped = false
    }

    private fun walk(
        registry: TemplateRegistry,
        imports: List<TemplateImport>,
        depth: Int,
        path: Set<String>,
        state: WalkState,
        collector: MutableList<TemplateValidationFailure>,
    ) {
        // Before the depth guard, on purpose (TPL-API-1): a library that imports nothing is a
        // leaf, and guarding on entry made the effective cap 9 — a legal closure exactly
        // MAX_IMPORT_DEPTH deep was rejected because its deepest library's empty `imports` array
        // was still "walked" at depth MAX_IMPORT_DEPTH + 1.
        if (imports.isEmpty() || state.stopped) return
        if (depth > MAX_IMPORT_DEPTH) {
            report(
                state,
                collector,
                TemplateValidationFailure(
                    code = PipelineErrorCodes.Template.IMPORT_DEPTH_EXCEEDED,
                    message = "Transitive import depth exceeds the cap of $MAX_IMPORT_DEPTH.",
                    details = mapOf("cap" to MAX_IMPORT_DEPTH, "depth" to depth),
                ),
            )
            return
        }
        for (imp in imports) {
            if (state.stopped) return
            visitImport(registry, imp, depth, path, state, collector)
        }
    }

    /** Judges one `imports` entry and, when it resolves to a not-yet-expanded library, walks it. */
    private fun visitImport(
        registry: TemplateRegistry,
        imp: TemplateImport,
        depth: Int,
        path: Set<String>,
        state: WalkState,
        collector: MutableList<TemplateValidationFailure>,
    ) {
        // An entry already refused by addUnsafeEntryFailures is not walked. Two reasons, and the
        // second is why it matters: the traversal would report a *second*, redundant failure for
        // it, and that failure's message echoes the attacker's `id` — undoing, on a different code
        // path, the §6.3 rule that a refusal never reflects the value.
        if (!imp.isSafeToSynthesize()) return
        if (imp.key in path) {
            report(state, collector, importFailure(PipelineErrorCodes.Template.IMPORT_CYCLE, imp, "is part of an import cycle"))
            return
        }
        val resolved = registry.lookup(imp.id, imp.version)
        when {
            resolved == null -> {
                report(state, collector, importFailure(PipelineErrorCodes.Template.IMPORT_NOT_FOUND, imp, "does not exist"))
            }

            !resolved.isLibrary -> {
                report(state, collector, importFailure(PipelineErrorCodes.Template.IMPORT_NOT_LIBRARY, imp, "is not a library"))
            }

            // Expand each (library, depth) pair once — see [WalkState.expanded]. A second route to
            // the same library at the same depth would walk an identical subtree and re-derive
            // identical verdicts; skipping it is what turns an exponential fan-out into a bounded
            // one. The depth is part of the key on purpose: memoizing on the library alone would
            // skip a deeper reach and let a closure over the §6.4 cap validate clean.
            // Already expanded at this depth means an identical subtree, already judged — so the
            // walk only recurses when this (library, depth) pair is new.
            else -> {
                if (state.expanded.add("${imp.key}#$depth") && chargeExpansion(state, collector)) {
                    walk(registry, resolved.imports, depth + 1, path + imp.key, state, collector)
                }
            }
        }
    }

    /** One import-graph failure about [imp], with the key truncated for safe reflection. */
    private fun importFailure(
        code: String,
        imp: TemplateImport,
        problem: String,
    ): TemplateValidationFailure =
        TemplateValidationFailure(
            code = code,
            message = "Imported template '${imp.key.truncateForError()}' $problem.",
            details = mapOf("import" to imp.key.truncateForError()),
        )

    /** Counts one expansion; returns false (having reported and stopped) once the cap is hit. */
    private fun chargeExpansion(
        state: WalkState,
        collector: MutableList<TemplateValidationFailure>,
    ): Boolean {
        if (++state.expansions <= MAX_EXPANSIONS) return true
        report(
            state,
            collector,
            TemplateValidationFailure(
                code = PipelineErrorCodes.Template.IMPORT_DEPTH_EXCEEDED,
                message = "Import graph is too large to validate (over $MAX_EXPANSIONS libraries traversed).",
                details = mapOf("cap" to MAX_IMPORT_DEPTH, "traversed" to state.expansions),
            ),
        )
        state.stopped = true
        return false
    }

    /**
     * Adds [failure], and stops the traversal once [MAX_REPORTED_FAILURES] have accumulated.
     *
     * A single hostile `imports` array can otherwise produce a failure per edge; the response
     * body (and the log line behind it) is bounded here for the same reason every reflected
     * value is truncated.
     */
    private fun report(
        state: WalkState,
        collector: MutableList<TemplateValidationFailure>,
        failure: TemplateValidationFailure,
    ) {
        if (state.stopped) return
        collector += failure
        if (++state.failures >= MAX_REPORTED_FAILURES) state.stopped = true
    }

    companion object {
        /**
         * The transitive import depth cap (templates.md §6.4). A fixed constant, **not** a
         * config key — the spec is explicit that this is not deployment-tunable.
         */
        const val MAX_IMPORT_DEPTH = 10

        /**
         * Total library expansions per validation. Unreachable for any legal graph — the
         * `(library, depth)` memo already bounds the walk at distinct-libraries ×
         * [MAX_IMPORT_DEPTH] — and present only so no graph shape can make one save unbounded.
         */
        const val MAX_EXPANSIONS = 10_000

        /** Import failures reported before the traversal gives up. Bounds the response size. */
        const val MAX_REPORTED_FAILURES = 100
    }
}
