package co.datapipelines.web.ui

import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Permission
import co.datapipelines.executor.ExecutionRecord
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.ExecutionStatus
import java.util.UUID

/**
 * The runs a UI list shows [principal] (#275): `co.datapipelines.web.api.visibleTo`'s row rule,
 * decided in SQL so a list is cut after visibility — the ONE read behind the search palette's
 * executions group, the pipeline and template explorers' Runs tabs, and (#293) the dashboard's
 * stat tiles.
 *
 * - `execution.read_all` (the workspace admin, the super admin) → every run of the workspace
 *   ([ExecutionRepository.findAll]);
 * - `execution.read` → own runs plus every SCHEDULED run of the workspace
 *   ([ExecutionRepository.findVisible], #9 R3 — rest-api §10.1's "every surface");
 * - neither → own runs only ([ExecutionRepository.findByUser]). These three surfaces are
 *   `pipeline.read` / `template.read` routes, so a promoter reaches them without `execution.read`;
 *   R3's scheduled arm is that row's, exactly as in `visibleTo`, and never the pane's.
 *
 * The executions screen, the dashboard's recent-executions pane and the REST list keep their
 * two-arm forks: their routes declare `execution.read`, so the third arm is unreachable there.
 */
fun ExecutionRepository.listVisibleTo(
    principal: AuthenticatedPrincipal,
    workspaceId: UUID,
    pipelineId: UUID?,
    status: ExecutionStatus?,
    limit: Int,
): List<ExecutionRecord> =
    when {
        principal.holds(Permission.EXECUTION_READ_ALL) -> findAll(workspaceId, pipelineId, status, limit = limit)
        principal.holds(Permission.EXECUTION_READ) -> findVisible(workspaceId, principal.userId, pipelineId, status, limit = limit)
        else -> findByUser(workspaceId, principal.userId, pipelineId, status, limit = limit)
    }
