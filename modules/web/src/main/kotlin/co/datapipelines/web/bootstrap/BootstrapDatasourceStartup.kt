package co.datapipelines.web.bootstrap

import co.datapipelines.auth.UserService
import co.datapipelines.datasources.BootstrapDatasourceRegistrar
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.SmartInitializingSingleton

/**
 * Runs bootstrap datasource registration once per startup (sample-data design §6).
 *
 * ## Why `SmartInitializingSingleton` and not `ApplicationRunner`
 * The spec's ordering is "after Flyway and after §6.1 actor resolution, **before serving
 * traffic**", and this interface gives both edges by construction rather than by convention:
 * `afterSingletonsInstantiated()` runs at the end of `preInstantiateSingletons()`, so every
 * singleton — Flyway's `flywayInitializer` included — is already built, and it runs *inside*
 * `refresh()`, before `finishRefresh()` starts the embedded web server. An `ApplicationRunner`
 * would satisfy the Flyway edge but fire after the connector is already accepting connections.
 *
 * ## Why the whole act lives in `web`
 * It needs `auth` (the actor) and `datasources` (the registration) in one place, and `web` is the
 * only module allowed to depend on both (module-structure §4.2 — `app` may depend on `web`
 * alone). `app` owns the config keys and their validation; the composition is here.
 *
 * A failure — unreadable file, unresolved `${ENV_VAR}`, an entry failing §9 validation — is left
 * to propagate: the context fails and the process exits non-zero. A half-registered demo is worse
 * than a loud one, and create-if-absent makes the operator's retry idempotent.
 */
class BootstrapDatasourceStartup(
    private val properties: BootstrapProperties,
    private val userService: UserService,
    private val registrar: BootstrapDatasourceRegistrar,
) : SmartInitializingSingleton {
    private val log = LoggerFactory.getLogger(BootstrapDatasourceStartup::class.java)

    override fun afterSingletonsInstantiated() {
        val path = properties.datasourcesPath() ?: return
        // §6.1 item 1: the actor is resolved (pre-provisioning the row when it is absent) BEFORE
        // any entry is applied — `datasources.created_by` is NOT NULL REFERENCES users(id).
        val actor = userService.provisionBootstrapActor()
        log.info(
            "event=datasource.bootstrap_start file={} actor_user_id={} actor_provider={}",
            path,
            actor.id,
            actor.provider,
        )
        registrar.register(path, actor.id)
    }
}
