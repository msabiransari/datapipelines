package co.datapipelines.web.pipelines

import co.datapipelines.pipeline.PipelineDeserializer
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineResolver
import co.datapipelines.pipeline.ResolvedPipeline

/**
 * The repository-backed [PipelineResolver] (design 2026-08-13-pipeline-node-type §3, D5) — the
 * save-time composition validator's view of the pipeline registry.
 *
 * A soft-deleted pipeline still **resolves**, flagged [ResolvedPipeline.deleted] (D7, mirroring
 * templates): existing pinned references keep working, while §12.9's
 * `pipeline_reference_deleted` blocks the NEW one. That is why the read goes through
 * [PipelineRepository.findByNameIncludingDeleted] rather than the live-only [PipelineRepository.findByName].
 *
 * A stored body passed validation when it was written, so [PipelineDeserializer.readOrThrow]
 * cannot fail here for a body the registry itself stored.
 */
fun repositoryPipelineResolver(
    repository: PipelineRepository,
    deserializer: PipelineDeserializer = PipelineDeserializer(),
): PipelineResolver =
    PipelineResolver { name, version ->
        val record = repository.findByNameIncludingDeleted(name) ?: return@PipelineResolver null
        val body = repository.findVersionBody(record.id, version) ?: return@PipelineResolver null
        ResolvedPipeline(pipeline = deserializer.readOrThrow(body), deleted = record.isDeleted)
    }
