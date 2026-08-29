# Which tables the manifest fingerprints, and in what order the engine must
# stream them. One authority for the build (load-and-dump.sh, which records the
# numbers) and the verifier (verify.sh, which re-derives them from the published
# artifacts) — two lists would drift and a green verify would prove nothing.
#
# <engine>  <table>  <order-by expression>
#
# The order-by must be a TOTAL order, or the stream is not reproducible. Every
# entry below is the table's primary key.
postgres  trips           trip_id
postgres  trips_daily     pickup_date
postgres  trips_monthly   month_start, pu_location_id
mysql     stations        station_id
mysql     observations    station_id, obs_date, element
sqlite    zones           location_id
sqlite    rate_codes      rate_code_id
sqlite    payment_types   payment_type_id
sqlite    calendar        cal_date
