-- dp_sample_weather (MYSQL) — NOAA GHCN-Daily observations for the pinned
-- NYC-area stations, melted from the wide per-day source rows into the long
-- element form (design §4.1 stage 2).
--
-- utf8mb4 explicitly: station NAMEs come from NCEI and are not guaranteed
-- ASCII, and a latin1 default would corrupt them silently at load.

CREATE TABLE stations (
    station_id  VARCHAR(16)   NOT NULL,
    name        VARCHAR(128)  NOT NULL,
    latitude    DECIMAL(9,4)  NOT NULL,
    longitude   DECIMAL(9,4)  NOT NULL,
    elevation_m DECIMAL(7,1),
    PRIMARY KEY (station_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Long form, one row per (station, day, element). `value` is in the STANDARD
-- unit named by `unit`, not GHCN's native tenths — the conversion happens once,
-- at build time, so no demo pipeline has to know that PRCP arrives in tenths of
-- a millimetre and SNOW does not.
CREATE TABLE observations (
    station_id  VARCHAR(16)   NOT NULL,
    obs_date    DATE          NOT NULL,
    element     VARCHAR(8)    NOT NULL,
    value       DECIMAL(10,2) NOT NULL,
    unit        VARCHAR(8)    NOT NULL,
    PRIMARY KEY (station_id, obs_date, element),
    CONSTRAINT fk_observations_station FOREIGN KEY (station_id) REFERENCES stations (station_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_observations_date_element ON observations (obs_date, element);
