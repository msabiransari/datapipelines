"""Write the dp-lake sample table as an Iceberg table, at its FINAL s3:// paths.

Run through the pinned environment, never a system Python:

    ./scripts/sample-data-lake/lib/iceberg-venv.sh run lib/iceberg_write.py \
        --sample-glob 'work/tables/hvfhv_trips_sample/part-*.parquet' \
        --stage       work/iceberg \
        --table-uri   s3://datapipelines-co/sample-data/lake/v1/hvfhv_trips_iceberg

WHY THIS FILE EXISTS AT ALL
---------------------------
089 has to be able to prove the Iceberg path beside the plain-Parquet path, so the
published artifact carries the same rows in both shapes. DuckDB cannot write the
Iceberg one: its iceberg extension writes only through an attached REST catalog
(verified against the pinned v1.5.5 and the extension's docs on 2026-09-07 — see
iceberg-venv.sh), and a public S3 prefix has no catalog service. pyiceberg can,
with a local SQLite catalog we throw away afterwards; the published table is
catalog-free and is opened by its metadata file.

WHY IT WRITES s3:// URIs INTO A LOCAL DIRECTORY
-----------------------------------------------
An Iceberg table records ABSOLUTE locations — in `metadata/*.metadata.json`, in the
snapshot's manifest-list Avro and in each manifest Avro. Build it under a staging
path and it is unreadable once that path is gone. Measured, not assumed
(2026-09-07, DuckDB v1.5.5 + this pyiceberg):

  * table built at <A>, directory copied to <B>, <A> deleted, then
    `iceberg_scan('<B>/metadata/00001-….metadata.json')`
      -> IO Error: Cannot open file "<A>/metadata/snap-….avro": No such file or directory
  * the same read with `allow_moved_paths := true`
      -> IO Error: Cannot open file "<B>/metadata/00001-….metadata.json/metadata/snap-….avro"
         (the flag resolves its argument as a table DIRECTORY, so a metadata-file
          argument is not rescued — it is made worse)
  * the same read while <A> still existed SUCCEEDED, which is the trap: a check run
    before the staging directory is cleaned proves nothing.
  * the Avro manifests are deflate-compressed, so the "just rewrite the prefix in
    the bytes" repair is not available either.

So the writer is told where the table will live and builds it believing that. The
bytes land in --stage; every path inside them is the --table-uri. The only piece of
machinery is a tiny fsspec filesystem registered for the `s3` scheme that maps
`s3://<bucket>/<key>` to `<stage>/<key-under-the-table>`; pyiceberg's FsspecFileIO
hands it the full URI and it opens the local file. Nothing here talks to S3, and
this build has no AWS credentials by design — the owner uploads (README §Publish).

DETERMINISM
-----------
This writer is NOT byte-reproducible and does not claim to be: pyiceberg stamps a
fresh table UUID, snapshot id and `last-updated-ms` on every run, and names data
files after a fresh UUID. What is reproducible is the CONTENT, and that is what
manifest.json pins per table (row count + a checksum of the ordered stream) —
the same contents-not-bytes contract the mobility family states for pg_dump.
Every published OBJECT is additionally pinned by SHA-256, so a consumer can still
prove it received the bytes that were published.
"""

from __future__ import annotations

import argparse
import glob
import json
import os
import shutil
import sys
from urllib.parse import urlparse

import pyarrow.parquet as pq
from fsspec.implementations.local import LocalFileSystem

import pyiceberg.io.fsspec as pyiceberg_fsspec
from pyiceberg.catalog.sql import SqlCatalog

#: Set once by main() before the catalog is built; read by StagedObjectStore, which
#: fsspec instantiates with no arguments of ours.
_STAGE_ROOT: str = ""
_BUCKET: str = ""


class StagedObjectStore(LocalFileSystem):
    """`s3://<bucket>/<key>` -> `<stage>/<key>`, on the local disk.

    Registered in place of pyiceberg's real S3 filesystem so that every location the
    writer records is the URI the object will actually have once uploaded, while the
    bytes are written where the build can checksum them.
    """

    protocol = ("s3",)

    def __init__(self, **_kwargs: object) -> None:
        super().__init__(auto_mkdir=True)

    @classmethod
    def _strip_protocol(cls, path: object) -> str:
        if isinstance(path, str) and path.startswith("s3://"):
            parsed = urlparse(path)
            if parsed.netloc != _BUCKET:
                raise ValueError(
                    f"refusing to map {path!r}: this build stages only bucket {_BUCKET!r}"
                )
            return os.path.join(_STAGE_ROOT, parsed.path.lstrip("/"))
        return LocalFileSystem._strip_protocol(path)


def main() -> int:
    global _STAGE_ROOT, _BUCKET

    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--sample-glob", required=True, help="the Parquet files to load, in name order")
    ap.add_argument("--stage", required=True, help="local directory the bucket key space is rooted at")
    ap.add_argument("--table-uri", required=True, help="s3:// URI the table will be published at")
    args = ap.parse_args()

    table_uri = args.table_uri.rstrip("/")
    parsed = urlparse(table_uri)
    if parsed.scheme != "s3" or not parsed.netloc:
        sys.exit(f"iceberg_write: --table-uri must be an s3://bucket/key URI, got {table_uri!r}")
    _BUCKET = parsed.netloc
    key = parsed.path.lstrip("/")

    # The stage root is the BUCKET root, so that the key space and the directory tree
    # are the same shape; the table therefore lands at <stage>/<key>. publish-layout.sh
    # copies exactly that directory.
    _STAGE_ROOT = os.path.abspath(args.stage)
    table_dir = os.path.join(_STAGE_ROOT, key)
    if os.path.exists(table_dir):
        # A rebuild must not append to the previous run's snapshot: two appends make a
        # table with two snapshots whose row count is double, and the manifest would
        # faithfully record the wrong number.
        shutil.rmtree(table_dir)

    files = sorted(glob.glob(args.sample_glob))
    if not files:
        sys.exit(f"iceberg_write: --sample-glob matched no files: {args.sample_glob}")

    pyiceberg_fsspec.SCHEME_TO_FS["s3"] = lambda _properties: StagedObjectStore()

    catalog_db = os.path.join(_STAGE_ROOT, "_catalog-throwaway.db")
    if os.path.exists(catalog_db):
        os.remove(catalog_db)
    catalog = SqlCatalog(
        "lake",
        **{
            "uri": f"sqlite:///{catalog_db}",
            "warehouse": table_uri.rsplit("/", 1)[0],
            "py-io-impl": "pyiceberg.io.fsspec.FsspecFileIO",
        },
    )
    catalog.create_namespace_if_not_exists("lake")

    schema = pq.read_schema(files[0])
    table = catalog.create_table("lake.hvfhv_trips_iceberg", schema=schema, location=table_uri)

    # One append per month rather than one over the whole sample: the full sample does
    # not have to fit in memory, and each month's file becomes its own data file, which
    # is what a reader wants for pruning. The appends are in file-name (= month) order.
    rows = 0
    for path in files:
        batch = pq.read_table(path)
        if batch.schema != schema:
            sys.exit(
                f"iceberg_write: {path} has a different schema than {files[0]} — "
                "the monthly sample files must be written by one transform.sh"
            )
        table.append(batch)
        rows += batch.num_rows
        print(f"iceberg_write: appended {batch.num_rows} rows from {os.path.basename(path)}", file=sys.stderr)

    # The catalog was scaffolding. Removing it here (not "later, by hand") is what makes
    # the published table catalog-free by construction: nothing can accidentally ship a
    # SQLite file that names local paths.
    os.remove(catalog_db)

    metadata_location = table.metadata_location
    # A version hint lets a reader that was given only the table DIRECTORY find the
    # current metadata file; pyiceberg's SqlCatalog does not write one, because a
    # catalog-managed table never needs it and ours will not have a catalog.
    version_hint = os.path.join(table_dir, "metadata", "version-hint.text")
    current = os.path.basename(urlparse(metadata_location).path)
    with open(version_hint, "w", encoding="utf-8") as fh:
        fh.write(current.split("-", 1)[0].lstrip("0") or "0")
        fh.write("\n")

    summary = {
        "table_uri": table_uri,
        "metadata_location": metadata_location,
        "row_count": rows,
        "data_files": len(files),
        "version_hint": f"{table_uri}/metadata/version-hint.text",
    }
    print(json.dumps(summary, indent=2))
    print(
        f"iceberg_write: wrote {rows} rows in {len(files)} data file(s); "
        f"current metadata is {metadata_location}",
        file=sys.stderr,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
