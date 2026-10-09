package co.datapipelines.web.sse

import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * The events of a read that must have found its log (#487). Any other answer fails the test and
 * names itself — an [ReplayRead.Unavailable] carries its Redis cause into the failure — where the
 * pre-#487 `replay(id)!!` failed with a bare NPE for both an absent log and a store fault.
 */
fun ReplayRead.loggedEvents(): List<LoggedSseEvent> = shouldBeInstanceOf<ReplayRead.Log>().events

/** The events of a read whose log may legitimately be gone (none then); a store fault still fails the test. */
fun ReplayRead.loggedEventsOrNone(): List<LoggedSseEvent> = if (this == ReplayRead.Absent) emptyList() else loggedEvents()
