package co.datapipelines.application.templates

import co.datapipelines.datasources.semantics.LearnedFactRepository
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.templates.CitableFacts
import co.datapipelines.templates.ImplementingVersion
import co.datapipelines.templates.TemplateImplementsRepository
import java.util.UUID

/**
 * "Which transforms implement this fact?" — the reverse arrow of the semantic link
 * (transform-nodes design §8.3), as the application layer answers it for the fact surfaces.
 *
 * A port in this layer, not a repository reach, so `SemanticsService` and the listing
 * enrichment stay ignorant of the template store. [NONE] answers nothing — the wiring for a
 * construction without the template store (the bare tool tests).
 */
fun interface FactImplementations {
    /**
     * For each of [factIds], the live template versions of [workspaceId] citing it that
     * [templateLens] admits (a promoter sees RELEASED versions of the admitted names only);
     * facts nobody implements are absent from the map.
     */
    fun implementedBy(
        workspaceId: UUID,
        templateLens: ReadLens,
        factIds: Collection<UUID>,
    ): Map<UUID, List<ImplementingVersion>>

    companion object {
        val NONE = FactImplementations { _, _, _ -> emptyMap() }
    }
}

/**
 * The semantic link's two application-layer questions, over the two stores that hold its
 * halves (lane 7e, transform-nodes design §2.3/§8.3):
 *
 *  - [citable] — the §2.3 citation rule the template write paths ask through the [CitableFacts]
 *    port (a fact the workspace may see that is a WORKSPACE definition/exclusion/preference),
 *    answered by `LearnedFactRepository`'s ONE visibility predicate so the rule lives with the
 *    facts;
 *  - [implementedBy] — the reverse read, over `template_implements`' fact index, with the
 *    reader's template lens applied in its SQL.
 *
 * Stateless over the two repositories; nothing here writes.
 */
class TemplateImplementsService(
    private val facts: LearnedFactRepository,
    private val citations: TemplateImplementsRepository,
) : CitableFacts,
    FactImplementations {
    override fun citable(
        workspaceId: UUID,
        factIds: Set<UUID>,
    ): Set<UUID> = facts.findCitable(factIds, workspaceId)

    override fun implementedBy(
        workspaceId: UUID,
        templateLens: ReadLens,
        factIds: Collection<UUID>,
    ): Map<UUID, List<ImplementingVersion>> = citations.implementedBy(workspaceId, templateLens, factIds)
}
