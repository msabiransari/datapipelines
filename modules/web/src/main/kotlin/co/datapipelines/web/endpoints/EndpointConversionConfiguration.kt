package co.datapipelines.web.endpoints

import co.datapipelines.application.endpoints.EndpointConversionConflict
import co.datapipelines.application.endpoints.EndpointRegistry
import co.datapipelines.application.endpoints.EndpointVersionConversion
import co.datapipelines.application.endpoints.PublishedEndpointVersionRepository
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.guard.MetadataWriterGuard
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.util.UUID

/**
 * One family the upgrade converts at startup, after Flyway and before the server serves or the scheduler dispatches
 * (#786 §E.1, §D.5). The port is web's so the composition root (`modules/app`, which lists `web` only) can run the
 * families without compiling against `application`; a family that refuses throws, and the throw refuses the startup.
 *
 * The RUNNER owns the one transaction and the one lock order (#895): E.0 first — it reads EVERY family's
 * [logPrecondition] and refuses the startup when any reported a row, before anything is written — then, inside ONE
 * transaction for every family, it asks each family to [plan] an attempt, acquires the workspace guard ONCE over the
 * union of every attempt's [ConversionAttempt.workspaces], then the endpoint registry key ([ConversionLocks]), then
 * each attempt's [ConversionAttempt.lockEntities], and only then each attempt's [ConversionAttempt.convert]. A family
 * owns no transaction and acquires no workspace or registry lock of its own.
 */
interface StartupConversion {
    /**
     * E.0 — logs each row this family cannot convert (its structured ERROR lines and summary) and returns how many;
     * writes nothing. Zero = convertible.
     */
    fun logPrecondition(): Int = 0

    /** Reads, inside the runner's transaction and before any lock, what this family would convert now. */
    fun plan(): ConversionAttempt
}

/** One family's part of ONE attempt of the runner's transaction; discarded when the attempt rolls back. */
interface ConversionAttempt {
    /** Every workspace this attempt would write in — the family's share of the runner's union. */
    val workspaces: Set<UUID>

    /** G0 class 4: this family's entity rows, in a deterministic order, after the registry key. None by default. */
    fun lockEntities() = Unit

    /**
     * Re-reads the family's candidates UNDER the runner's locks and writes their conversion. Throws
     * [StartupConversionConflict] when a candidate is outside [guarded] or this family's planned entity locks, or a CAS
     * lost (the runner rolls back and re-plans). Rows already converted or removed no longer match the family's
     * legacy-only selector and are skipped; new rows covered by its locks can convert. Any other throw refuses the
     * startup. Returns what may only happen after the joint commit.
     */
    fun convert(guarded: Set<UUID>): CommitEffects
}

/**
 * What one family's conversion reports only once the joint transaction COMMITTED — its converted lines, its success
 * summary and the registry invalidation (`EndpointRegistry` requires it after commit). Discarded on a rollback.
 */
fun interface CommitEffects {
    fun publish()
}

/** A lost race (a CAS, a row outside the guarded union): the runner rolls the whole transaction back and re-plans. */
class StartupConversionConflict(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** The runner's G0 acquisitions (classes 2 and 3), on the production guard; transaction-scoped like every G0 lock. */
interface ConversionLocks {
    /** Class 2: the workspace rows, ONCE, sorted. */
    fun workspaces(workspaceIds: Collection<UUID>)

    /** Class 3: the endpoint registry key. */
    fun endpointRegistry()
}

/** #786 §E and #895 — the in-place API conversion's beans and the runner's lock port; `modules/app`'s runner runs them. */
@Configuration
class EndpointConversionConfiguration {
    @Bean
    fun endpointVersionConversion(
        jdbc: NamedParameterJdbcTemplate,
        versions: PublishedEndpointVersionRepository,
        registry: EndpointRegistry,
    ): EndpointVersionConversion = EndpointVersionConversion(jdbc, versions, registry)

    /**
     * The runner's acquisitions on the one production guard. The busy code is the API family's: the registry key is
     * the endpoints' own, and at startup the code only names why the start refused (nothing is served yet).
     */
    @Bean
    fun conversionLocks(writeGuard: MetadataWriterGuard): ConversionLocks =
        object : ConversionLocks {
            override fun workspaces(workspaceIds: Collection<UUID>) =
                writeGuard.workspaces(workspaceIds, PipelineErrorCodes.Endpoint.WRITE_BUSY)

            override fun endpointRegistry() = writeGuard.endpointRegistry(PipelineErrorCodes.Endpoint.WRITE_BUSY)
        }

    /** The API family (E.1), FIRST in the runner's order. The schedule family (D.5) is second, wired beside the scheduler. */
    @Bean
    @Order(API_FAMILY_ORDER)
    fun endpointStartupConversion(conversion: EndpointVersionConversion): StartupConversion =
        object : StartupConversion {
            override fun logPrecondition(): Int = conversion.logPrecondition()

            override fun plan(): ConversionAttempt {
                val attempt = conversion.plan()
                return object : ConversionAttempt {
                    override val workspaces: Set<UUID> = attempt.workspaces

                    override fun lockEntities() = attempt.lockEntities()

                    override fun convert(guarded: Set<UUID>): CommitEffects =
                        try {
                            attempt.convert(guarded).let { outcome -> CommitEffects { outcome.publish() } }
                        } catch (conflict: EndpointConversionConflict) {
                            throw StartupConversionConflict(conflict.message.orEmpty(), conflict)
                        }
                }
            }
        }

    companion object {
        /** The runner's family order: the APIs, then the schedules ([SCHEDULE_FAMILY_ORDER]). */
        const val API_FAMILY_ORDER = 1
        const val SCHEDULE_FAMILY_ORDER = 2
    }
}
