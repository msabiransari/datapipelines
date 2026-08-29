-- dp_sample_trips (POSTGRES) — NYC TLC yellow-taxi sample.
-- Column types are the canonical type system's Postgres mappings
-- (docs/type-system.md §5): NUMERIC(p,s) for money and distance so a demo
-- pipeline's aggregates are exact, never float-drifted.
--
-- No FOREIGN KEYs to the reference tables: those live in a DIFFERENT engine
-- (SQLITE nyc_reference.db, design D3). The joins that matter here are
-- cross-datasource joins staged in tempdb — which is the point of the demo.

CREATE TABLE trips (
    trip_id               BIGINT       NOT NULL,
    vendor_id             SMALLINT,
    pickup_ts             TIMESTAMP    NOT NULL,
    dropoff_ts            TIMESTAMP    NOT NULL,
    -- Denormalized from pickup_ts. It is the join key to weather and calendar,
    -- and a demo that needs a cast to join reads badly.
    pickup_date           DATE         NOT NULL,
    passenger_count       SMALLINT,
    trip_distance_mi      NUMERIC(9,2),
    rate_code_id          SMALLINT,
    store_and_fwd         BOOLEAN,
    pu_location_id        SMALLINT     NOT NULL,
    do_location_id        SMALLINT     NOT NULL,
    payment_type_id       SMALLINT,
    fare_amount           NUMERIC(10,2),
    extra                 NUMERIC(10,2),
    mta_tax               NUMERIC(10,2),
    tip_amount            NUMERIC(10,2),
    tolls_amount          NUMERIC(10,2),
    improvement_surcharge NUMERIC(10,2),
    congestion_surcharge  NUMERIC(10,2),
    airport_fee           NUMERIC(10,2),
    total_amount          NUMERIC(10,2),
    CONSTRAINT pk_trips PRIMARY KEY (trip_id)
);

-- Pre-aggregated rollups. They exist so the shipped example pipelines answer a
-- real question in seconds against a 5M-row table on a demo box, and so an
-- agent exploring the schema finds an obvious starting point.
CREATE TABLE trips_daily (
    pickup_date           DATE         NOT NULL,
    trip_count            BIGINT       NOT NULL,
    total_revenue         NUMERIC(14,2) NOT NULL,
    total_distance_mi     NUMERIC(14,2) NOT NULL,
    total_tips            NUMERIC(14,2) NOT NULL,
    CONSTRAINT pk_trips_daily PRIMARY KEY (pickup_date)
);

CREATE TABLE trips_monthly (
    month_start           DATE         NOT NULL,
    pu_location_id        SMALLINT     NOT NULL,
    trip_count            BIGINT       NOT NULL,
    total_revenue         NUMERIC(14,2) NOT NULL,
    total_distance_mi     NUMERIC(14,2) NOT NULL,
    total_tips            NUMERIC(14,2) NOT NULL,
    CONSTRAINT pk_trips_monthly PRIMARY KEY (month_start, pu_location_id)
);

CREATE INDEX idx_trips_pickup_date ON trips (pickup_date);
CREATE INDEX idx_trips_pu_location ON trips (pu_location_id);
