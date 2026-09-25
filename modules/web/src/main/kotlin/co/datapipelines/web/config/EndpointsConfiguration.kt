package co.datapipelines.web.config

import co.datapipelines.application.endpoints.EndpointAuthorizer
import co.datapipelines.application.endpoints.EndpointInvalidationPublisher
import co.datapipelines.application.endpoints.EndpointKeyBindingRepository
import co.datapipelines.application.endpoints.EndpointKeyService
import co.datapipelines.application.endpoints.EndpointPublishService
import co.datapipelines.application.endpoints.EndpointRegistry
import co.datapipelines.application.endpoints.EndpointServeAudit
import co.datapipelines.application.endpoints.PublishedEndpointRepository
import co.datapipelines.application.endpoints.ReadOnlyPipelineRule
import co.datapipelines.auth.ApiKeyService
import co.datapipelines.auth.AuditEventSink
import co.datapipelines.auth.WorkspaceLiveness
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.ResultConfig
import co.datapipelines.executor.ResultStore
import co.datapipelines.executor.ResultUrlFactory
import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineResolver
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.web.endpoints.PublishedEndpointServeService
import co.datapipelines.web.endpoints.PublishedExecutionPagingService
import co.datapipelines.web.executions.ExecutionMetadataProjection
import co.datapipelines.web.executions.ResultCursor
import co.datapipelines.web.executions.ExecutionVisibility
import co.datapipelines.web.pipelines.RecordingExecutionRunner
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.connection.Message
import org.springframework.data.redis.connection.MessageListener
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.listener.ChannelTopic
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/**
 * Every bean of the published-endpoint feature that `web` owns (074) — the registry, its
 * cross-instance invalidation, and the services the REST/MCP surfaces call.
 *
 * ## Cross-instance invalidation (design §4.1)
 *
 * The same 050 mechanism, and deliberately the same shape, as datasource pool invalidation
 * next door.
 *
 * ## Why this is needed at all
 *
 * [EndpointRegistry] holds the whole enabled registry per JVM so a request under `/api` can be
 * resolved without a metadata-DB round trip. With N replicas, a publish on instance A leaves B
 * and C serving `404` for a URL that exists. A TTL would fix that eventually, with a window
 * nobody can predict and a different answer per instance; a broadcast fixes it at the write.
 *
 * ## The payload is the instruction
 *
 * Unlike the pool channel, the message carries no name: the registry is small and is reloaded
 * whole, so "something changed" is the entire instruction. `origin` exists only so a subscriber
 * can skip its own publish — the publishing instance has already dropped its snapshot
 * synchronously, and reloading twice would be harmless but pointless.
 *
 * ## Degradation
 *
 * A publish that fails (Redis down) is logged at WARN and swallowed: the write has already
 * committed and the LOCAL instance is correct, so failing the caller's publish afterwards would
 * be a lie about what happened. Peers serve the previous registry until their next reload — the
 * same trade the pool publisher documents.
 */
@Configuration
class EndpointsConfiguration {
    /**
     * The registry's persistence. Declared here rather than in `DomainConfiguration` for
     * cohesion — every bean of the published-endpoint feature that `web` owns is in this file —
     * and because `modules/application` ships no Spring configuration of its own, so its
     * repositories are wired by the module that composes them.
     */
    @Bean
    fun publishedEndpointRepository(jdbc: NamedParameterJdbcTemplate): PublishedEndpointRepository = PublishedEndpointRepository(jdbc)

    /** The per-instance registry cache, invalidated through the channel below. */
    @Bean
    fun endpointRegistry(
        repository: PublishedEndpointRepository,
        invalidation: EndpointInvalidationPublisher,
    ): EndpointRegistry = EndpointRegistry(repository, invalidation)

    /** The key-binding table (§4.14). */
    @Bean
    fun endpointKeyBindingRepository(jdbc: NamedParameterJdbcTemplate): EndpointKeyBindingRepository = EndpointKeyBindingRepository(jdbc)

    /**
     * Issuance that writes a key and its bindings together (§5.2) — cross-aggregate, so the
     * service lives in `modules/application` and only its wiring is here. The published-endpoint
     * repository is what #191's bind-time rule reads: a binding of a workspace must name its own
     * published tree.
     */
    @Bean
    fun endpointKeyService(
        apiKeys: ApiKeyService,
        bindings: EndpointKeyBindingRepository,
        audit: AuditEventSink,
        publishedEndpoints: PublishedEndpointRepository,
    ): EndpointKeyService = EndpointKeyService(apiKeys, bindings, audit, publishedEndpoints)

    /**
     * The §7.7 proof that an execution belongs to the endpoint key asking for its result. Reads
     * the serve audit row, because `executed_by` is the key's OWNER and cannot tell two of one
     * person's keys apart.
     */
    @Bean
    fun endpointServeAudit(jdbc: NamedParameterJdbcTemplate): EndpointServeAudit = EndpointServeAudit(jdbc)

    /**
     * Publishing and unpublishing (§4.2/§6) — the ONE validated path REST, MCP and promotion all
     * go through, so a publish cannot skip the read-only rule by arriving over a different
     * surface.
     */
    @Bean
    // A DI factory's arity is the container's business: every parameter is a bean the service
    // genuinely needs (178 added the promoter lens), and a holder type would exist only to be counted.
    @Suppress("LongParameterList")
    fun endpointPublishService(
        endpoints: PublishedEndpointRepository,
        pipelines: PipelineService,
        pipelineRepository: PipelineRepository,
        readOnlyRule: ReadOnlyPipelineRule,
        registry: EndpointRegistry,
        audit: AuditEventSink,
        properties: EndpointsProperties,
        // 178 — the promoter lens on the endpoint reads (visible iff the pipeline is).
        lens: co.datapipelines.application.lens.PromoterLens,
    ): EndpointPublishService =
        EndpointPublishService(
            endpoints = endpoints,
            pipelines = pipelines,
            pipelineRepository = pipelineRepository,
            readOnlyRule = readOnlyRule,
            registry = registry,
            audit = audit,
            lens = lens,
            timeouts =
                EndpointPublishService.TimeoutBounds(
                    defaultSeconds = properties.timeoutDefaultSeconds,
                    minSeconds = properties.timeoutMinSeconds,
                    maxSeconds = properties.timeoutMaxSeconds,
                ),
        )

    /** §7.2/§7.7 — the one execution-visibility rule both execution reads share. */
    @Bean
    fun executionVisibility(serveAudit: EndpointServeAudit): ExecutionVisibility = ExecutionVisibility(serveAudit)

    /** §10.2 — the ONE metadata projection, shared by the framework route and the paging route (keys v2 A16). */
    @Bean
    fun executionMetadataProjection(
        pipelines: PipelineRepository,
        resultStore: ResultStore,
        resultUrls: ResultUrlFactory,
    ): ExecutionMetadataProjection = ExecutionMetadataProjection(pipelines, resultStore, resultUrls)

    /**
     * Keys v2 A16/B3 — the business-path paging read, reached THROUGH the serve catch-all; see
     * [PublishedExecutionPagingService] for why it is not a mapping of its own.
     */
    @Bean
    fun publishedExecutionPagingService(
        executions: ExecutionRepository,
        cursor: ResultCursor,
        visibility: ExecutionVisibility,
        metadata: ExecutionMetadataProjection,
        authorizer: EndpointAuthorizer,
        bindings: EndpointKeyBindingRepository,
    ): PublishedExecutionPagingService =
        PublishedExecutionPagingService(executions, cursor, visibility, metadata, authorizer, bindings)

    /** The §5.2 hierarchical decision. Pure and stateless — one instance serves every request. */
    @Bean
    fun endpointAuthorizer(): EndpointAuthorizer = EndpointAuthorizer()

    /**
     * The §4.2 read-only rule. It resolves PIPELINE children through the same
     * [co.datapipelines.pipeline.PipelineResolver] the save-time validator uses, so "what the
     * child pipeline is" has one answer across both.
     */
    @Bean
    fun readOnlyPipelineRule(
        pipelineResolver: PipelineResolver,
        pipelineProperties: PipelineProperties,
    ): ReadOnlyPipelineRule = ReadOnlyPipelineRule(pipelineResolver, pipelineProperties.maxCompositionDepth)

    /**
     * The serving path (§5). It takes the application's execution scope rather than making its
     * own, so an endpoint's run is drained at shutdown with every other execution — and so the
     * `202` contract's "the execution keeps running" is true of a scope that outlives the
     * request but not the application.
     */
    @Bean
    @Suppress("LongParameterList") // one composition root for one request path
    fun publishedEndpointServeService(
        registry: EndpointRegistry,
        bindings: EndpointKeyBindingRepository,
        authorizer: EndpointAuthorizer,
        readOnlyRule: ReadOnlyPipelineRule,
        pipelines: PipelineService,
        runner: RecordingExecutionRunner,
        resultStore: ResultStore,
        resultUrls: ResultUrlFactory,
        resultConfig: ResultConfig,
        endpointsProperties: EndpointsProperties,
        audit: AuditEventSink,
        executionScope: WebSurfaceConfiguration.ExecutionCoroutineScope,
        authoringGuard: AuthoringGuard,
        // 180 (D15): `WorkspaceService` is the one implementation — the auth liveness cache
        // answers, so a deactivated workspace's endpoint is unknown within the same TTL as its keys.
        workspaceLiveness: WorkspaceLiveness,
    ): PublishedEndpointServeService =
        PublishedEndpointServeService(
            registry = registry,
            bindings = bindings,
            authorizer = authorizer,
            readOnlyRule = readOnlyRule,
            pipelines = pipelines,
            runner = runner,
            resultStore = resultStore,
            resultUrls = resultUrls,
            resultConfig = resultConfig,
            endpointsProperties = endpointsProperties,
            audit = audit,
            authoring = authoringGuard,
            scope = executionScope,
            workspaceLiveness = workspaceLiveness,
        )

    @Bean
    fun endpointInvalidationPublisher(
        redis: StringRedisTemplate,
        mapper: ObjectMapper,
        // The per-boot instance identity, reused from DatasourceInvalidationConfiguration rather
        // than minting a second one: it is this JVM's identity, not this channel's, and two
        // unqualified String beans would be an ambiguity the container refuses anyway.
        instanceId: String,
    ): EndpointInvalidationPublisher = RedisEndpointInvalidationPublisher(redis, mapper, instanceId)

    @Bean
    fun endpointInvalidationListener(
        registry: EndpointRegistry,
        mapper: ObjectMapper,
        instanceId: String,
    ): EndpointInvalidationListener = EndpointInvalidationListener(registry, mapper, instanceId)

    /**
     * The subscription. A SECOND container rather than another listener on the existing one: the
     * two channels have independent lifecycles and an error on one must not disturb the other.
     */
    @Bean
    fun endpointInvalidationListenerContainer(
        factory: RedisConnectionFactory,
        listener: EndpointInvalidationListener,
    ): RedisMessageListenerContainer =
        RedisMessageListenerContainer().apply {
            setConnectionFactory(factory)
            addMessageListener(listener, ChannelTopic(EndpointInvalidationChannel.NAME))
        }
}

/** The channel name — one constant shared by publisher and subscriber so the two cannot drift. */
internal object EndpointInvalidationChannel {
    const val NAME = "dp:endpoints-invalidated"
}

/** What the channel carries. `origin` lets a subscriber recognize and skip its own publish. */
internal data class EndpointInvalidationMessage(
    val origin: String = "",
)

/** Publishes "the endpoint registry is stale" to every other instance. */
internal class RedisEndpointInvalidationPublisher(
    private val redis: StringRedisTemplate,
    private val mapper: ObjectMapper,
    private val instanceId: String,
) : EndpointInvalidationPublisher {
    @Suppress("SwallowedException") // the write already committed; see the class KDoc's degradation note
    override fun publish() {
        try {
            redis.convertAndSend(
                EndpointInvalidationChannel.NAME,
                mapper.writeValueAsString(EndpointInvalidationMessage(origin = instanceId)),
            )
        } catch (e: DataAccessException) {
            LOG.warn(
                "event=endpoint.registry_invalidation_publish_failed message=\"{}\" " +
                    "reason=\"peers serve the previous endpoint registry until their next reload\"",
                e.message,
            )
        }
    }

    private companion object {
        val LOG = LoggerFactory.getLogger(RedisEndpointInvalidationPublisher::class.java)
    }
}

/** Drops this instance's registry snapshot when ANOTHER instance published a change. */
class EndpointInvalidationListener(
    private val registry: EndpointRegistry,
    private val mapper: ObjectMapper,
    private val instanceId: String,
) : MessageListener {
    override fun onMessage(
        message: Message,
        pattern: ByteArray?,
    ) {
        val parsed =
            try {
                mapper.readValue(String(message.body, Charsets.UTF_8), EndpointInvalidationMessage::class.java)
            } catch (e: com.fasterxml.jackson.core.JsonProcessingException) {
                // One bad message must not kill the subscription for every subsequent one.
                LOG.warn(
                    "event=endpoint.registry_invalidation_malformed reason=\"dropped\" message=\"{}\"",
                    e.message?.take(MALFORMED_MESSAGE_HEAD_CHARS),
                )
                return
            }
        if (parsed.origin == instanceId) return
        registry.invalidateLocally()
        LOG.info(
            "event=endpoint.registry_invalidated_remotely origin={} " +
                "message=\"snapshot dropped after a publish on another instance; next request reloads it\"",
            parsed.origin,
        )
    }

    private companion object {
        val LOG = LoggerFactory.getLogger(EndpointInvalidationListener::class.java)
        const val MALFORMED_MESSAGE_HEAD_CHARS = 200
    }
}
