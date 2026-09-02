package co.datapipelines.web.config

import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.PoolInvalidationPublisher
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
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
import java.util.UUID

/**
 * The Redis pub/sub channel behind §5.7's cross-instance pool invalidation (050/R1,
 * ARCH-AUDIT M3/M10). The messaging precedent [CancellationFlags], [IdempotencyStore],
 * [RedisResultStore] and `SseEventLog` set is Redis *state*; this is the codebase's first Redis
 * *message*, and that is why it lives beside them in `web` (one of the two modules allowed
 * Redis, module-structure §4.2) with the port in `datasources` (like `DatasourceReferences`:
 * the library declares the need, the aggregation layer supplies the mechanism).
 *
 * ## The contract, once each
 *
 * - **Publish** — the registry calls the [PoolInvalidationPublisher] port after the row write
 *   has committed, beside its synchronous local eviction (save/delete). A Redis fault at
 *   publish is a WARN, never a failed save: the write already succeeded, and peers degrade to
 *   the pre-050 behaviour (stale pool until their next restart) rather than the API refusing a
 *   valid datasource save over fan-out.
 * - **Subscribe** — every instance runs a [RedisMessageListenerContainer] (Spring's container,
 *   not a hand-rolled loop: it re-subscribes after a Redis reconnect, which a hand-rolled read
 *   loop gets silently wrong). On message: parse, ignore the publishing instance's own origin
 *   (the writer already evicted synchronously — acting on its own message would double-evict a
 *   pool an in-flight lease may just have rebuilt), and evict the named pool. Next use rebuilds
 *   from the row.
 *
 * ## Startup ordering
 *
 * The container is a `SmartLifecycle` at the maximum phase, so the subscription is active
 * before the web server accepts traffic — a pool built during a subscription gap would be
 * exactly the M3 staleness this channel exists to close.
 *
 * ## Payload
 *
 * One JSON object — the datasource name is the whole instruction; `origin` exists only so
 * subscribers can skip their own messages. Garbled payloads are logged and dropped: one bad
 * message must not kill the subscription thread for every subsequent one.
 */
@Configuration
class DatasourceInvalidationConfiguration {
    /** This instance's identity inside invalidation messages — generated per boot, never persisted. */
    @Bean
    fun poolInvalidationInstanceId(): String = UUID.randomUUID().toString()

    @Bean
    fun poolInvalidationPublisher(
        redis: StringRedisTemplate,
        mapper: ObjectMapper,
        instanceId: String,
    ): PoolInvalidationPublisher = RedisPoolInvalidationPublisher(redis, mapper, instanceId)

    @Bean
    fun datasourceInvalidationListener(
        registry: DatasourceRegistry,
        mapper: ObjectMapper,
        instanceId: String,
    ): DatasourceInvalidationListener = DatasourceInvalidationListener(registry, mapper, instanceId)

    /**
     * The subscription. `autoStartup` (the default) begins the subscription during context
     * refresh — see the class KDoc's ordering paragraph — and the container survives a Redis
     * reconnect by re-issuing `SUBSCRIBE` on a fresh connection.
     */
    @Bean
    fun datasourceInvalidationListenerContainer(
        factory: RedisConnectionFactory,
        listener: DatasourceInvalidationListener,
    ): RedisMessageListenerContainer =
        RedisMessageListenerContainer().apply {
            setConnectionFactory(factory)
            addMessageListener(listener, ChannelTopic(PoolInvalidationChannel.NAME))
        }
}

/** The channel name — one constant shared by publisher and subscriber so the two cannot drift. */
internal object PoolInvalidationChannel {
    /** `web`'s pub/sub channel, named beside the `dp:` keyspace conventions (module-structure §5.9). */
    const val NAME = "dp:datasource-invalidated"
}

/** What the channel carries. `origin` lets a subscriber recognize and skip its own publish. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class PoolInvalidationMessage(
    val origin: String,
    val name: String,
)

/** The publishing half — see [DatasourceInvalidationConfiguration]. */
class RedisPoolInvalidationPublisher(
    private val redis: StringRedisTemplate,
    private val mapper: ObjectMapper,
    private val instanceId: String,
) : PoolInvalidationPublisher {
    @Suppress("SwallowedException")
    override fun publish(datasourceName: String) {
        try {
            val payload = mapper.writeValueAsString(PoolInvalidationMessage(origin = instanceId, name = datasourceName))
            redis.convertAndSend(PoolInvalidationChannel.NAME, payload)
        } catch (e: DataAccessException) {
            // The save that triggered this already committed; failing it over fan-out would be
            // strictly worse. Peers keep their stale pools until restart — the pre-050 state.
            LOG.warn(
                "event=datasource.pool_invalidation_publish_failed datasource={} message=\"{}\" " +
                    "reason=\"peers serve the stale pool until their next restart\"",
                datasourceName,
                e.message,
            )
        }
    }

    private companion object {
        val LOG = LoggerFactory.getLogger(RedisPoolInvalidationPublisher::class.java)
    }
}

/**
 * The receiving half: evict the named pool unless this instance published the message (its
 * save path already evicted synchronously — reacting again would double-evict a pool that an
 * in-flight lease may just have rebuilt from the new row).
 *
 * Registered as a plain [MessageListener]; returns are ignored, and every failure path is
 * caught and logged because an exception out of here would kill the subscription thread.
 */
class DatasourceInvalidationListener(
    private val registry: DatasourceRegistry,
    private val mapper: ObjectMapper,
    private val instanceId: String,
) : MessageListener {
    override fun onMessage(
        message: Message,
        pattern: ByteArray?,
    ) {
        val parsed =
            try {
                mapper.readValue(String(message.body, Charsets.UTF_8), PoolInvalidationMessage::class.java)
            } catch (e: com.fasterxml.jackson.core.JsonProcessingException) {
                LOG.warn(
                    "event=datasource.pool_invalidation_malformed reason=\"dropped\" message=\"{}\"",
                    e.message?.take(MALFORMED_MESSAGE_HEAD_CHARS),
                )
                return
            }
        if (parsed.origin == instanceId) return
        if (registry.evictPool(parsed.name)) {
            LOG.info(
                "event=datasource.pool_invalidated_remotely datasource={} origin={} " +
                    "message=\"pool evicted after a save on another instance; next use rebuilds from the row\"",
                parsed.name,
                parsed.origin,
            )
        }
    }

    private companion object {
        val LOG = LoggerFactory.getLogger(DatasourceInvalidationListener::class.java)

        /** Bounded so a hostile/garbled payload cannot spray its whole body into the log. */
        const val MALFORMED_MESSAGE_HEAD_CHARS = 200
    }
}
