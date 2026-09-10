package co.datapipelines.web.config

import co.datapipelines.auth.DemoWorkspaceSeeder
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.SmartInitializingSingleton

/**
 * Ensures the `demo` workspace exists at first boot (D-R11) — the one workspace the product
 * ships, and the one every user with no membership joins as a VIEWER on first login.
 *
 * ## Why a boot hook when V23 already inserts the row
 * The migration covers a fresh database; this covers everything else, and — the part that
 * matters — it is where the example content is seeded, which SQL cannot do (it goes through
 * the pipeline and template import services).
 *
 * ## O-3: a deactivated `demo` is never recreated
 * The seeder's own check is "does a row named `demo` exist", not "is there an active one", so
 * an operator who deactivates `demo` gets a deployment that stays that way across restarts. A
 * seeder that re-creates what somebody deliberately turned off is a seeder that cannot be
 * turned off.
 *
 * ## Why `SmartInitializingSingleton`, and why in `web`
 * The same two reasons [SystemActorSeeder] states (it and `BootstrapDatasourceStartup` are the
 * authority): `afterSingletonsInstantiated()` runs after Flyway's initializer and inside
 * `refresh()`, before the server accepts traffic — so the row exists before any login can look
 * for it — and it READS the database, which `auth`'s own test contexts cannot support at bean
 * creation. It runs AFTER [SystemActorSeeder] by construction: the content seeding attributes
 * its rows to the system actor, and asks for it lazily at the moment it seeds.
 */
class DemoWorkspaceStartup(
    private val seeder: DemoWorkspaceSeeder,
) : SmartInitializingSingleton {
    private val log = LoggerFactory.getLogger(DemoWorkspaceStartup::class.java)

    override fun afterSingletonsInstantiated() {
        val demo = seeder.ensureDemoWorkspace()
        if (demo == null) {
            log.warn(
                "event=auth.workspace.demo_absent message=\"the demo workspace could not be ensured; " +
                    "users with no membership will land on the no-workspace state\"",
            )
            return
        }
        log.info(
            "event=auth.workspace.demo_ready workspace={} active={} " +
                "message=\"the demo workspace is present; users with no membership join it as viewers\"",
            demo.name,
            demo.isActive,
        )
    }
}
