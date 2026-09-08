package co.datapipelines.mcp

import co.datapipelines.typesystem.DatapipelinesException

/**
 * **A new ROOT folder needs the person's say-so** (094 addendum) — on the AGENT surface only.
 *
 * 067 gave pipelines and templates folder paths and 077 made a folder mandatory; the root
 * segment is what says who owns a thing. Until now the tool schemas and the SKILL only
 * *instructed* an agent to list the existing roots and ask before minting a new one — an
 * instruction to a model, which is a hope, not a guarantee. The observable cost of the hope
 * being wrong is a workspace whose tree grows a `reports/`, an `analytics/` and a `metrics/`
 * that all mean the same thing, and there is no rename.
 *
 * So `pipelines_create` and `templates_create` REFUSE a name whose root segment has nothing
 * under it yet, unless the call carries `confirm_new_root: true`. The refusal names the roots
 * that DO exist, so the agent's next move is obvious: reuse one, or go and ask.
 *
 * ## What this rule deliberately is not
 *
 * - **Not a REST or UI rule.** A person choosing a folder in a form has already decided; a
 *   second confirmation there would be a dialog nobody reads. This lives in the two MCP tools.
 * - **Not an update rule.** `pipelines_update` / `templates_update` cannot change a name at all
 *   (there is no rename), so there is no new root for them to mint.
 * - **Not a validation rule in the contract sense.** The names it refuses are perfectly legal;
 *   the codes therefore sit in the `*.validation.*` families for catalogue consistency, but
 *   nothing below the MCP surface consults this object.
 * - **Not a spelling checker.** `finence/` confirmed is `finence/` created. The rule buys ONE
 *   thing: the agent cannot mint a root without the exchange happening.
 *
 * `test/` is always allowed, unconfirmed — it is the round-tripping experiments root
 * ([Template Hierarchy §4.1](../../../../../../../docs/template-hierarchy-design.md)), the one
 * root whose whole purpose is that nobody has to be asked about it.
 */
internal object NewRootConfirmation {
    /** The opt-in argument both create tools accept. */
    const val ARG = "confirm_new_root"

    /** The one root a new name may mint without asking: experiments go here. */
    const val TEST_ROOT = "test"

    /** The sentence both tool schemas carry, verbatim, on the `confirm_new_root` property. */
    const val ARG_DESC =
        "Set true ONLY after a person has agreed to a new top-level folder. A name whose root segment has no " +
            "pipelines or templates under it yet is refused with details.existing_roots listing the roots that do " +
            "exist — reuse one of those, or ask the person first and then pass this. 'test/' never needs it."

    /**
     * Refuses [name] when its root segment is new and the call did not confirm it.
     *
     * @param name the requested name/id. Null (a `templates_create` that lets the server
     *   generate one under `test/`) is allowed: there is no root to mint.
     * @param confirmed the call's `confirm_new_root` argument.
     * @param code the catalogued refusal code for this family.
     * @param existingRoots evaluated ONLY when the rule is about to refuse — the roots query is
     *   one `GROUP BY` per call and the overwhelmingly common case is an existing root, so it is
     *   passed as a thunk rather than run on every create.
     */
    fun require(
        name: String?,
        confirmed: Boolean?,
        code: String,
        existingRoots: () -> List<String>,
    ) {
        val root = rootOf(name) ?: return
        if (root == TEST_ROOT || confirmed == true) return
        val roots = existingRoots()
        if (root in roots) return
        throw DatapipelinesException(
            code,
            "'$root' is not an existing top-level folder in this workspace. Reuse one of the roots below, or ask " +
                "the person whether '$root' should exist and then retry with confirm_new_root: true.",
            mapOf("root" to root, "existing_roots" to roots),
        )
    }

    /**
     * The root segment of [name], or null when there is none to judge.
     *
     * Null for a null/blank name and for a name with NO `/` at all — a bare `daily_settlement`
     * is already refused by the mandatory-folder rule (077) with `details.reason='folder_required'`,
     * and answering the same mistake with two different codes would be worse than either.
     */
    fun rootOf(name: String?): String? {
        val trimmed = name?.trim().orEmpty()
        if (!trimmed.contains('/')) return null
        return trimmed.substringBefore('/').takeIf { it.isNotEmpty() }
    }
}
