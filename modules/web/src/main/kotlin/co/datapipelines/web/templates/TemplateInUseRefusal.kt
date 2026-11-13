package co.datapipelines.web.templates

import co.datapipelines.application.lens.LensedView
import co.datapipelines.application.templates.TemplateUsage
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.typesystem.DatapipelinesException

/**
 * The `template.in_use` refusal of every verb that removes a template version (REST and the UI dialogs' POSTs alike) —
 * the ONE filtering owner of its echo (#877 §C.2). The guard decided on the whole workspace ([TemplateUsage.Pins] is
 * unlensed); this builder narrows only what the refusal SAYS, by the caller's [LensedView]: each arm names the pinners
 * its lens admits, and when any pinner is outside the view the details carry the boolean `pins_hidden` — never a hidden
 * name, never a count of what the caller cannot see (the #300 rule the MCP purge tool already applies).
 *
 * The four arms, in the order the refusal always listed them: pipelines (`pinned_by`, always present), parameter sets
 * (`referencing_parameter_sets`), visualizations (`referencing_visualizations`) and — #877 §D — importing templates
 * (`referencing_templates`), each of the last three present only when it names something. A caller whose view admits
 * every pinner gets the message and details the refusal carried before this lane, byte for byte (plus
 * `referencing_templates` when an importer pins).
 */
internal object TemplateInUseRefusal {
    fun of(
        id: String,
        pins: TemplateUsage.Pins,
        view: LensedView,
    ): DatapipelinesException {
        val pipelines = Arm(pins.pipelines.map { it.pipelineName }, view.pipelines)
        val sets = Arm(pins.parameterSets.map { it.setName }, view.parameterSets)
        val visualizations = Arm(pins.visualizations.map { it.name }, view.visualizations)
        val importers = Arm(pins.templates.map { it.importerName }, view.templates)
        val sentences =
            listOfNotNull(
                pipelines.sentence("live pipeline version(s)", total = pins.pipelines.size),
                sets.sentence("parameter set version(s)"),
                visualizations.sentence("visualization version(s)"),
                importers.sentence("template version(s)"),
            )
        val hidden = listOf(pipelines, sets, visualizations, importers).any { it.hidden }
        return DatapipelinesException(
            code = PipelineErrorCodes.Template.IN_USE,
            message = "Version of template '$id' is pinned by ${sentences.joinToString(" and ")}; discard or repoint them first.",
            details =
                buildMap {
                    put("template_id", id)
                    put("pinned_by", pipelines.admitted)
                    if (sets.admitted.isNotEmpty()) put("referencing_parameter_sets", sets.admitted)
                    if (visualizations.admitted.isNotEmpty()) put("referencing_visualizations", visualizations.admitted)
                    if (importers.admitted.isNotEmpty()) put("referencing_templates", importers.admitted)
                    if (hidden) put("pins_hidden", true)
                },
        )
    }

    /** One arm's distinct pinner names, split by what [lens] admits. */
    private class Arm(
        pinners: List<String>,
        lens: ReadLens,
    ) {
        private val names = pinners.distinct()
        val admitted: List<String> = names.filter { lens.admits(it) }
        val hidden: Boolean = admitted.size < names.size

        /**
         * The arm's phrase: with nothing hidden, the refusal's established sentence (cardinality [total], the distinct
         * names by default); with a pinner hidden, NO cardinality at all — the admitted names and the fact that others
         * exist outside the view.
         */
        fun sentence(
            kind: String,
            total: Int = names.size,
        ): String? =
            when {
                names.isEmpty() -> null
                !hidden -> "$total $kind: ${admitted.joinToString(", ")}"
                admitted.isEmpty() -> "$kind outside your view"
                else -> "$kind: ${admitted.joinToString(", ")} and others outside your view"
            }
    }
}
