package co.datapipelines.lake.maintenance.plan

import co.datapipelines.lake.maintenance.MaintenanceOutcome
import co.datapipelines.lake.profiles.LakeProfile
import co.datapipelines.lake.profiles.LakeProfileFixtures
import co.datapipelines.lake.profiles.LakeTablePause
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Inputs for the pure due function's cases (N5–N8): every table built from the built-in profile unless told otherwise. */
internal object PlanFixtures {
    /** Inside the built-in window `02:00-05:00`. */
    val NOW: Instant = Instant.parse("2026-11-14T03:00:00Z")
    val PROFILE_ID: UUID = UUID.fromString("00000000-0000-0000-0000-00000000aaaa")
    val PROFILE_UPDATED: Instant = NOW.minus(Duration.ofDays(30))
    val GRACE: Duration = Duration.ofHours(72)
    val ALL_PARTS: Set<String> = MaintenancePartNames.ALL.toSet()

    fun id(n: Int): UUID = UUID.fromString("00000000-0000-0000-0000-%012d".format(n))

    fun hoursAgo(h: Long): Instant = NOW.minus(Duration.ofHours(h))

    /** A successful measurement under the table's current profile pair, with a snapshot. */
    @Suppress("LongParameterList")
    fun measured(
        small: Long = 0,
        unsorted: Long = 0,
        overCap: Long = 0,
        manifests: Long = 10,
        profileUpdatedAt: Instant = PROFILE_UPDATED,
        errorCode: String? = null,
        snapshot: Long? = 1,
    ): StoredMeasurement =
        StoredMeasurement(
            attemptId = UUID.randomUUID(),
            attemptedAt = hoursAgo(1),
            attemptProfileId = PROFILE_ID,
            attemptProfileUpdatedAt = profileUpdatedAt,
            errorCode = errorCode,
            measuredAt = hoursAgo(1),
            figures =
                if (snapshot == null) {
                    TableFigures.NO_SNAPSHOT
                } else {
                    TableFigures(snapshot, 1, 1, 1, 0, manifests, unsorted, small, overCap, 0)
                },
            figuresProfileId = PROFILE_ID,
            figuresProfileUpdatedAt = profileUpdatedAt,
        )

    @Suppress("LongParameterList")
    fun input(
        n: Int,
        status: String = PlanStatus.ACTIVE,
        measurement: StoredMeasurement? = measured(),
        lastCleanup: Instant? = hoursAgo(1),
        createdAt: Instant = hoursAgo(1000),
        lastFinishedRewrite: Instant? = null,
        attempt: RewriteAttempt? = null,
        pause: LakeTablePause = LakeTablePause(null, null),
        profile: LakeProfile = LakeProfileFixtures.builtIn(),
        profileUpdatedAt: Instant = PROFILE_UPDATED,
        profileAssignedAt: Instant = hoursAgo(2000),
        purgeDeletedAt: Instant? = null,
        finalProtectedUntil: Instant? = null,
    ): DueInput =
        DueInput(
            tableId = id(n),
            status = status,
            createdAt = createdAt,
            profileId = PROFILE_ID,
            profileUpdatedAt = profileUpdatedAt,
            profileAssignedAt = profileAssignedAt,
            profile = profile,
            pause = pause,
            measurement = measurement,
            purgeDeletedAt = purgeDeletedAt,
            finalProtectedUntil = finalProtectedUntil,
            history = RunHistory(lastCleanup, lastFinishedRewrite, attempt),
        )

    fun attempt(
        hoursAgo: Long,
        outcome: MaintenanceOutcome,
        committed: Boolean? = false,
        left: Long? = 0,
    ): RewriteAttempt = RewriteAttempt(hoursAgo(hoursAgo), outcome, committed, left)
}
