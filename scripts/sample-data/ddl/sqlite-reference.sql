-- nyc_reference.db (SQLITE) — the small, slow-moving lookup tables.
--
-- Shipped as the file itself (design §4): SQLite's file format is stable across
-- versions, which is exactly why it, and not DuckDB, is the embedded engine in
-- v1 (D2).
--
-- Column types are spelled with explicit affinities. SQLite is dynamically
-- typed, so these are documentation for the reader and affinity hints for the
-- driver; the values are made canonical by the loader, not by the declaration.

CREATE TABLE zones (
    location_id  INTEGER NOT NULL PRIMARY KEY,
    borough      TEXT    NOT NULL,
    zone         TEXT    NOT NULL,
    service_zone TEXT    NOT NULL
);

CREATE TABLE rate_codes (
    rate_code_id INTEGER NOT NULL PRIMARY KEY,
    description  TEXT    NOT NULL
);

CREATE TABLE payment_types (
    payment_type_id INTEGER NOT NULL PRIMARY KEY,
    description     TEXT    NOT NULL
);

-- One row per day of the pinned window. `cal_date` is TEXT in ISO-8601 form:
-- SQLite has no DATE type, and ISO-8601 text is the one representation that
-- sorts, compares and joins correctly against the DATE columns of the other
-- two engines after staging.
CREATE TABLE calendar (
    cal_date     TEXT    NOT NULL PRIMARY KEY,
    day_of_week  INTEGER NOT NULL,
    is_weekend   INTEGER NOT NULL,
    is_holiday   INTEGER NOT NULL,
    -- Empty string, never NULL, on a non-holiday: the CSV loader imports an
    -- empty field as '' and a column that is sometimes '' and sometimes NULL is
    -- the kind of thing a demo query gets wrong in a way nobody notices.
    holiday_name TEXT    NOT NULL DEFAULT ''
);
