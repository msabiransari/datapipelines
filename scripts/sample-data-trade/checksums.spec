# Which tables the trade/v1 manifest fingerprints, and in what order the
# engine must stream them — same contract as ../sample-data/checksums.spec:
# one authority shared by load-and-dump.sh (records) and verify.sh
# (re-derives). The order-by must be a TOTAL order; every entry below is the
# table's primary key.
duckdb  trade_monthly       flow, period, partner_code, hs_code
duckdb  partners            partner_code
duckdb  hs_chapters         chapter
duckdb  trade_flow_monthly  flow, period
duckdb  partner_iso_crosswalk  census_code
mysql   comtrade_annual     reporter_code, flow, period
mysql   reporters           reporter_code
sqlite  klines_1h           symbol, open_ts
sqlite  symbols             symbol
