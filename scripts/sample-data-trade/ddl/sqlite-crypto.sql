-- scripts/sample-data-trade/ddl/sqlite-crypto.sql — schema of the
-- crypto_market.db artifact (SQLITE engine). Hourly klines for the pinned
-- symbols and window; timestamps are ISO-8601 UTC text (SQLite has no
-- timestamp type; the demo datasource reads it as STRING and casts in SQL).

CREATE TABLE klines_1h (
  symbol       TEXT    NOT NULL,
  month        TEXT    NOT NULL,   -- 'YYYY-MM', derived from open_ts at build
  open_ts      TEXT    NOT NULL,   -- ISO-8601 UTC, hour-aligned
  open_price   REAL,
  high_price   REAL,
  low_price    REAL,
  close_price  REAL,
  base_volume  REAL,
  quote_volume REAL,
  trade_count  INTEGER,
  PRIMARY KEY (symbol, open_ts)
);

CREATE TABLE symbols (
  symbol TEXT PRIMARY KEY,
  base   TEXT NOT NULL,
  quote  TEXT NOT NULL
);
