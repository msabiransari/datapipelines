package co.datapipelines.web.pipelines

import co.datapipelines.pipeline.PipelineDeserializer
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineResolver
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ResolvedPipeline

/**
 * The repository-backed [PipelineResolver] (design 2026-08-13-pipeline-node-type §3, D5) — the
 * save-time composition validator's view of the pipeline registry.
 *
 * A pipeline whose entity is DISCARDED still **resolves**, flagged
 * [ResolvedPipeline.entityDiscarded] (D7, mirroring templates): existing pinned references
 * keep working, while §12.9's `pipeline_reference_deleted` blocks the NEW one. That is why
 * the read goes through [PipelineRepository.findByNameAnyStatus] rather than the live-only
 * [PipelineRepository.findByName] — and why the status flag is the §3.2 DERIVATION
 * (`hasLiveVersion`), not a column: V19 retired `is_deleted` and nothing stores the
 * entity's status.
 *
 * A stored body passed validation when it was written, so [PipelineDeserializer.readOrThrow]
 * cannot fail here for a body the registry itself stored.
 */
fun repositoryPipelineResolver(
    repository: PipelineRepository,
    deserializer: PipelineDeserializer = PipelineDeserializer(),
): PipelineResolver =
    PipelineResolver { workspaceId, name, version ->
        val record = repository.findByNameAnyStatus(workspaceId, name) ?: return@PipelineResolver null
        // Detail first, and fail CLOSED on a missing detail: a silent RELEASED default would
        // be a fail-open hole in D58's guard. Body and detail are the same key, so both null
        // or both present in practice — the order is about which absence answers first.
        val detail =
            repository.findVersionDetail(workspaceId, record.id, version) ?: return@PipelineResolver null
        val body = repository.findVersionBody(workspaceId, record.id, version) ?: return@PipelineResolver null
        ResolvedPipeline(
            pipeline = deserializer.readOrThrow(body),
            entityDiscarded = !repository.hasLiveVersion(workspaceId, record.id),
            // D58: the pinned version's own status — a pin must name a RELEASED child.
            versionStatus = detail.status,
        )
    }
