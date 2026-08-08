# Type System Specification

**Status:** v1.1 (frozen contract — additive-only changes after this point)
**Owner:** datapipelines.co core
**Depends on:** none (foundational spec — other specs depend on this)
**Last updated:** 2026-08-07

---

## 1. Purpose

The Type System is the **API contract** between datapipelines.co and every client — whether that client is an agentic tool (Claude/GLM/Copilot) calling via MCP, a .NET service calling the REST API, a browser dashboard rendering results, or another pipeline reading this pipeline's output.

It defines:

1. A **canonical set of logical types** that represent the union of what any supported source database can produce.
2. **Wire encoding rules** for each logical type when serialized to JSON.
3. **Per-dialect source-to-canonical mappings** for every supported database (PG, Oracle, MSSQL, MySQL, H2, DuckDB, SQLite).
4. **H2 staging type mappings** for the in-memory staging layer.
5. The **schema envelope structure** that travels with every result set.

The canonical types are deliberately small (11 types) and **versioned with an additive-only stability promise** ([§9](#9-stability-promise)). Clients build against this contract; churn breaks them.

---

## 2. Design Principles

1. **Generic over specialized.** Collapse types whose distinctions are academic for the analytics/federated-query use case (e.g., REAL/DOUBLE collapse into DECIMAL with their representable precision).
2. **Type name is the wire contract.** The presence of `BIG` in a type name (`BIGINTEGER`, `BIGDECIMAL`) signals "serializes as JSON string because the value space exceeds IEEE 754 double safe range." Clients switching on type name know what to expect on the wire without consulting additional metadata.
3. **Lossless where loss matters, pragmatic elsewhere.** Numeric precision is preserved for types that exceed IEEE 754 (BIG*). Approximate numerics (REAL/DOUBLE) collapse to DECIMAL with their representable precision because they were never exact to begin with — false precision is not a virtue.
4. **Mapping by type and precision, never by value.** A DECIMAL(18,2) column serializes as BIGDECIMAL → string, even if every actual value would fit in a double. Wire format is stable per-column, declared once in the schema, joins and parsers do not break.
5. **Source-timezone normalization.** All timestamp-bearing types normalize to UTC on ingest. The canonical type system has no notion of "TIMESTAMP WITH source TIME ZONE" — UTC is the canonical zone.
6. **Additive-only evolution.** Types and rules never removed or renamed; new types added under new version bumps. Clients coded against v1 will continue to work against vN. The full promise is stated in [§9 Stability Promise](#9-stability-promise); its client-side counterpart is the unknown-field rule in [§7.1](#71-column-descriptor-json-schema) — clients MUST ignore fields they do not recognize.

---

## 3. Canonical Types (v1)

The canonical type set is **11 types**.

| LogicalType | Wire | Description | Boundary |
|---|---|---|---|
| `NULL` | `null` | Column contains only NULL values; type could not be inferred. | — |
| `BOOLEAN` | `boolean` | Two-valued logic: `true` / `false` / `null`. | — |
| `INTEGER` | `number` | Exact integer fitting in int32 (≤ 2^31 − 1, ~2.1 × 10^9). | int32 and smaller |
| `BIGINTEGER` | `string` | Exact integer up to int64 (≤ 2^63 − 1, ~9.2 × 10^18). Exceeds IEEE 754 double safe integer range (2^53 − 1). | int64 |
| `DECIMAL(p, s?)` | `number` | Exact numeric with precision ≤ 15. Scale is required for exact-numeric origins, omitted for approximate-numeric origins. | precision ≤ 15 |
| `BIGDECIMAL(p, s)` | `string` | Exact numeric with precision > 15. Scale always declared. Precision is **omitted** when the source numeric is unsized (unbounded precision — see [§4](#4-precision-and-scale-semantics)). | precision > 15 or unbounded |
| `STRING` | `string` | Variable-length text. Includes source JSON/JSONB, XML, enums, UUIDs, intervals, geospatial WKT, and any type without a clean canonical mapping. | — |
| `BINARY` | `string` (base64) | Variable-length bytes. | — |
| `DATE` | `string` (ISO 8601 date) | Calendar date, no time component. | — |
| `TIME` | `string` (ISO 8601 time) | Time of day, no date component. No timezone (timezone-of-day is a non-concept). | — |
| `TIMESTAMP` | `string` (ISO 8601 datetime, UTC) | Date and time, normalized to UTC on ingest. Always carries `Z` suffix. | — |

### 3.1 Wire encoding summary

```
number    → INTEGER, DECIMAL(p,s) where p ≤ 15
string    → BIGINTEGER, BIGDECIMAL(p,s) where p > 15, STRING, BINARY, DATE, TIME, TIMESTAMP
boolean   → BOOLEAN
null      → NULL
```

### 3.2 Why INTEGER/BIGINTEGER are split, not collapsed

JS `Number.MAX_SAFE_INTEGER` = 2^53 − 1. Int32 max = 2^31 − 1. Int64 max = 2^63 − 1.

- Integer types whose entire value space fits in double-safe range (≤ 2^53 − 1) serialize as JSON number.
- Integer types whose value space exceeds double-safe range (> 2^53 − 1) serialize as JSON string.

In practice, no source database has a type in the 2^31 to 2^53 range — they have either int32 or int64. So the boundary collapses cleanly: int32-and-smaller → INTEGER (number); int64 → BIGINTEGER (string).

### 3.3 Why DECIMAL/BIGDECIMAL are split at precision 15

IEEE 754 double holds ~15–17 significant decimal digits. Conservative threshold is 15 (round down for safety margin).

- DECIMAL with precision ≤ 15 → all values in the column's value space are losslessly representable as double → JSON number.
- DECIMAL with precision > 15 → some values lose precision in double → JSON string.

### 3.4 Why REAL/DOUBLE collapse into DECIMAL

Approximate numerics (REAL/FLOAT/DOUBLE) are IEEE 754 floats — by definition not exact. Treating them as DECIMAL with their representable precision is honest:

- REAL (float32, ~7 sig digits) → `DECIMAL(7)` (no scale — scale is meaningless for approximate)
- DOUBLE (float64, ~15 sig digits) → `DECIMAL(15)` (no scale)

The schema marks these by **omitting the scale field**: `{type: "DECIMAL", precision: 15}`. Exact-numeric DECIMALs always include scale: `{type: "DECIMAL", precision: 15, scale: 4}`.

This collapses the type system (no separate REAL/DOUBLE) without losing meaningful information — clients that need to know "this was approximate" infer it from the absent scale field.

### 3.5 Egress serialization rules (normative)

These rules fix the exact bytes a client receives. They apply to every egress path uniformly — SSE `data_ready` payloads, the REST result cursor, and MCP tool results.

1. **`TIMESTAMP`** — ISO 8601 with a literal `Z` suffix and **exactly 6 fractional digits** (microseconds), zero-padded: `"2026-08-05T19:30:00.123456Z"`, `"2026-08-05T19:30:00.000000Z"`. Never fewer digits, never more; sub-microsecond source precision is truncated (not rounded up) at ingest. Fixed width means clients can parse and sort lexicographically.
2. **`TIME`** — ISO 8601 time-of-day with **exactly 6 fractional digits**, zero-padded, and **no** zone designator: `"14:30:00.123456"`, `"00:00:00.000000"`.
3. **`DATE`** — ISO 8601 calendar date, no time component, no zone: `"2026-08-05"`.
4. **`BINARY`** — **standard** base64 as defined by [RFC 4648 §4](https://www.rfc-editor.org/rfc/rfc4648#section-4), **with** `=` padding. The URL-safe alphabet (RFC 4648 §5, `-`/`_`) is **not** used, and the encoded string carries no line breaks and no `data:` prefix.
5. **`BIGINTEGER` / `BIGDECIMAL`** — JSON string holding the plain decimal representation; no exponent notation, no thousands separators. `BIGDECIMAL` preserves the source's trailing zeros to its declared scale (`"12345.60"`, not `"12345.6"`).
6. **`NULL` values** — a NULL in any column serializes as JSON `null`, regardless of the column's canonical type.

---

## 4. Precision and Scale Semantics

| Source kind | Precision | Scale | Example schema entry |
|---|---|---|---|
| Exact numeric (`NUMERIC(p,s)`, `DECIMAL(p,s)`) | from source metadata | from source metadata | `{"type": "DECIMAL", "precision": 12, "scale": 2}` (p ≤ 15) or `{"type": "BIGDECIMAL", "precision": 20, "scale": 4}` (p > 15) |
| Approximate numeric (`REAL`, `FLOAT`, `DOUBLE`) | fixed by source bit-width (7 or 15) | **omitted** | `{"type": "DECIMAL", "precision": 7}` or `{"type": "DECIMAL", "precision": 15}` |
| Money/currency (e.g., PG `money`, MSSQL `money`) | from source (PG money = 19, MSSQL money = 19, smallmoney = 10) | from source (PG = 2, MSSQL = 4) | `{"type": "BIGDECIMAL", "precision": 19, "scale": 2}` |
| Unsized exact numeric with a bounded source default (`NUMBER` in Oracle with no precision) | the dialect's documented default (Oracle = 38) | 0 | `{"type": "BIGDECIMAL", "precision": 38, "scale": 0}` |
| Unsized exact numeric with unbounded source precision (`numeric` / `decimal` in PG with no precision) | **omitted** (= unbounded) | 0 | `{"type": "BIGDECIMAL", "scale": 0}` |

**Omitted precision on BIGDECIMAL means unbounded (normative).** There is exactly **one** rule for an unsized source numeric whose dialect imposes no precision ceiling (today: PostgreSQL `numeric`/`decimal` declared without precision): the envelope reports **`BIGDECIMAL` with the `precision` field omitted**. Omitted precision is normative shorthand for "the source declares no precision limit; assume unbounded."

- The envelope never reports a synthetic ceiling for this case — neither PostgreSQL's internal maximum digit count nor another dialect's default precision may be substituted. A fabricated bound would be a lie about the source column and would break clients that size their local decimal buffers from it.
- `scale` is still declared (`0`, as the driver reports it), so the BIGDECIMAL wire contract — a JSON string carrying the exact decimal — is unchanged.
- Clients that need a bound must impose their own (or `CAST` in the source query). Staging applies its own ceiling separately: see the H2 overflow policy in [§6](#6-h2-staging-type-mapping-canonical--h2).
- Dialects that *do* define a default precision for their unsized numeric (Oracle `NUMBER` → 38) report that default; they are not affected by this rule.

### 4.1 Why scale is omitted for approximate numerics

Approximate numerics have variable scale per value: `3.14` and `6.022e23` are both valid doubles, with zero and many fractional digits respectively. Declaring a fixed scale would be a lie. Clients consuming a DECIMAL without scale should treat it as "IEEE 754 double rendered as JSON number; do not assume fixed fractional digits."

### 4.2 H2 staging behavior for approximate numerics

Staging in H2 uses H2's native `DOUBLE` type for approximate-numeric origins (lossless round-trip), not `DECIMAL(p, ?)`. The canonical label `DECIMAL(15)` is the API contract; the H2 storage choice is internal and invisible to clients.

---

## 5. Source-to-Canonical Mapping Tables

Each supported source dialect has a deterministic mapping from JDBC `java.sql.Types` + column metadata (precision, scale, type name) to a canonical LogicalType. Mappings are mechanical and exhaustive — every JDBC type in each dialect maps to exactly one canonical type.

### 5.1 PostgreSQL

| PG type | JDBC type code | Canonical | Notes |
|---|---|---|---|
| `int2`, `smallint`, `int2vector` | `SMALLINT` (5) | `INTEGER` | |
| `int4`, `integer`, `int`, `serial` | `INTEGER` (4) | `INTEGER` | |
| `int8`, `bigint`, `bigserial` | `BIGINT` (-5) | `BIGINTEGER` | |
| `real`, `float4` | `REAL` (7) | `DECIMAL(7)` | no scale |
| `float8`, `double precision`, `double` | `DOUBLE` (8) | `DECIMAL(15)` | no scale |
| `numeric`, `decimal` (no precision) | `NUMERIC` (2) | `BIGDECIMAL`, **precision omitted**, `scale: 0` | PG unsized numeric is unbounded — omitted precision is the normative encoding for that (§4) |
| `numeric(p,s)`, `decimal(p,s)` (p ≤ 15) | `NUMERIC` (2) | `DECIMAL(p, s)` | |
| `numeric(p,s)`, `decimal(p,s)` (p > 15) | `NUMERIC` (2) | `BIGDECIMAL(p, s)` | |
| `money` | — | `BIGDECIMAL(19, 2)` | PG fixed at 19,2 |
| `boolean`, `bool` | `BIT` / `BOOLEAN` (-7 / 16) | `BOOLEAN` | |
| `char`, `bpchar`, `character` | `CHAR` (1) | `STRING` | |
| `varchar`, `character varying` | `VARCHAR` (12) | `STRING` | |
| `text` | `VARCHAR` (12) | `STRING` | |
| `bytea` | `BINARY` (-2) | `BINARY` | |
| `uuid` | `OTHER` (1111) | `STRING` | canonical UUID text form |
| `json`, `jsonb` | `OTHER` (1111) | `STRING` | JSON-serialized |
| `xml` | `OTHER` (1111) | `STRING` | |
| `date` | `DATE` (91) | `DATE` | |
| `time`, `timetz` | `TIME` (92) | `TIME` | TZ info dropped (TIME has no canonical TZ) |
| `timestamp` | `TIMESTAMP` (93) | `TIMESTAMP` | normalized to UTC |
| `timestamptz` | `TIMESTAMP_WITH_TIMEZONE` (2014) | `TIMESTAMP` | normalized to UTC |
| `interval` (all variants) | `OTHER` (1111) | `STRING` | PG interval format string |
| `bit(n)`, `varbit(n)` | `BIT` / `VARCHAR` | `STRING` | bit-string text representation |
| `enum` types | `OTHER` (1111) | `STRING` | enum label |
| `array` types | `ARRAY` (2003) | `STRING` | serialized representation |
| `oid`, system integers | `BIGINT` | `BIGINTEGER` | |
| geometric / network types | `OTHER` (1111) | `STRING` | WKT / text form |

### 5.2 Oracle

Oracle has significant quirks; the **most important gotcha is that Oracle's `DATE` type stores both date AND time** — it is semantically a TIMESTAMP, not a DATE. Our mapper reflects this.

| Oracle type | JDBC type code | Canonical | Notes |
|---|---|---|---|
| `NUMBER(p)` or `NUMBER(p,0)` or `INTEGER` (Oracle pseudo-type), `INT`, `SMALLINT` (p ≤ 9, scale = 0) | `INTEGER` / `NUMERIC` | `INTEGER` | fits int32 |
| `NUMBER(p)` or `NUMBER(p,0)` (9 < p ≤ 18, scale = 0) | `NUMERIC` | `BIGINTEGER` | fits int64 |
| `NUMBER(p)` or `NUMBER(p,0)` (p > 18, scale = 0) | `NUMERIC` | `BIGDECIMAL(p, 0)` | exceeds int64 |
| `NUMBER(p,s)` (s > 0, p ≤ 15) | `NUMERIC` (2) | `DECIMAL(p, s)` | |
| `NUMBER(p,s)` (s > 0, p > 15) | `NUMERIC` (2) | `BIGDECIMAL(p, s)` | |
| `NUMBER` (no precision/scale — Oracle default) | `NUMERIC` (2) | `BIGDECIMAL(38, 0)` | Oracle default = 38 digits |
| `FLOAT(p)` (Oracle's FLOAT — p in binary bits, 1-126) | `FLOAT` (6) | `DECIMAL(15)` | treated as double-precision |
| `BINARY_FLOAT` | `REAL` (7) | `DECIMAL(7)` | no scale |
| `BINARY_DOUBLE` | `DOUBLE` (8) | `DECIMAL(15)` | no scale |
| `DATE` (Oracle DATE has time component!) | `TIMESTAMP` (93) | `TIMESTAMP` | **Gotcha**: not a DATE — see note below |
| `TIMESTAMP` | `TIMESTAMP` (93) | `TIMESTAMP` | normalized to UTC |
| `TIMESTAMP WITH TIME ZONE` | `TIMESTAMP_WITH_TIMEZONE` (2014) | `TIMESTAMP` | normalized to UTC |
| `TIMESTAMP WITH LOCAL TIME ZONE` | `TIMESTAMP_WITH_TIMEZONE` (2014) | `TIMESTAMP` | normalized to UTC |
| `INTERVAL YEAR TO MONTH` | `OTHER` (1111) | `STRING` | |
| `INTERVAL DAY TO SECOND` | `OTHER` (1111) | `STRING` | |
| `CHAR`, `NCHAR` | `CHAR` (1) | `STRING` | |
| `VARCHAR2`, `NVARCHAR2` | `VARCHAR` (12) | `STRING` | |
| `CLOB`, `NCLOB`, `LONG` | `LONGVARCHAR` / `CLOB` | `STRING` | |
| `BLOB`, `RAW`, `LONG RAW`, `BFILE` | `LONGVARBINARY` / `BLOB` | `BINARY` | |
| `ROWID`, `UROWID` | `ROWID` | `STRING` | |
| `XMLType` | `STRUCT` / `OTHER` | `STRING` | serialized XML |
| `BOOLEAN` (23c+ only) | `BOOLEAN` (16) | `BOOLEAN` | rare; PL/SQL-only before 23c |

**Oracle `DATE` gotcha — explicit policy:** Oracle's `DATE` type stores both date and time-of-day (no fractional seconds). Mapping it to canonical `DATE` would silently truncate the time component — a data-loss bug. **The mapper always maps Oracle `DATE` → canonical `TIMESTAMP`.** If a source table truly has date-only data in an Oracle `DATE` column, the canonical `TIMESTAMP` value will simply carry `T00:00:00Z`. This is honest and lossless.

**`NUMBER(1)` is NOT auto-promoted to BOOLEAN.** Some frameworks infer boolean from `NUMBER(1)`. We do not — that's heuristic and lossy of intent. `NUMBER(1)` → `INTEGER`.

### 5.3 Microsoft SQL Server

| MSSQL type | JDBC type code | Canonical | Notes |
|---|---|---|---|
| `bit` | `BIT` (-7) | `BOOLEAN` | semantically boolean (0/1/null) |
| `tinyint` | `TINYINT` (-6) | `INTEGER` | unsigned 8-bit (0-255) |
| `smallint` | `SMALLINT` (5) | `INTEGER` | |
| `int`, `integer` | `INTEGER` (4) | `INTEGER` | |
| `bigint` | `BIGINT` (-5) | `BIGINTEGER` | |
| `decimal(p,s)`, `numeric(p,s)` (p ≤ 15) | `DECIMAL` / `NUMERIC` (3 / 2) | `DECIMAL(p, s)` | |
| `decimal(p,s)`, `numeric(p,s)` (p > 15) | `DECIMAL` / `NUMERIC` | `BIGDECIMAL(p, s)` | |
| `money` | — | `BIGDECIMAL(19, 4)` | MSSQL fixed at 19,4 |
| `smallmoney` | — | `DECIMAL(10, 4)` | MSSQL fixed at 10,4 |
| `real` | `REAL` (7) | `DECIMAL(7)` | no scale |
| `float(p)` (p ≤ 24) | `FLOAT` (6) | `DECIMAL(7)` | single-precision |
| `float(p)` (24 < p ≤ 53) | `FLOAT` (6) | `DECIMAL(15)` | double-precision |
| `float` (no p, defaults to 53) | `FLOAT` (6) | `DECIMAL(15)` | |
| `date` | `DATE` (91) | `DATE` | |
| `time` | `TIME` (92) | `TIME` | |
| `datetime`, `datetime2`, `smalldatetime` | `TIMESTAMP` (93) | `TIMESTAMP` | normalized to UTC |
| `datetimeoffset` | `TIMESTAMP_WITH_TIMEZONE` (2014) | `TIMESTAMP` | normalized to UTC |
| `char`, `nchar` | `CHAR` (1) | `STRING` | |
| `varchar`, `nvarchar` | `VARCHAR` (12) | `STRING` | |
| `text`, `ntext` | `LONGVARCHAR` | `STRING` | deprecated in MSSQL but still mapped |
| `binary`, `varbinary` | `BINARY` / `VARBINARY` | `BINARY` | |
| `image` | `LONGVARBINARY` | `BINARY` | deprecated but mapped |
| `uniqueidentifier` | `CHAR` (1) | `STRING` | UUID canonical form |
| `xml` | `LONGVARCHAR` / `SQLXML` | `STRING` | serialized XML |
| `sql_variant` | `OTHER` (1111) | `STRING` | heterogeneous — see note |

**`sql_variant` policy:** MSSQL's `sql_variant` can hold values of different types per row. The mapper cannot pick a single canonical type per column. Policy: map to `STRING`, serialize each value via its underlying type's toString, and emit a warning in the response (`warnings` array — see Response Envelope spec). Pipeline authors should `CAST` sql_variant values to a concrete type in their source query template.

### 5.4 MySQL / MariaDB

MySQL's `BOOLEAN` is an alias for `TINYINT(1)`. The JDBC driver reports the column type — we map by what the driver reports, not by declared name.

| MySQL type | JDBC type code | Canonical | Notes |
|---|---|---|---|
| `boolean`, `bool`, `tinyint(1)` (when JDBC reports `BIT`/`BOOLEAN`) | `BIT` (-7) / `BOOLEAN` (16) | `BOOLEAN` | driver-dependent |
| `tinyint` (signed 8-bit, NOT reported as BOOLEAN) | `TINYINT` (-6) | `INTEGER` | |
| `smallint` | `SMALLINT` (5) | `INTEGER` | |
| `mediumint` | `INTEGER` (4) | `INTEGER` | 24-bit |
| `int`, `integer` | `INTEGER` (4) | `INTEGER` | |
| `bigint` | `BIGINT` (-5) | `BIGINTEGER` | |
| `decimal(p,s)`, `numeric(p,s)` (p ≤ 15) | `DECIMAL` (3) | `DECIMAL(p, s)` | |
| `decimal(p,s)`, `numeric(p,s)` (p > 15) | `DECIMAL` (3) | `BIGDECIMAL(p, s)` | |
| `float` | `REAL` (7) | `DECIMAL(7)` | no scale |
| `double`, `double precision`, `real` | `DOUBLE` (8) | `DECIMAL(15)` | no scale |
| `date` | `DATE` (91) | `DATE` | |
| `time` (with optional fractional seconds, fsp) | `TIME` (92) | `TIME` | fractional seconds preserved in ISO string |
| `datetime`, `timestamp` | `TIMESTAMP` (93) | `TIMESTAMP` | normalized to UTC |
| `year(2)`, `year(4)` | `INTEGER` / `DATE` | `INTEGER` | 4-digit year as integer |
| `char` | `CHAR` (1) | `STRING` | |
| `varchar` | `VARCHAR` (12) | `STRING` | |
| `tinytext`, `text`, `mediumtext`, `longtext` | `LONGVARCHAR` (-1) | `STRING` | |
| `enum`, `set` | `CHAR` / `VARCHAR` | `STRING` | enum/set label |
| `binary`, `varbinary` | `BINARY` (-2) / `VARBINARY` (-3) | `BINARY` | |
| `tinyblob`, `blob`, `mediumblob`, `longblob` | `LONGVARBINARY` (-4) | `BINARY` | |
| `bit(n)` (n > 1) | `BIT` (-7) | `BINARY` | bit-string, binary representation |
| `json` | `LONGVARCHAR` (-1) | `STRING` | JSON-serialized |
| geometry types | `BINARY` (-2) | `STRING` | WKT representation; v1 fallback |

### 5.5 H2 (staging layer — used internally)

H2 is the staging database. We map H2 → canonical when reading back from staging for the OUTPUT node.

| H2 type | Canonical | Notes |
|---|---|---|
| `TINYINT`, `SMALLINT`, `INTEGER`, `INT`, `MEDIUMINT` | `INTEGER` | |
| `BIGINT` | `BIGINTEGER` | |
| `NUMERIC(p,s)`, `DECIMAL(p,s)` (p ≤ 15) | `DECIMAL(p, s)` | |
| `NUMERIC(p,s)`, `DECIMAL(p,s)` (p > 15) | `BIGDECIMAL(p, s)` | |
| `REAL` | `DECIMAL(7)` | no scale |
| `DOUBLE`, `DOUBLE PRECISION`, `FLOAT` | `DECIMAL(15)` | no scale (H2 FLOAT aliases DOUBLE) |
| `BOOLEAN`, `BOOL`, `BIT`, `TRUE`, `FALSE` | `BOOLEAN` | |
| `DATE` | `DATE` | |
| `TIME`, `TIME WITHOUT TIME ZONE` | `TIME` | |
| `TIMESTAMP`, `TIMESTAMP WITHOUT TIME ZONE` | `TIMESTAMP` | |
| `TIMESTAMP WITH TIME ZONE` | `TIMESTAMP` | normalized to UTC |
| `VARCHAR`, `VARCHAR_IGNORECASE`, `CHAR`, `CHARACTER`, `CLOB`, `TEXT`, `STRING`, `LONGVARCHAR` | `STRING` | |
| `BINARY`, `VARBINARY`, `BLOB`, `BINARY VARYING`, `LONGVARBINARY` | `BINARY` | |
| `UUID` | `STRING` | canonical UUID form |
| `JSON` | `STRING` | |
| `ENUM` | `STRING` | enum label |
| `GEOMETRY` | `STRING` | WKT |
| `INTERVAL *` (all variants) | `STRING` | interval text form |

### 5.6 DuckDB

DuckDB has 128-bit integers (`HUGEINT`) — unusual but supported.

| DuckDB type | Canonical | Notes |
|---|---|---|
| `tinyint` | `INTEGER` | signed 8-bit |
| `smallint` | `INTEGER` | signed 16-bit |
| `integer`, `int`, `signed` | `INTEGER` | signed 32-bit |
| `bigint` | `BIGINTEGER` | signed 64-bit |
| `hugeint` | `BIGDECIMAL(38, 0)` | 128-bit; no canonical INTEGER can hold it |
| `uhugeint` (unsigned 128-bit) | `BIGDECIMAL(38, 0)` | as above |
| `decimal(p,s)`, `numeric(p,s)` (p ≤ 15) | `DECIMAL(p, s)` | |
| `decimal(p,s)` (p > 15) | `BIGDECIMAL(p, s)` | DuckDB supports up to 38 |
| `float` | `DECIMAL(7)` | no scale |
| `double` | `DECIMAL(15)` | no scale |
| `boolean`, `bool`, `logical` | `BOOLEAN` | |
| `date` | `DATE` | |
| `time`, `time without time zone` | `TIME` | |
| `time with time zone` | `TIME` | TZ info dropped |
| `timestamp`, `timestamp without time zone` | `TIMESTAMP` | normalized to UTC |
| `timestamp with time zone`, `timestamptz` | `TIMESTAMP` | normalized to UTC |
| `varchar`, `char`, `text`, `string`, `bpchar` | `STRING` | |
| `blob`, `bytea`, `varbinary`, `binary` | `BINARY` | |
| `uuid` | `STRING` | |
| `json` | `STRING` | |
| `interval` | `STRING` | |
| `list`, `struct`, `map`, `union` (nested types) | `STRING` | serialized; v1 fallback |

### 5.7 SQLite

SQLite is **dynamically typed**: columns have "type affinity" (INTEGER, TEXT, BLOB, REAL, NUMERIC), not fixed types. Mapping is based on declared column type string parsing and (where possible) actual value inspection.

| SQLite declared type / affinity | Canonical | Notes |
|---|---|---|
| `INTEGER` affinity (declared type contains `INT`) | `INTEGER` | |
| `REAL` affinity (declared type contains `REAL`, `FLOA`, or `DOUB`) | `DECIMAL(15)` | no scale |
| `NUMERIC` affinity (mixed int/float) | `DECIMAL(15)` | conservative; no scale |
| `TEXT` affinity (declared type contains `CHAR`, `CLOB`, or `TEXT`) | `STRING` | |
| `BLOB` affinity — column **has** a declared type containing `BLOB` | `BINARY` | an explicit `BLOB` declaration is a real byte column |
| **No declared type at all** (SQLite assigns BLOB affinity by default) | `STRING` | untyped column; no declaration to trust, so the conservative text fallback wins over `BINARY` |

**The two BLOB-affinity rows are not in conflict — the discriminator is the declared type string, not the affinity:** a column *declared* `BLOB` (or any type whose name contains `BLOB`) maps to canonical `BINARY`; a column declared with **no type at all** (e.g. `CREATE TABLE t (c)`), which SQLite also gives BLOB affinity, maps to canonical `STRING`. Untyped SQLite columns in practice hold text, and base64-encoding text as `BINARY` would be the more damaging error. Affinity names follow the SQLite determination rules (substring match on the declared type, in order INT → CHAR/CLOB/TEXT → BLOB/none → REAL/FLOA/DOUB → NUMERIC).

**SQLite DATE/TIME/TIMESTAMP policy:** SQLite has no native temporal types. Conventions vary widely: ISO 8601 text, Unix epoch seconds (INTEGER), Unix epoch millis (INTEGER), Julian day (REAL). In v1, we map all temporal-looking columns from SQLite to `STRING` and do not attempt heuristic parsing. Pipeline authors who know their storage convention should `CAST` in their query template or post-process the result.

---

## 6. H2 Staging Type Mapping (Canonical → H2)

When the executor stages data from a source into H2 (`CREATE TABLE staging.x ...`), each canonical type maps to a specific H2 column type.

| Canonical | H2 type | Notes |
|---|---|---|
| `NULL` | `VARCHAR` | H2 requires a type; all values will be NULL |
| `BOOLEAN` | `BOOLEAN` | |
| `INTEGER` | `INTEGER` | |
| `BIGINTEGER` | `BIGINT` | H2 BIGINT = int64 |
| `DECIMAL(p, s?)` (exact, scale declared) | `DECIMAL(p, s)` | |
| `DECIMAL(p)` (approximate, no scale) | `DOUBLE` | preserve IEEE 754 representation |
| `BIGDECIMAL(p, s)` | `DECIMAL(p, s)` | H2 2.x `DECIMAL` supports precision up to 100000 — every bounded source precision fits. `BIGDECIMAL` with **omitted** (unbounded) precision stages as `DECIMAL(100000, s)`; see the overflow policy below. |
| `STRING` | `VARCHAR` | length unbounded; H2 supports `VARCHAR` with no length spec |
| `BINARY` | `VARBINARY` | |
| `DATE` | `DATE` | |
| `TIME` | `TIME` | |
| `TIMESTAMP` | `TIMESTAMP WITH TIME ZONE` | H2 stores in UTC; egress reads back as UTC ISO 8601 |

**Overflow policy:** H2 2.x supports `DECIMAL` precision up to **100000**. Every bounded precision any supported dialect can declare fits well inside that. If a staged value's precision exceeds **100000** digits — only reachable from an unbounded source numeric (§4) carrying an extreme value — the staging step fails with error code `pipeline.staging.precision_overflow`. Pipeline authors should reduce precision in the source query (`CAST` to a smaller type) or restructure. The same threshold is stated in [Staging §5.2](staging.md#52-precision-overflow-handling); the two MUST stay identical.

---

## 7. Schema Envelope Structure

Every result set carries a **schema** describing its columns. The schema is an array of column descriptors.

### 7.1 Column descriptor (JSON Schema)

```json
{
  "$schema": "https://datapipelines.co/schema/column.schema.json",
  "type": "object",
  "additionalProperties": true,
  "required": ["name", "type"],
  "properties": {
    "name": {
      "type": "string",
      "description": "Column name as returned by the OUTPUT node's SQL.",
      "minLength": 1
    },
    "type": {
      "type": "string",
      "enum": [
        "NULL", "BOOLEAN", "INTEGER", "BIGINTEGER",
        "DECIMAL", "BIGDECIMAL",
        "STRING", "BINARY",
        "DATE", "TIME", "TIMESTAMP"
      ]
    },
    "precision": {
      "type": "integer",
      "minimum": 1,
      "description": "Required for DECIMAL. Present for BIGDECIMAL except when the source numeric is unsized — an omitted precision on BIGDECIMAL means unbounded (see §4)."
    },
    "scale": {
      "type": "integer",
      "minimum": 0,
      "description": "Required for exact-numeric DECIMAL and all BIGDECIMAL. Omitted for approximate-numeric DECIMAL (source was REAL/DOUBLE)."
    },
    "nullable": {
      "type": "boolean",
      "description": "Optional. True when the source column may contain NULL, false when the source declares it NOT NULL. Omitted when the driver does not report nullability (JDBC columnNullableUnknown) — an absent field means unknown, NOT 'not nullable'."
    }
  },
  "allOf": [
    {
      "if": { "properties": { "type": { "const": "DECIMAL" } } },
      "then": { "required": ["precision"] }
    },
    {
      "if": { "properties": { "type": { "const": "BIGDECIMAL" } } },
      "then": { "required": ["scale"] }
    }
  ]
}
```

**Unknown fields (normative).** `additionalProperties` is `true` by design: [§9.2](#92-what-is-not-frozen) promises that new *optional* fields may be added to the schema envelope without a version bump, and a closed schema would make every such addition breaking. Therefore:

- Producers MUST emit only the fields defined here plus fields introduced by a later revision of this spec.
- **Clients MUST ignore fields they do not recognize.** A client MUST NOT reject, error on, or fail validation of a column descriptor because it carries an unknown property. Strict-mode deserializers (Jackson `FAIL_ON_UNKNOWN_PROPERTIES`, `System.Text.Json` `UnmappedMemberHandling.Disallow`, Pydantic `extra="forbid"`) MUST be configured off for this type.
- A client that rejects unknown fields is non-conformant, and breakage from a future additive field is that client's defect, not a contract break.
- Field *removal* or *meaning change* remains forbidden ([§9.3](#93-evolution-rules)) — the open object buys additive room, not licence to churn.

### 7.2 Schema envelope example

```json
{
  "schema_version": 1,
  "schema": [
    {"name": "customer_id",   "type": "INTEGER", "nullable": false},
    {"name": "customer_name", "type": "STRING", "nullable": false},
    {"name": "total_amount",  "type": "BIGDECIMAL", "precision": 18, "scale": 2, "nullable": true},
    {"name": "unbounded_total", "type": "BIGDECIMAL", "scale": 0},
    {"name": "lifetime_value", "type": "DECIMAL", "precision": 12, "scale": 2},
    {"name": "measurement",   "type": "DECIMAL", "precision": 15},
    {"name": "order_count",   "type": "INTEGER"},
    {"name": "is_vip",        "type": "BOOLEAN"},
    {"name": "first_order_at", "type": "TIMESTAMP"},
    {"name": "renewal_date",  "type": "DATE"},
    {"name": "support_time",  "type": "TIME"},
    {"name": "logo",          "type": "BINARY"}
  ]
}
```

### 7.3 Field-by-field rules

- `name` — always present. From the OUTPUT node's SQL column alias.
- `type` — always present. One of the 11 canonical types.
- `precision` — present iff `type ∈ {DECIMAL, BIGDECIMAL}`, with one exception: omitted for `BIGDECIMAL` when the source numeric is unsized/unbounded (§4). Omitted precision on `BIGDECIMAL` means unbounded; it never means "unknown".
- `scale` — present iff:
  - `type = BIGDECIMAL` (always), or
  - `type = DECIMAL` AND the source was exact-numeric (NUMERIC/DECIMAL/MONEY).
  - Omitted when `type = DECIMAL` AND the source was approximate-numeric (REAL/FLOAT/DOUBLE).
- `nullable` — **optional**, any type. `true`/`false` mirror the driver's `ResultSetMetaData.isNullable()` verdict (`columnNullable` / `columnNoNulls`); the field is omitted when the driver reports `columnNullableUnknown`. Absence means unknown — clients MUST NOT read an absent `nullable` as `false`. Being optional and additive, it does not bump `schema_version` ([§9.2](#92-what-is-not-frozen)).

---

## 8. Edge Cases and Policies

### 8.1 All-NULL columns

When a source query returns a column where every value is NULL (common with conditional `CASE WHEN` expressions), JDBC metadata still declares a type, but the column may not have a meaningful one. **Policy:** trust JDBC metadata. If the driver reports a type, use the standard mapping. If the driver reports `NULL` type (JDBC `Types.NULL`, code 0), emit canonical `NULL`.

### 8.2 Unknown / unmappable types

When a source column has a type the dialect mapper doesn't recognize (exotic Oracle object types, PG extension types, custom MSSQL CLR types, etc.), **Policy:** fall back to `STRING`, serialize the value via its `toString()`, and add a warning to the response envelope:

```json
{
  "schema": [
    {"name": "weird_column", "type": "STRING"}
  ],
  "warnings": [
    {
      "code": "type_mapping.unknown_source_type",
      "message": "Source type 'pgvector' on column 'weird_column' has no canonical mapping; falling back to STRING.",
      "column": "weird_column",
      "source_type": "pgvector"
    }
  ]
}
```

The pipeline does not fail. The pipeline author sees the warning and fixes it (usually with a `CAST` in the template). The dispatch-level and mapper-level fallback branches that implement this policy are shown in [§11.2](#112-mapper-dispatch).

### 8.3 Mixed-precision values in a single column (impossible in practice)

Cannot occur: a single column has one declared type in any source database, and the mapper is deterministic. Documented here for completeness only.

### 8.4 Timestamp timezone normalization

**On ingest:** when a source column has timezone info (`TIMESTAMPTZ` in PG, `TIMESTAMP WITH TIME ZONE` in Oracle/MSSQL/H2, `datetimeoffset` in MSSQL), the value is converted to UTC at read time. The original timezone is **dropped** — canonical TIMESTAMP carries UTC only.

**For TIMESTAMP WITHOUT TIME ZONE:** the source value is assumed to be in UTC (no conversion possible — the source has no TZ info). This is the documented assumption; pipeline authors working with non-UTC naive timestamps should declare their convention in source SQL.

**Policy rationale:** federated queries joining multiple sources with different TZ conventions produce inconsistent results if TZ is preserved per-source. UTC normalization is the only defensible default. Matches industry practice (Snowflake, BigQuery, Databricks).

**Deployment precondition — the JVM default zone MUST be UTC (normative).** The server **REQUIRES** `-Duser.timezone=UTC` (equivalently, `TZ=UTC` in the container environment). This is a hard precondition of every rule above, not an operational nicety:

- JDBC reads of zone-less types (`ResultSet.getTimestamp()` / `getTime()` / `getDate()` without an explicit `Calendar`) resolve against the **JVM default zone**. Under a non-UTC default, a source `TIMESTAMP WITHOUT TIME ZONE` is silently shifted by the offset before it ever reaches the mapper — a correctness bug the type system cannot detect or repair downstream.
- Zone-aware source values are likewise rendered through the default zone by several drivers on the way back out, so egress ISO 8601 strings would carry the wrong instant while still ending in `Z`. Silent, plausible, and wrong: the worst failure shape.
- The same precondition backs "TIMESTAMP WITHOUT TIME ZONE is assumed UTC" above and the `TIMESTAMPTZ`-everywhere rule for internal metadata storage.

The flag is baked into the Docker image entrypoint; bare-JVM and JAR deployments MUST pass it themselves — see [Deployment §3.1](deployment.md#31-docker-image). Deployments that cannot guarantee a UTC JVM are unsupported: every timestamp guarantee in this document is void without it.

### 8.5 Boolean-from-non-boolean sources

Some sources fake booleans:
- Oracle: `NUMBER(1)` with 0/1 (pre-23c, no native boolean)
- MySQL: `TINYINT(1)` — handled by JDBC driver detection
- MSSQL: `BIT` — semantically boolean

**Policy:** map by source type, not by inferred intent. `NUMBER(1)` in Oracle → INTEGER (not BOOLEAN). Pipeline authors who want boolean semantics should wrap in their query: `CASE WHEN col = 1 THEN true ELSE false END AS col`.

### 8.6 UUID and GUID types

Mapped to `STRING` (canonical UUID text form: `xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`). No canonical UUID type in v1. Most clients handle UUIDs as strings naturally.

### 8.7 JSON / JSONB / XML types

Mapped to `STRING`, value is the serialized JSON/XML text. Clients parse as they see fit. No canonical JSON type in v1 — collapsing to STRING keeps the type system small while preserving the data.

### 8.8 Money / currency types

Mapped to DECIMAL/BIGDECIMAL with the source's native precision and scale. PG `money` → `BIGDECIMAL(19, 2)`. MSSQL `money` → `BIGDECIMAL(19, 4)`, `smallmoney` → `DECIMAL(10, 4)`. MySQL has no native money type — typically stored as `DECIMAL(p, 2)`.

### 8.9 Out-of-range integers on ingest

If a source column is declared as INTEGER but contains a value that overflows int32 (rare bug in source schema), the ingest step fails with `pipeline.staging.value_overflow`. Pipeline author fixes via `CAST` in source SQL.

---

## 9. Stability Promise

The canonical type system is a **versioned, additive-only contract.**

### 9.1 What is frozen in v1

- The 11 canonical type names and their spellings.
- The wire encoding for each type (number/string/boolean/null).
- The precision-15 threshold between DECIMAL and BIGDECIMAL.
- The int32/int64 boundary between INTEGER and BIGINTEGER.
- The UTC normalization policy for TIMESTAMP.
- The H2 staging type mapping.

### 9.2 What is NOT frozen

- Per-dialect source mappings (a new database version may introduce types we map differently; mappings are versioned per dialect).
- The list of warning codes (additive).
- The schema envelope's optional fields (new optional fields may be added non-breakingly).

### 9.3 Evolution rules

- **Never** remove or rename a canonical type.
- **Never** change the wire encoding of an existing type.
- **Never** change the precision-15 or int32/int64 boundaries.
- New canonical types are **added only** under a `schema_version` bump.
- New canonical types are added with documented: source mapping rules, wire encoding, H2 staging mapping, and migration notes.
- Clients coded against v1 continue to work against vN without modification.

### 9.4 Versioning

- `schema_version` field on every schema envelope and every response envelope.
- v1 starts at `schema_version: 1`.
- Bumps are integers, monotonic, never reused.
- A bump's release notes document the additive change.

---

## 10. Worked Examples

### 10.1 End-to-end: PG `numeric(18,2)` → staging H2 → JSON response

1. **Source:** PG column `total_amount` declared as `numeric(18, 2)`.
2. **Ingest mapping:** precision 18 > 15 → canonical `BIGDECIMAL(18, 2)`.
3. **H2 staging:** `BIGDECIMAL(18, 2)` → H2 `DECIMAL(18, 2)`. Value `12345.67` stored exactly.
4. **Egress:** read back from H2 via JDBC `ResultSet.getBigDecimal()`. Serialize as JSON string `"12345.67"` (lossless).
5. **Schema entry:** `{"name": "total_amount", "type": "BIGDECIMAL", "precision": 18, "scale": 2}`.
6. **Client (.NET):** parses string via `decimal.Parse("12345.67")` → `System.Decimal`. Lossless.
7. **Client (JS):** sees string `"12345.67"`; parses with `Number()` only if approximate is acceptable for the use case (e.g., chart rendering), otherwise uses `Decimal.js` or displays as-is.

### 10.2 End-to-end: PG `bigint` ID → staging → JSON response

1. **Source:** PG column `event_id` declared as `bigint`.
2. **Ingest mapping:** int64 → canonical `BIGINTEGER`.
3. **H2 staging:** `BIGINTEGER` → H2 `BIGINT`. Value `9223372036854775807` stored exactly.
4. **Egress:** read back via `ResultSet.getLong()`. Serialize as JSON string `"9223372036854775807"` (lossless).
5. **Schema entry:** `{"name": "event_id", "type": "BIGINTEGER"}`.
6. **Client (.NET):** `long.Parse("9223372036854775807")` → `System.Int64`. Lossless.
7. **Client (JS):** sees string `"9223372036854775807"`; uses `BigInt()` for arithmetic or displays as-is. JSON.parse does not corrupt the value (it stays a string).

### 10.3 End-to-end: PG `timestamptz` → staging → JSON response

1. **Source:** PG column `created_at` declared as `timestamptz`, value `2026-08-05 14:30:00.123456-05:00` (US Eastern).
2. **Ingest mapping:** `timestamptz` → canonical `TIMESTAMP` (TZ info to be normalized).
3. **At read time:** JDBC `getTimestamp()` returns the value in JVM default TZ (UTC-configured), so value read is `2026-08-05 19:30:00.123456 UTC`.
4. **H2 staging:** stored as `TIMESTAMP WITH TIME ZONE` value `2026-08-05 19:30:00.123456+00`.
5. **Egress:** read back, format as ISO 8601 UTC string: `"2026-08-05T19:30:00.123456Z"`.
6. **Schema entry:** `{"name": "created_at", "type": "TIMESTAMP"}`.
7. **Client:** parses ISO 8601 string. Always UTC, always `Z` suffix. No TZ conversion logic needed on client.

### 10.4 End-to-end: Oracle `DATE` (with time component)

1. **Source:** Oracle column `order_date` declared as `DATE`, value `2026-08-05 14:30:00`.
2. **Ingest mapping:** Oracle `DATE` → canonical `TIMESTAMP` (NOT canonical `DATE` — see §5.2).
3. **H2 staging:** stored as `TIMESTAMP WITH TIME ZONE` value `2026-08-05 14:30:00.000000+00` (assumed UTC since Oracle DATE has no TZ).
4. **Egress:** `"2026-08-05T14:30:00.000000Z"`.
5. **Schema entry:** `{"name": "order_date", "type": "TIMESTAMP"}`.

### 10.5 End-to-end: MSSQL `sql_variant` column

1. **Source:** MSSQL column `mixed_values` declared as `sql_variant`, contains INT in some rows, VARCHAR in others.
2. **Ingest mapping:** `sql_variant` → canonical `STRING` (with warning).
3. **H2 staging:** stored as `VARCHAR`, value is the underlying type's toString.
4. **Egress:** all values as strings.
5. **Schema entry:** `{"name": "mixed_values", "type": "STRING"}`.
6. **Warning in response:**
   ```json
   {
     "code": "type_mapping.sql_variant",
     "message": "Column 'mixed_values' is MSSQL sql_variant; values serialized as text. CAST to a concrete type in source SQL for typed access.",
     "column": "mixed_values"
   }
   ```

---

## 11. Implementation Notes (Non-Normative)

This section is informational; the normative content is in Sections 3–10.

### 11.1 Where this lives in the codebase

The type system is implemented in the `typesystem` Gradle module:

- `LogicalType` enum (the 11 types)
- `ColumnSchema` data class (name, type, precision?, scale?)
- `IngressTypeMapper` interface + per-dialect implementations (`PostgresTypeMapper`, `OracleTypeMapper`, `MssqlTypeMapper`, `MysqlTypeMapper`, `H2IngressMapper`, `DuckDbTypeMapper`, `SqliteTypeMapper`)
- `FallbackTypeMapper` (unknown dialect / unrecognized JDBC type → `STRING` + warning, per §8.2)
- `H2EgressMapper` (canonical → H2 column type). Note the split: `H2IngressMapper` reads H2 JDBC metadata back into canonical types, `H2EgressMapper` produces the H2 DDL type for a canonical type. There is no `H2TypeMapper` — see [Staging §5.3](staging.md#53-mappers-and-helper-signatures).
- `JsonEncoder` (canonical → wire representation)
- `SchemaEnvelope` data class (schema_version + schema array)

### 11.2 Mapper dispatch

Per-source mapping is dispatched by dialect identifier:

```kotlin
interface IngressTypeMapper {
    fun map(sqlType: Int, precision: Int, scale: Int, typeName: String): LogicalTypeMapping
}

data class LogicalTypeMapping(
    val type: LogicalType,
    val precision: Int? = null,
    val scale: Int? = null
)

object TypeMappers {
    fun forDialect(dialect: Dialect): IngressTypeMapper = when (dialect) {
        POSTGRES    -> PostgresTypeMapper
        ORACLE      -> OracleTypeMapper
        MSSQL       -> MssqlTypeMapper
        MYSQL       -> MysqlTypeMapper
        H2          -> H2IngressMapper
        DUCKDB      -> DuckDbTypeMapper
        SQLITE      -> SqliteTypeMapper
        // Documented else path: a Dialect value this build does not know
        // (e.g. one added to the enum ahead of its mapper) degrades instead
        // of throwing — every column maps to STRING with the §8.2 warning.
        else        -> FallbackTypeMapper
    }
}

/**
 * Implements the §8.2 unknown-type policy. Also the delegate every
 * per-dialect mapper calls for a JDBC type code it does not recognize:
 * never throw, map to STRING, and attach one
 * `type_mapping.unknown_source_type` warning per affected column.
 */
object FallbackTypeMapper : IngressTypeMapper {
    override fun map(sqlType: Int, precision: Int, scale: Int, typeName: String) =
        LogicalTypeMapping(type = LogicalType.STRING)   // + warning, see §8.2
}
```

**Fallback contract (§8.2 restated for implementers):** unrecognized input never fails an execution. Both fallback paths — unknown `Dialect` at dispatch, and unknown JDBC type code inside a per-dialect mapper — resolve to canonical `STRING`, serialize values via `toString()`, and emit exactly one `type_mapping.unknown_source_type` warning naming the column and the source type. The `when` above is therefore total by construction: adding a `Dialect` enum value can never produce a compile-time hole that becomes a runtime crash.

### 11.3 Testing the type system

The type system must have:

- **Unit tests** for every per-dialect mapper covering every JDBC type code that dialect produces.
- **Round-trip tests**: source value → H2 staging → JSON wire → client parsed value (lossless where expected).
- **Edge case tests**: NULL columns, mixed-precision overflow, Oracle `DATE` (not `DATE`), MSSQL `sql_variant` warnings, etc.
- **Wire encoding tests**: every canonical type serializes to the declared wire representation; `JSON.parse` on the serialized output preserves exact values for BIG* types.
- **Egress format tests** (§3.5): TIMESTAMP and TIME render exactly 6 fractional digits including the all-zero case; BINARY round-trips through a standard padded base64 decoder and contains no `-`/`_` characters.
- **Fallback tests** (§8.2/§11.2): an unrecognized JDBC type code yields `STRING` plus exactly one `type_mapping.unknown_source_type` warning, and never throws.
- **Unbounded-precision tests** (§4): a PG `numeric` with no declared precision produces a `BIGDECIMAL` descriptor with no `precision` key, and that descriptor validates against the §7.1 JSON Schema.
- **Unknown-field tests** (§7.1): a column descriptor carrying an unrecognized property still deserializes successfully in the reference clients.

---

## 12. Open Questions / Future Additions

These are explicitly **out of scope for v1** but tracked for future versions. Listed here so they are not forgotten.

- **Nested types** (struct, list/array, map): currently fall back to STRING. Future v2 could add canonical `STRUCT`, `ARRAY`, `MAP` types with schema-declared shapes.
- **Geospatial types**: currently fall back to STRING (WKT). Future v2 could add canonical `GEOMETRY`, `GEOGRAPHY` types with declared SRID.
- **Intervals**: currently STRING. Future v2 could add canonical `INTERVAL_YEAR_MONTH`, `INTERVAL_DAY_TIME`.
- **UUID**: currently STRING. Future v2 could add canonical `UUID`.
- **ENUM**: currently STRING. Future v2 could add canonical `ENUM` with declared allowed values.
- **JSON**: currently STRING. Future v2 could add canonical `JSON` with declared schema.
- **BIT / bit strings**: currently STRING (PG) or BINARY (MySQL). Future v2 could add canonical `BIT_STRING`.
- **Schema-introspection endpoint**: a `/types` or `/schema` endpoint exposing this canonical type list to clients and to MCP for tool-discovery. To be specified in REST API spec.

---

## Appendix A: Change Log

| Date | Version | Author | Change |
|---|---|---|---|
| 2026-08-05 | v1.0 | initial draft | Initial type system specification: 11 canonical types, wire encoding, 7 dialect mappings, edge cases, stability promise |
| 2026-08-07 | v1.1 | spec review | Per [SPEC-REVIEW-2026-08 §2.17](SPEC-REVIEW-2026-08.md#217-type-systemmd) (all [M]): §7.1 column descriptor gains optional `nullable` and opens `additionalProperties` with a normative clients-MUST-ignore-unknown-fields rule (resolves the §9.2 additive-evolution contradiction); PG unsized `numeric` adjudicated to `BIGDECIMAL` with **precision omitted = unbounded** — §3/§4/§5.1/§6/§7.1/§7.3 aligned on that single rule and the two conflicting synthetic-ceiling values deleted; §6 H2 `DECIMAL` limit and `pipeline.staging.precision_overflow` threshold both fixed at 100000 (matches staging.md §5.2); §5.7 REAL-affinity typo fixed and the two BLOB-affinity rows disambiguated (declared BLOB → `BINARY`, no declared type → `STRING`); §1/§2 principle 6 stability-promise pointer corrected to §9; new §3.5 normative egress rules (TIMESTAMP/TIME exactly 6 fractional digits, BINARY = RFC 4648 §4 base64 with padding); §8.4 promotes the UTC-JVM deployment precondition (`-Duser.timezone=UTC`) to a normative rule linked to deployment.md §3.1; §11.2 `forDialect` gains the documented `else` fallback wired to §8.2 (`FallbackTypeMapper` → STRING + `type_mapping.unknown_source_type`); §11.1 `H2TypeMapper` split into `H2IngressMapper`/`H2EgressMapper` per staging.md §5.3; §11.3 test list extended to cover the new rules. |
