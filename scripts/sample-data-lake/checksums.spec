# Which tables the dp-lake manifest fingerprints, in what shape they are published,
# and in what order a reader must stream them to reproduce the checksum. ONE
# authority: manifest.sh builds `tables[]` from this file, verify.sh re-derives
# every row count and content checksum from it, and publish-layout.sh refuses to
# render a tree missing any entry. Two lists would drift and a green verify would
# prove nothing (../sample-data/checksums.spec makes the same argument).
#
# <table>  <format>  <path or glob under the version directory>  <ORDER BY expression>
#
# The ORDER BY must be a TOTAL order, or the stream is not reproducible. The two
# aggregates have a real key (their GROUP BY), so theirs is short. The trip tables
# have NONE — a trip record carries no id and this build invents no surrogate, since
# a row_number() would depend on which months had been transformed when it ran — so
# their order is the full column list; rows that tie are identical in every column
# and the resulting table is the same set either way. (The mobility family makes the
# same argument for trip_id's row_number() order.)
#
# The checksum itself is taken over `SELECT *` in this order, so it covers every
# column, not just the ordering key.
#
# The BYTES of a Parquet file are not the contract; the CONTENTS are. A partitioned
# write does not promise a stable row order inside a file, DuckDB is free to change
# its encoder between versions, and the Iceberg writer stamps a fresh UUID and
# timestamp on every run. What is pinned is what a query sees.
hvfhv_trips          parquet  hvfhv_trips/pickup_date=*/part-*.parquet  pickup_at, dropoff_at, pu_location_id, do_location_id, trip_miles, trip_time_s, base_fare, tips, driver_pay, shared_request, hvfhs_license_num
hvfhv_trips_sample   parquet  hvfhv_trips_sample/part-*.parquet         pickup_at, dropoff_at, pu_location_id, do_location_id, trip_miles, trip_time_s, base_fare, tips, driver_pay, shared_request, hvfhs_license_num
hvfhv_zone_day       parquet  hvfhv_zone_day/part-0.parquet             pickup_date, pu_location_id, company
hvfhs_companies      parquet  hvfhs_companies/part-0.parquet            hvfhs_license_num
hvfhv_trips_iceberg  iceberg  hvfhv_trips_iceberg                       pickup_at, dropoff_at, pu_location_id, do_location_id, trip_miles, trip_time_s, base_fare, tips, driver_pay, shared_request, hvfhs_license_num
