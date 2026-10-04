-- #442a: settings only; no mail transport exists yet.
ALTER TABLE schedules
    ADD COLUMN notification_recipients TEXT[] NOT NULL DEFAULT '{}',
    ADD COLUMN notification_events TEXT[] NOT NULL DEFAULT '{failure,unknown,blocked}',
    ADD CONSTRAINT chk_schedules_notification_events
        CHECK (notification_events <@ ARRAY['start','success','failure','unknown','blocked']::TEXT[]);
