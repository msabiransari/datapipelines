-- scripts/sample-data-trade/ddl/duckdb-us-trade.sql — schema of the
-- us_trade.duckdb artifact (DUCKDB engine, the fourth demo dialect). Column
-- types follow the canonical type system: codes INTEGER, values DECIMAL,
-- period is the month grain 'YYYY-MM' as VARCHAR (a month is not a date;
-- storing it as one invites day-level accidents).

CREATE TABLE trade_monthly (
  flow         VARCHAR     NOT NULL,   -- 'imports' | 'exports'
  period       VARCHAR     NOT NULL,   -- 'YYYY-MM'
  partner_code INTEGER     NOT NULL,
  partner_name VARCHAR     NOT NULL,
  hs_code      VARCHAR     NOT NULL,   -- 6-digit HS code
  hs_desc      VARCHAR,
  value_usd    DECIMAL(18,2),
  PRIMARY KEY (flow, period, partner_code, hs_code)
);

CREATE TABLE partners (
  partner_code INTEGER PRIMARY KEY,
  partner_name VARCHAR NOT NULL
);

CREATE TABLE hs_chapters (
  chapter      VARCHAR PRIMARY KEY,   -- 2-digit HS chapter
  chapter_name VARCHAR NOT NULL
);

CREATE TABLE trade_flow_monthly (
  flow             VARCHAR NOT NULL,
  period           VARCHAR NOT NULL,
  total_value_usd  DECIMAL(20,2),
  hs6_cell_count   BIGINT,
  PRIMARY KEY (flow, period)
);

-- Crosswalk between the Census partner codes used in trade_monthly and the
-- UN M49 numeric codes the Comtrade reconciliation table uses — the join
-- key for the mirror-statistics pipeline. Only the Comtrade reporter set is
-- covered (that is all the reconciliation needs).
CREATE TABLE partner_iso_crosswalk (
  census_code  INTEGER PRIMARY KEY,
  iso_numeric  INTEGER NOT NULL,
  partner_name VARCHAR NOT NULL
);
