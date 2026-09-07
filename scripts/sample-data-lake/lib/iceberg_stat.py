"""Read a published Iceberg table THROUGH ITS OWN METADATA and report its shape.

    lib/iceberg-venv.sh run lib/iceberg_stat.py \
        --stage work/publish/lake/v1 --table-uri s3://…/lake/v1/hvfhv_trips_iceberg \
        --order '<checksums.spec ORDER BY>' --duckdb .tools/duckdb/duckdb-…

Prints one line to stdout:  <row_count> <sha256> <metadata_location>

WHY NOT JUST GLOB THE DATA DIRECTORY. The point of shipping an Iceberg table beside
the Parquet one is that a reader can open it as a TABLE. A fingerprint taken by
globbing `<table>/data/*.parquet` would be green even if the metadata JSON, the
manifest list or a manifest were corrupt, truncated or pointed at the wrong files —
i.e. green in exactly the state that makes the table useless. So the data files are
enumerated by asking Iceberg: load the metadata, walk the current snapshot's
manifests, and hash the files the table SAYS it is made of.

The hashing itself is handed to the pinned DuckDB CLI, so the Iceberg table's
checksum is computed by the same code path, in the same rendering, as every Parquet
table's — otherwise the equality assertion in manifest.sh (`sample == iceberg`)
would be comparing two different encodings and could never hold.

The `s3://` locations recorded in the table are mapped back onto --stage by the same
one-line rule the writer used; see iceberg_write.py for why the table records
absolute URIs at all.
"""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
# (urlparse is no longer needed: the prefix is stripped whole, never parsed apart)

from fsspec.implementations.local import LocalFileSystem

import pyiceberg.io.fsspec as pyiceberg_fsspec
from pyiceberg.table import StaticTable

#: The published version prefix (`s3://bucket/sample-data/lake/v1`) and the local
#: directory that holds it. Set by main() before any read.
_PREFIX = ""
_STAGE = ""


class StagedObjectStore(LocalFileSystem):
    """`<publish prefix>/<rest>` -> `<stage>/<rest>`, on the local disk.

    Deliberately NOT "bucket root maps to some parent of --stage": the rendered tree
    is `work/publish/lake/<version>/`, which is not the shape of the bucket KEY
    (`sample-data/lake/<version>/`), and deriving one from the other by counting path
    segments is how a reader ends up looking in `work/publish/sample-data/lake/…` and
    reporting a missing file (it did, before this was written this way). The prefix is
    passed in whole and stripped whole.
    """

    protocol = ("s3",)

    def __init__(self, **_kwargs: object) -> None:
        super().__init__(auto_mkdir=False)

    @classmethod
    def _strip_protocol(cls, path: object) -> str:
        if isinstance(path, str) and path.startswith("s3://"):
            if not path.startswith(_PREFIX + "/"):
                raise ValueError(
                    f"refusing to map {path!r}: it is not under the published prefix {_PREFIX!r}. "
                    "An Iceberg table that names a location outside its own version directory is "
                    "unreadable wherever it is published."
                )
            return os.path.join(_STAGE, path[len(_PREFIX) + 1 :])
        return LocalFileSystem._strip_protocol(path)


def main() -> int:
    global _PREFIX, _STAGE

    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--stage", required=True, help="the rendered VERSION directory (…/lake/<version>)")
    ap.add_argument("--prefix", required=True, help="the s3:// prefix that directory is published at")
    ap.add_argument("--table-uri", required=True)
    ap.add_argument("--order", required=True, help="the checksums.spec ORDER BY for this table")
    ap.add_argument("--duckdb", required=True, help="path to the pinned DuckDB CLI")
    args = ap.parse_args()

    _PREFIX = args.prefix.rstrip("/")
    _STAGE = os.path.abspath(args.stage)
    table_uri = args.table_uri.rstrip("/")
    if not table_uri.startswith(_PREFIX + "/"):
        sys.exit(f"iceberg_stat: --table-uri {table_uri!r} is not under --prefix {_PREFIX!r}")
    table_rel = table_uri[len(_PREFIX) + 1 :]

    pyiceberg_fsspec.SCHEME_TO_FS["s3"] = lambda _properties: StagedObjectStore()

    metadata_dir = os.path.join(_STAGE, table_rel, "metadata")
    candidates = sorted(f for f in os.listdir(metadata_dir) if f.endswith(".metadata.json"))
    if not candidates:
        sys.exit(f"iceberg_stat: no *.metadata.json under {metadata_dir}")
    # The highest-numbered metadata file is the current one; version-hint.text, which
    # the writer also publishes, names the same number. Both are checked so a
    # disagreement is a failure rather than a coin toss.
    current = candidates[-1]
    hint_path = os.path.join(metadata_dir, "version-hint.text")
    if os.path.exists(hint_path):
        hint = open(hint_path, encoding="utf-8").read().strip()
        if current.split("-", 1)[0].lstrip("0").lstrip() not in (hint, ""):
            sys.exit(
                f"iceberg_stat: version-hint.text says {hint!r} but the highest metadata "
                f"file is {current!r} — the published table disagrees with itself"
            )

    metadata_location = f"{table_uri}/metadata/{current}"
    # py-io-impl is NOT optional here. StaticTable defaults to pyiceberg's pyarrow
    # FileIO, which resolves an s3:// location by actually talking to S3 — this read
    # went out to the real bucket and came back ACCESS_DENIED before the property was
    # passed. The fsspec IO is the one whose `s3` scheme this file has replaced.
    table = StaticTable.from_metadata(
        metadata_location, properties={"py-io-impl": "pyiceberg.io.fsspec.FsspecFileIO"}
    )

    data_files = []
    snapshot = table.current_snapshot()
    if snapshot is None:
        sys.exit("iceberg_stat: the table has no current snapshot")
    for manifest in snapshot.manifests(table.io):
        for entry in manifest.fetch_manifest_entry(table.io, discard_deleted=True):
            data_files.append(StagedObjectStore._strip_protocol(entry.data_file.file_path))
    if not data_files:
        sys.exit("iceberg_stat: the current snapshot lists no data files")
    for path in data_files:
        if not os.path.exists(path):
            sys.exit(f"iceberg_stat: the table names a data file that is not in the published tree: {path}")

    files_sql = ", ".join("'" + p.replace("'", "''") + "'" for p in sorted(data_files))
    count_sql = f"SELECT count(*) FROM read_parquet([{files_sql}], union_by_name = true);"
    stream_sql = f"SELECT * FROM read_parquet([{files_sql}], union_by_name = true) ORDER BY {args.order};"

    def duck(sql: str) -> bytes:
        return subprocess.run(
            [args.duckdb, "-noheader", "-list", "-c", sql],
            check=True, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
        ).stdout

    import hashlib

    rows = duck(count_sql).decode().strip()
    digest = hashlib.sha256(duck(stream_sql)).hexdigest()
    print(f"{rows} {digest} {metadata_location}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
