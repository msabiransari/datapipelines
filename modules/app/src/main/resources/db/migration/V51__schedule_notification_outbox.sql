-- =============================================================================
-- V51 — the schedule notification outbox (#442 part b, slice 4 of #9)
--
-- Authority: docs/superpowers/specs/2026-09-22-scheduler-design-revision.md §6.1 (outbox, one
-- message per recipient, a stable Message-ID, bounded retries, visible delivery states, resend)
-- and §5.4 (the trail's notification kinds); the owner's rulings in #442. Three facts:
--
-- 1. `schedule_notification_events` — one row per run transition (or schedule block) that the
--    schedule's event set selected at that moment, written in the SAME transaction as the
--    transition and its trail row (RunLedger). `run_id` is NOT NULL: a block is always caused by
--    a run, and the notice attaches to it. UNIQUE (run_id, outcome): a run reaches each outcome at
--    most once, so a replayed transition can never write a second event. `sequence` is the trail
--    row's seq of the transition that produced it.
--
-- 2. `schedule_notification_deliveries` — one row per (event, recipient), the recipient address
--    snapshotted at enqueue (the record's identity, UNIQUE). `state`: pending → accepted | failed |
--    uncertain; `uncertain` is a send that may have reached the relay and is never retried
--    automatically. `message_id` is fixed at enqueue, so a resend carries the same Message-ID.
--    `sending_since` marks a claimed send in flight; a pending row whose claim went stale (the
--    instance died mid-send) is marked uncertain, never sent twice blindly.
--
-- 3. The trail's kind CHECK gains notification_enqueued, notification_sent, notification_failed
--    and notification_uncertain (TrailKind; the run detail's Messages pane merges the trail).
--
-- Down path (documented, not shipped — Flyway runs forward only):
--   ALTER TABLE schedule_runs DROP COLUMN target_ref;
--   DROP TABLE schedule_notification_deliveries; DROP TABLE schedule_notification_events;
--   DELETE FROM schedule_run_events WHERE kind LIKE 'notification\_%';
--   ALTER TABLE schedule_run_events DROP CONSTRAINT chk_schedule_run_events_kind;
--   ALTER TABLE schedule_run_events ADD CONSTRAINT chk_schedule_run_events_kind CHECK (kind IN (
--       'recorded', 'capacity_retry', 'claimed', 'execution_started', 'not_started', 'skipped',
--       'finished', 'unknown', 'updated_after_unknown', 'unblocked'));
--   DELETE FROM flyway_schema_history WHERE version = '51';
-- =============================================================================

-- Safe historical target summary for mail; executor payloads remain opaque. Older/ad-hoc runs stay NULL.
ALTER TABLE schedule_runs ADD COLUMN target_ref TEXT;
COMMENT ON COLUMN schedule_runs.target_ref IS 'Target summary frozen from the schedule at run creation; NULL for older or ad-hoc runs';

CREATE TABLE schedule_notification_events (
    id          UUID        PRIMARY KEY,
    schedule_id UUID        NOT NULL REFERENCES schedules(id),
    run_id      UUID        NOT NULL REFERENCES schedule_runs(id),
    event       TEXT        NOT NULL,
    outcome     TEXT        NOT NULL,
    run_state   TEXT        NOT NULL,
    reason      TEXT,
    occurred_at TIMESTAMPTZ NOT NULL,
    sequence    INT         NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_schedule_notification_events_run_outcome UNIQUE (run_id, outcome),
    CONSTRAINT chk_schedule_notification_events_event CHECK (event IN ('start', 'success', 'failure', 'unknown', 'blocked')),
    CONSTRAINT chk_schedule_notification_events_outcome CHECK (
        (event = 'start' AND outcome = 'started')
        OR (event = 'success' AND outcome = 'completed')
        OR (event = 'failure' AND outcome IN ('failed', 'could_not_start', 'cancelled', 'aborted'))
        OR (event = 'unknown' AND outcome IN ('unknown', 'unknown_update'))
        OR (event = 'blocked' AND outcome = 'blocked')),
    CONSTRAINT chk_schedule_notification_events_sequence CHECK (sequence >= 1)
);

CREATE INDEX idx_schedule_notification_events_schedule ON schedule_notification_events (schedule_id);

CREATE TABLE schedule_notification_deliveries (
    id              UUID        PRIMARY KEY,
    event_id        UUID        NOT NULL REFERENCES schedule_notification_events(id),
    recipient       TEXT        NOT NULL,
    state           TEXT        NOT NULL,
    attempts        INT         NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    sending_since   TIMESTAMPTZ,
    last_error_code TEXT,
    message_id      TEXT        NOT NULL,
    sent_at         TIMESTAMPTZ,
    resend_count    INT         NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_schedule_notification_deliveries_event_recipient UNIQUE (event_id, recipient),
    CONSTRAINT uq_schedule_notification_deliveries_message_id UNIQUE (message_id),
    CONSTRAINT chk_schedule_notification_deliveries_state CHECK (state IN ('pending', 'accepted', 'failed', 'uncertain')),
    -- Pending exactly when a next attempt is due; a settled delivery is never claimed.
    CONSTRAINT chk_schedule_notification_deliveries_due CHECK ((state = 'pending') = (next_attempt_at IS NOT NULL)),
    CONSTRAINT chk_schedule_notification_deliveries_sending CHECK (sending_since IS NULL OR state = 'pending'),
    CONSTRAINT chk_schedule_notification_deliveries_accepted CHECK (state <> 'accepted' OR sent_at IS NOT NULL),
    CONSTRAINT chk_schedule_notification_deliveries_failure CHECK (state NOT IN ('failed', 'uncertain') OR last_error_code IS NOT NULL),
    CONSTRAINT chk_schedule_notification_deliveries_counts CHECK (attempts >= 0 AND resend_count >= 0)
);

CREATE INDEX idx_schedule_notification_deliveries_due ON schedule_notification_deliveries (state, next_attempt_at);
CREATE INDEX idx_schedule_notification_deliveries_event ON schedule_notification_deliveries (event_id);

ALTER TABLE schedule_run_events DROP CONSTRAINT chk_schedule_run_events_kind;
ALTER TABLE schedule_run_events ADD CONSTRAINT chk_schedule_run_events_kind CHECK (kind IN (
    'recorded', 'capacity_retry', 'claimed', 'execution_started', 'not_started', 'skipped',
    'finished', 'unknown', 'updated_after_unknown', 'unblocked',
    'notification_enqueued', 'notification_sent', 'notification_failed', 'notification_uncertain'));
