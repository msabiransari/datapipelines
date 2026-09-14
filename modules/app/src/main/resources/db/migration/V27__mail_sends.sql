-- =============================================================================
-- V27 — mail_sends: the claim row behind every notice the product sends (137)
--
-- Authority: the 2026-09-14 owner rulings (welcome mail with the one-time
-- password; "New user" notice to sys-ops); recorded in metadata-db.md §4.19,
-- auth.md §5A.8.
--
-- One row per MESSAGE IDENTITY — (user, kind, act) — inserted BEFORE the send
-- with ON CONFLICT DO NOTHING: whoever inserts the row sends; a retry, a
-- double-submit or a second instance finds it and does not. The welcome mail
-- carries a password and must never go twice, and this table is what makes
-- that a database fact rather than a hope. `act_id` is the user's own id for
-- the once-per-user kinds (`welcome`, `new_user`) and a fresh id per reset for
-- `password_reset` — two resets are two credentials and two messages.
--
-- The row proves an ATTEMPT, not a delivery: `sent_at`/`message_id` land when
-- the transport accepted the message, `error` when it did not. Rows are never
-- cleaned up (they are the record the admin screen and an operator read).
-- =============================================================================

CREATE TABLE mail_sends (
    id          UUID        PRIMARY KEY,
    user_id     UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind        TEXT        NOT NULL,
    act_id      UUID        NOT NULL,
    recipient   TEXT        NOT NULL,
    claimed_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    sent_at     TIMESTAMPTZ,
    message_id  TEXT,
    error       TEXT,
    CONSTRAINT uq_mail_sends_message UNIQUE (user_id, kind, act_id),
    CONSTRAINT chk_mail_sends_kind CHECK (kind IN ('welcome', 'password_reset', 'new_user'))
);
