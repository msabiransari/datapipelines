-- scripts/sample-data-trade/ddl/mysql-world-trade.sql — schema of the
-- dp_sample_trade database inside mysql-world-trade.sql.gz (MYSQL engine,
-- reused demo container). Mirror statistics: what each partner REPORTS
-- trading with the USA at the headline (TOTAL) level, to be reconciled
-- against the US-reported Census facts in the DuckDB artifact.

CREATE TABLE comtrade_annual (
  reporter_code INT         NOT NULL,  -- UN M49 numeric
  flow          CHAR(1)     NOT NULL,  -- 'X' exports to USA | 'M' imports from USA, as the REPORTER classifies
  period        CHAR(4)     NOT NULL,  -- 'YYYY'
  value_usd     DECIMAL(20,2),
  PRIMARY KEY (reporter_code, flow, period)
);

CREATE TABLE reporters (
  reporter_code INT PRIMARY KEY,
  reporter_name VARCHAR(100) NOT NULL
);
