package co.datapipelines.web.dependencies

import co.datapipelines.application.lens.LensedView
import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.dependencies.Authority
import co.datapipelines.dependencies.Deadline
import co.datapipelines.dependencies.DeadlineExceeded
import co.datapipelines.dependencies.DependencyWalker
import co.datapipelines.dependencies.FamilyKey
import co.datapipelines.dependencies.FamilyRegistry
import co.datapipelines.dependencies.GraphLimits
import co.datapipelines.dependencies.GraphSnapshot
import co.datapipelines.dependencies.MetadataSnapshot
import co.datapipelines.dependencies.Purpose
import co.datapipelines.dependencies.ReadIsolation
import co.datapipelines.dependencies.Resolution
import co.datapipelines.dependencies.ResolveContext
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.web.dependencies.DependenciesRequest.Parsed
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.dao.DataAccessException
import org.springframework.transaction.TransactionException
import org.springframework.transaction.support.TransactionOperations
import java.time.Clock

/**
 * The tree read behind the eight `GET /api/v1/dependencies/<family>` routes (#875 G4; record §§11–12, erratum (8)).
 *
 * The handler's order, after `ScopeInterceptor` (key-kind confinement, then the route's `@RequiredScope`):
 * 1. a session principal, else `auth.session_required` (defensive — the interceptor already refused keys);
 * 2. the request parsed against the family's transport row (§A) — a refusal is `dependencies.request.invalid`;
 * 3. the root lens, only for a family whose row has one AND a lensed principal: an unreachable promotion view is
 *    `navigation.view.unavailable`; a root the lens does not admit is the family's native not-found. The datasource row
 *    has no lens, so no `LensedView` is ever obtained for it;
 * 4. ONE walk inside one READ COMMITTED read-only metadata transaction ([transactions]), speculative reads fenced by
 *    [readIsolation], the context carrying one [Deadline] with the statement limit on it; no lens enters the walk;
 * 5. a root that did not resolve, or (lensed) a resolved root the family's native lensed version read hides, is the
 *    native not-found — byte-identical for absent and hidden (§12, no oracle);
 * 6. the projection, and the payload held to `max-wire-bytes` (over it: `dependencies.response.too_large`, never a
 *    truncated tree).
 *
 * A deadline that expires, or any provider read that fails (a `DataAccessException`, or the metadata transaction
 * itself failing — `TransactionException`), is `dependencies.metadata.unavailable` — no partial tree. Anything else is a
 * defect and keeps the handler's 500 with its stack: a bug is never dressed as a retryable outage.
 */
class DependencyTreeService(
    registry: FamilyRegistry,
    private val transport: DependencyFamilyTransport,
    private val limits: GraphLimits,
    private val transactions: TransactionOperations,
    private val readIsolation: ReadIsolation,
    private val lens: PromoterLens,
    private val mapper: ObjectMapper,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val walker = DependencyWalker(registry, limits, clock)

    /** The projected tree of [family]'s root named by [rawQuery], read for [principal]. */
    fun tree(
        principal: AuthenticatedPrincipal,
        family: FamilyKey,
        rawQuery: String?,
    ): DependencyTreeData {
        requireSession(principal)
        val row = transport.row(family)
        val request = parse(row, rawQuery)
        val lensed = rootLensOf(principal, row)
        // A root the lens refuses by its request is never walked; one it refuses once resolved is walked and discarded.
        val graph = if (lensed == null || lensed.check.admitsRequest(principal, lensed.view, request)) walk(principal, request) else null
        val root = graph?.node(graph.roots.single())
        val identity = root?.identity?.takeIf { root.resolution == Resolution.RESOLVED }
        val visible = identity != null && (lensed == null || lensed.check.admitsResolved(principal, lensed.view, request, identity))
        if (graph == null || !visible) throw notFound(row)
        return bounded(DependencyTreeProjection.project(graph))
    }

    /** The defensive session check (`TreeController`'s): the interceptor already refused every key kind. */
    private fun requireSession(principal: AuthenticatedPrincipal) {
        if (principal.authMethod != AuthMethod.OIDC) {
            throw DatapipelinesException(PipelineErrorCodes.Auth.SESSION_REQUIRED, "Dependency trees require a signed-in session")
        }
    }

    private fun parse(
        row: FamilyTransportRow,
        rawQuery: String?,
    ): DependenciesRequest =
        when (val parsed = DependenciesRequest.parse(row, rawQuery)) {
            is Parsed.Accepted -> parsed.request
            is Parsed.Refused -> throw requestInvalid(parsed)
        }

    private class LensedRoot(
        val check: RootLens.Lensed,
        val view: LensedView,
    )

    /** The root lens to apply, or null — read from the ROW first, so a lens-free family never obtains a view. */
    private fun rootLensOf(
        principal: AuthenticatedPrincipal,
        row: FamilyTransportRow,
    ): LensedRoot? {
        val check =
            when (val rootLens = row.rootLens) {
                RootLens.None -> return null
                is RootLens.Lensed -> rootLens
            }
        if (!principal.isLensed) return null
        val view = lens.viewFor(principal)
        // #464 §13.24's precedent: the catalogued, retryable 503; the target's address and reason stay operator facts.
        if (view.unavailable != null) {
            throw DatapipelinesException(PipelineErrorCodes.Navigation.VIEW_UNAVAILABLE, "Dependency view is unavailable; retry")
        }
        return LensedRoot(check, view)
    }

    @Suppress("ThrowsCount") // each failure class is the one catalogued 503, named by its own WARN message; nothing else is caught
    private fun walk(
        principal: AuthenticatedPrincipal,
        request: DependenciesRequest,
    ): GraphSnapshot {
        val workspaceId = principal.requireWorkspace().id
        val context =
            ResolveContext(
                workspaceId = workspaceId,
                snapshot = MetadataSnapshot.READ_COMMITTED,
                purpose = Purpose.INTERACTIVE_READ,
                authority = Authority.Member(principal.userId),
                // §25.4 (#786 R3): one cumulative deadline, the statement ceiling riding on it — each provider statement
                // is bounded by min(statement-timeout-seconds, the time left).
                deadline = Deadline.after(limits.metadataTimeout, clock).withStatementLimit(limits.statementTimeout),
                readIsolation = readIsolation,
            )
        val graph =
            try {
                requireNotNull(transactions.execute { walker.walk(listOf(request.toObjectRequest(workspaceId)), context) }) {
                    "the tree transaction returned no graph"
                }
            } catch (expired: DeadlineExceeded) {
                throw metadataUnavailable(DEADLINE_MESSAGE, expired)
            } catch (failed: DataAccessException) {
                throw metadataUnavailable(READ_MESSAGE, failed)
            } catch (failed: TransactionException) {
                throw metadataUnavailable(READ_MESSAGE, failed)
            }
        // The walker checks the deadline BEFORE each provider read; a read that ran past it returned a graph read outside
        // its budget (#786 R2). Expired at the end of the walk: refused, as the release planner refuses.
        if (context.deadline.expired()) throw metadataUnavailable(DEADLINE_MESSAGE, DeadlineExceeded(request.family))
        return graph
    }

    private fun bounded(data: DependencyTreeData): DependencyTreeData {
        val bytes = mapper.writeValueAsBytes(data).size
        if (bytes > limits.wireBytes) {
            // ERROR with a stack (the handler's 5xx path): a tree this size within the traversal limits means the
            // limits and the byte budget disagree — an operator's fact, never answered by a truncated tree.
            throw DatapipelinesException(
                PipelineErrorCodes.Dependencies.RESPONSE_TOO_LARGE,
                "The dependency tree is larger than the response budget.",
                cause = IllegalStateException("projected tree is $bytes bytes; max-wire-bytes is ${limits.wireBytes}"),
            )
        }
        return data
    }

    private fun requestInvalid(refusal: Parsed.Refused) =
        DatapipelinesException(
            PipelineErrorCodes.Dependencies.REQUEST_INVALID,
            "The dependency tree request is invalid.",
            linkedMapOf(
                PipelineErrorCodes.Dependencies.DETAIL_FIELD to refusal.field.wire,
                PipelineErrorCodes.Dependencies.DETAIL_REASON to refusal.reason.wire,
            ),
        )

    /** The family's own not-found code with one static message: a hidden root and an absent one answer alike. */
    private fun notFound(row: FamilyTransportRow) = DatapipelinesException(row.notFoundCode, NOT_FOUND_MESSAGE)

    private fun metadataUnavailable(
        message: String,
        cause: Throwable,
    ) = DatapipelinesException(PipelineErrorCodes.Dependencies.METADATA_UNAVAILABLE, message, cause = cause)

    companion object {
        /** The one not-found message of every family route — it names nothing the caller sent. */
        const val NOT_FOUND_MESSAGE = "The requested object does not exist or is not visible."

        /** The WARN line's distinction (no stack, no name): the cumulative deadline or a statement bound ran out. */
        const val DEADLINE_MESSAGE = "Dependency metadata did not answer within its deadline; retry."

        /** The WARN line's distinction: a provider's metadata read failed. */
        const val READ_MESSAGE = "Dependency metadata could not be read; retry."
    }
}
