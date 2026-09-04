-- scripts/sample-data-trade/ddl/sqlite-fx.sql — schema of the fx_rates.db
-- artifact (SQLITE engine): Federal Reserve H.10 daily noon buying rates and
-- their G.5 monthly averages, for the five currencies of the partners this
-- family already reconciles.
--
-- BOTH DIRECTIONS ARE STORED. `per_usd` is always foreign-currency-per-USD and
-- `usd_per` always its inverse, whichever way the source quotes the pair (the
-- euro series is published USD-per-EUR and is inverted at build). A template
-- that needs either direction reads a column; none of them divides, and none
-- of them has to know which convention the Board publishes.
--
-- Source: Board of Governors of the Federal Reserve System, H.10 "Foreign
-- Exchange Rates" (daily) and its G.5 monthly-average companion, via the Data
-- Download Program. A work of the US Government: no copyright.

CREATE TABLE fx_daily (
  rate_date TEXT NOT NULL,   -- ISO-8601 date, business days only
  currency  TEXT NOT NULL,   -- ISO 4217
  per_usd   REAL NOT NULL,   -- units of `currency` per 1 USD
  usd_per   REAL NOT NULL,   -- USD per 1 unit of `currency`
  PRIMARY KEY (rate_date, currency)
);

CREATE TABLE fx_monthly (
  month    TEXT NOT NULL,    -- 'YYYY-MM' (a month is not a date)
  currency TEXT NOT NULL,
  per_usd  REAL NOT NULL,
  usd_per  REAL NOT NULL,
  PRIMARY KEY (month, currency)
);

CREATE TABLE currencies (
  currency         TEXT PRIMARY KEY,
  name             TEXT NOT NULL,
  series_daily     TEXT NOT NULL,   -- H.10 unique identifier, daily
  series_monthly   TEXT NOT NULL,   -- H.10/G.5 unique identifier, monthly
  quote_convention TEXT NOT NULL    -- how the BOARD publishes it, before inversion
);

-- Census partner -> currency. It lives here, with the rates, so the fx node of
-- a pipeline can emit partner-keyed rows and the join "Census partner ->
-- currency" is DATA rather than a CASE expression in someone's SQL. Only the
-- five reconciled partners are covered — that is all any shipped example needs.
CREATE TABLE partner_currency (
  census_code  INTEGER PRIMARY KEY,   -- the code used by trade_monthly.partner_code
  partner      TEXT NOT NULL,         -- ISO 3166-1 alpha-2, the value a `partner` parameter takes
  partner_name TEXT NOT NULL,
  currency     TEXT NOT NULL
);
