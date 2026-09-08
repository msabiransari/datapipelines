#!/bin/sh
# place-lake.sh — the LAKE family's bootstrap-file stage (089 §E). It is NOT a
# loader, and that is the whole point of the family: nothing is downloaded or
# restored — the app reads the published Parquet/Iceberg objects IN PLACE over
# HTTPS at query time (environments.md §5). The only "load" is placing the
# family's two REPO files on the shared sample volume before the app boots:
#
#   bootstrap-datasources-lake.yml  from this directory (deployment facts —
#                                   exactly why load.sh treats it as repo
#                                   content, not a published artifact)
#   examples-lake.json              from scripts/sample-data/content, the file
#                                   the content test validates (never a copy)
#
# Both travel on the sample-data volume so the app keeps exactly ONE mount
# (load.sh's own note on the same choice). Copy-then-rename, like load.sh: a
# reader never sees half a file, and 0444 because the app only ever reads.
set -eu

SAMPLE_DIR="${SAMPLE_DIR:-/srv/sample}"

place() { # <source> <placed-name>
  cp "$1" "$SAMPLE_DIR/$2.part"
  mv "$SAMPLE_DIR/$2.part" "$SAMPLE_DIR/$2"
  chmod 0444 "$SAMPLE_DIR/$2"
  echo "place-lake: placed $SAMPLE_DIR/$2"
}

place /opt/sample-data/bootstrap-datasources-lake.yml bootstrap-datasources-lake.yml
place /opt/lake-content/examples-lake.json examples-lake.json
