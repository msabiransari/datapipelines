package co.datapipelines.web.config

import co.datapipelines.auth.UserService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.SmartInitializingSingleton

/**
 * Provisions the system service account at first boot (auth.md §4.5, ruling R7).
 *
 * `pipeline_versions.created_by` and `pipeline_executions.triggered_by` are
 * `NOT NULL REFERENCES users(id)`, so every write the SYSTEM makes on nobody's behalf —
 * a promoted version, a retention sweep, a stale-execution reap — still needs a row to
 * point at. That row is provisioned here, ONCE, and read everywhere through
 * [UserService.systemActor] so nobody mints a second one.
 *
 * ## Unconditional, unlike its siblings
 * `LocalAdminSeeder` and `BootstrapDatasourceStartup` are inert without their config; this
 * one always runs. The actor is not a feature an operator opts into — it is a referential
 * precondition of the schema, and a deployment that discovers it missing discovers it at a
 * foreign-key violation inside a promotion or a scheduled job, which is the worst possible
 * moment.
 *
 * ## Why `SmartInitializingSingleton`
 * The same ordering contract `BootstrapDatasourceStartup`'s KDoc states (it is the
 * authority): `afterSingletonsInstantiated()` runs after every singleton — Flyway's
 * initializer included — is built, and *inside* `refresh()`, before the web server accepts
 * traffic. So the row exists before any promotion push, scheduled job or request can need
 * it. Provisioning is idempotent: a restart returns the existing row untouched.
 *
 * ## Why it lives in `web`, not in `auth`
 * The same reason [AuthoringStartupCheck] does: it READS the database, so it must be a bean
 * that initializes after Flyway has applied the schema. `auth`'s own test contexts build
 * their schema after the context refreshes, so a database-reading singleton wired in
 * `AuthConfiguration` would crash every one of them. The behaviour it drives
 * ([UserService.provisionSystemActor]) stays in `auth`, where the users table belongs.
 */
class SystemActorSeeder(
    private val userService: UserService,
) : SmartInitializingSingleton {
    private val log = LoggerFactory.getLogger(SystemActorSeeder::class.java)

    override fun afterSingletonsInstantiated() {
        val actor = userService.provisionSystemActor()
        log.info(
            "event=auth.system_actor.ready user_id={} provider={} " +
                "message=\"the system service account is provisioned; every non-user-bound write is stamped with it\"",
            actor.id,
            actor.provider,
        )
    }
}
