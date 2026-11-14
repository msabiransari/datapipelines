package co.datapipelines.lake.maintenance.plan

import co.datapipelines.lake.maintenance.GateAnswer
import co.datapipelines.lake.maintenance.MaintenanceGate
import co.datapipelines.lake.maintenance.MaintenancePart
import co.datapipelines.lake.maintenance.MaintenanceTable
import co.datapipelines.lake.maintenance.PartResult
import co.datapipelines.lake.profiles.LakeProfileDocument
import co.datapipelines.lake.profiles.LakeTablePauses
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/**
 * The real gate (brief 959 D6; the cut's L13), replacing 957's admit-all default: the table's CURRENT profile window,
 * its two pauses and the catalog's clock, read in one statement before each part, answered by [MaintenanceHolds] (the
 * function the plan uses). A table whose row has gone is admitted: the part finds nothing to do.
 */
class ProfileMaintenanceGate(
    private val jdbc: NamedParameterJdbcTemplate,
) : MaintenanceGate {
    override fun admit(
        table: MaintenanceTable,
        part: MaintenancePart,
    ): GateAnswer {
        val row =
            jdbc
                .query(
                    "SELECT p.values_json::text AS values_json, t.policy_json::text AS policy_json, now() AS now " +
                        "FROM lake_tables_managed t JOIN lake_profiles p ON p.id = t.profile_id WHERE t.id = :table",
                    mapOf("table" to table.id),
                ) { rs, _ -> Triple(rs.getString("values_json"), rs.getString("policy_json"), rs.getTimestamp("now").toInstant()) }
                .singleOrNull() ?: return GateAnswer.Admit
        val (values, policy, now) = row
        val hold =
            MaintenanceHolds.hold(part.name, LakeProfileDocument.parse(values).maintenanceWindow, LakeTablePauses.parse(policy), now)
                ?: return GateAnswer.Admit
        return GateAnswer.Held(PartResult(hold.outcome, until = hold.until), hold.reason)
    }
}
