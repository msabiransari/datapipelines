# Calculators

**Status:** shipped 2026-09-04 (072). **Authority:** this document is the user-facing catalog; the
registry is [`CalculatorRegistry`](../modules/calculators/src/main/kotlin/co/datapipelines/calculators/CalculatorRegistry.kt),
and `CalculatorRegistrySpecDriftTest` fails the build if the two ever disagree in either direction —
including on an input's name, its type, whether it is optional, and the worked example's answer.

Design record: [Calculators — Configurable Pure Transformations](superpowers/specs/2026-09-04-calculators-design.md) §0.

---

## 1. What a calculator is

A **calculator** is a pure function the server ships: typed inputs in, one typed value out. You use
one by putting a `CALCULATOR` node in a pipeline ([Pipeline Contract §4.10](pipeline-contract.md)):

```json
{ "id": "fiscal_q", "type": "CALCULATOR",
  "kind": "fiscal_quarter",
  "inputs": { "date": "$current_date", "fiscal_start": "$org_fiscal_start_date" },
  "context_key": "run_fiscal_quarter",
  "depends_on": [] }
```

The node writes one value into the execution Context under `context_key`, and every node that
`depends_on` it — directly or transitively — can bind it as `:run_fiscal_quarter`.

Three rules are worth learning once:

1. **`$name` is a reference, anything else is a literal.** `"fiscal_start": "$org_fiscal_start_date"`
   reads the deployment's setting; `"fiscal_start": "09-15"` pins this pipeline's own. The literal is
   type-checked against the input at save time.
2. **Ordering is `depends_on`, not array position.** A reference to another calculator's
   `context_key`, or a SQL node binding one, is valid only if the reader depends on the writer.
   Otherwise the save is refused with `pipeline.validation.calculator_input_unordered` — the node
   would otherwise read a key that may or may not have been written yet, depending on scheduling.
3. **A calculator is not for row data.** It computes ONE value for the whole run. Transforming
   columns is SQL's job — on the source engine, or in tempdb, reused through a library template
   ([Templates §6](templates.md)).

### The keys you can reference without declaring anything

| Key | Type | What it is |
|---|---|---|
| `org_currency_name` | STRING | The deployment's currency name (Configuration §3.21) |
| `org_currency_symbol` | STRING | Its symbol |
| `org_fiscal_start_date` | STRING | `MM-DD`; the fiscal year's first day |
| `org_week_start` | STRING | `monday` or `sunday` |
| `org_timezone` | STRING | IANA zone id |
| `current_date` | DATE | Today, in `org_timezone`, fixed at execution start |
| `current_timestamp` | TIMESTAMP | The execution's start instant |
| `execution_id` | STRING | This execution's id |

Anything else must be a declared pipeline `parameter` or another calculator's `context_key`. The
full precedence table is [Pipeline Contract §7.2](pipeline-contract.md).

---

## 2. The catalog

Reading a signature: `` `name` `` is an input, `?` marks it optional, `[]` marks a JSON array, and
`ANY` means the kind does not look at the value's type. Types are the canonical ones
([Type System §3](type-system.md)).

### 2.1 Calendar and time

| kind | Inputs → Output | What it computes | Example |
|---|---|---|---|
| `quarter_of_year` | `date` DATE → INTEGER | Which calendar quarter (1-4) a date falls in. | date=2026-08-14 → 3 |
| `fiscal_year` | `date` DATE, `fiscal_start` STRING → INTEGER | The fiscal year a date falls in, labelled by the calendar year the fiscal year STARTS in (with a 04-06 start, 2026-01-15 is fiscal year 2025). | date=2026-01-15, fiscal_start=04-06 → 2025 |
| `fiscal_quarter` | `date` DATE, `fiscal_start` STRING → INTEGER | Which quarter (1-4) of its fiscal year a date falls in. | date=2026-08-14, fiscal_start=09-15 → 4 |
| `period_start` | `date` DATE, `unit` STRING, `mode?` STRING, `fiscal_start?` STRING, `week_start?` STRING → DATE | The first day of the week, month, quarter or year containing a date. | date=2026-08-14, unit=quarter → 2026-07-01 |
| `period_end` | `date` DATE, `unit` STRING, `mode?` STRING, `fiscal_start?` STRING, `week_start?` STRING → DATE | The last day of the week, month, quarter or year containing a date. | date=2026-08-14, unit=quarter → 2026-09-30 |
| `prior_period` | `date` DATE, `unit` STRING, `offset?` INTEGER, `mode?` STRING, `fiscal_start?` STRING, `week_start?` STRING → DATE | The first day of the period `offset` periods before the one containing a date — the anchor a period-over-period comparison filters from. | date=2026-08-14, unit=quarter, offset=1 → 2026-04-01 |
| `date_trunc` | `date` DATE, `unit` STRING, `week_start?` STRING → DATE | A date snapped back to the start of its day, week, month, quarter or year. | date=2026-08-14, unit=month → 2026-08-01 |
| `iso_week` | `date` DATE → INTEGER | The ISO-8601 week number (1-53) of a date. | date=2026-01-01 → 1 |
| `iso_year` | `date` DATE → INTEGER | The ISO-8601 week-based year of a date — which differs from the calendar year in the days either side of New Year, and is why it is its own kind. | date=2027-01-01 → 2026 |
| `day_of_week` | `date` DATE, `week_start?` STRING → INTEGER | The day's position in the week (1-7), counting from `week_start`. | date=2026-08-14, week_start=monday → 5 |
| `days_in_month` | `date` DATE → INTEGER | How many days the date's calendar month has (28-31). | date=2028-02-10 → 29 |
| `date_diff` | `from` DATE, `to` DATE, `unit` STRING → INTEGER | Whole units from one date to another; negative when `to` precedes `from`. Partial units are truncated, never rounded. | from=2026-01-01, to=2026-08-14, unit=month → 7 |
| `add_days` | `date` DATE, `days` INTEGER → DATE | A date shifted by a whole number of calendar days; negative shifts back. | date=2026-08-14, days=-30 → 2026-07-15 |
| `add_months` | `date` DATE, `months` INTEGER → DATE | A date shifted by whole months, clamped to the target month's last day (2026-01-31 plus one month is 2026-02-28). | date=2026-01-31, months=1 → 2026-02-28 |
| `add_business_days` | `date` DATE, `days` INTEGER, `weekend_days[]?` STRING, `holidays[]?` DATE → DATE | A date shifted by working days, skipping the weekend days and the listed holidays. Negative counts step backwards; the starting date is never counted. | date=2026-08-14, days=1, holidays=["2026-08-17"] → 2026-08-18 |
| `date_parse` | `text` STRING, `format` STRING → DATE | A date read out of text with an explicit pattern. The grammar is Java's `DateTimeFormatter`, which is contract: `dd/MM/yyyy`, `yyyyMMdd`, `MMM d, yyyy`. | text=14/08/2026, format=dd/MM/yyyy → 2026-08-14 |
| `date_format` | `date` DATE, `format` STRING → STRING | A date rendered as text with an explicit `DateTimeFormatter` pattern. | date=2026-08-14, format=yyyyMMdd → 20260814 |
| `tz_shift` | `timestamp` TIMESTAMP, `from_zone` STRING, `to_zone` STRING → TIMESTAMP | Re-reads a timestamp's wall-clock time from one zone in another: the clock face is kept and the instant moves. This is the kind for a timestamp that was stored under the wrong zone, not for displaying one — a timestamp is already absolute. | timestamp=2026-06-01T12:00:00Z, from_zone=UTC, to_zone=Europe/Berlin → 2026-06-01T10:00:00Z |

**Two conventions these kinds fix, so that nothing has to guess.** A fiscal year is labelled by the
calendar year it **starts** in — with `fiscal_start = "04-06"`, 2026-01-15 is fiscal year 2025.
(Deployments that label by the ending year, as the US federal government does, add 1 in SQL or
declare a parameter.) And `02-29` is a legal `fiscal_start`: it resolves to 02-28 in a non-leap
year, which is what `MonthDay.atYear` does and what `ConfigValidator` accepts it for.

`mode` is `calendar` or `fiscal` and decides how `quarter` and `year` boundaries fall. A **month is
a month in both** — no fiscal calendar in use redefines a month's boundary, and pretending `mode`
mattered there would invite an author to expect that it did.

Two grammars are contract, not implementation detail: `format` is a Java
[`DateTimeFormatter`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/time/format/DateTimeFormatter.html)
pattern, and every zone is an IANA id. A fixed offset such as `+02:00` is deliberately not a zone id.

`tz_shift` re-reads a wall-clock time, so it meets daylight saving head on. A reading that lands in
the spring-forward **gap** (02:30 on a night the clocks jump 02:00 → 03:00) is moved forward by the
gap's length, and one in the autumn **overlap** takes the earlier of the two offsets — Java's
`ZonedDateTime` rules, stated here because a kind's grammar is contract.

### 2.2 Numeric

| kind | Inputs → Output | What it computes | Example |
|---|---|---|---|
| `round` | `value` DECIMAL, `places?` INTEGER, `mode?` STRING → DECIMAL | A decimal rounded to a number of places under an explicit rounding mode. | value=2.345, places=2 → 2.35 |
| `percent_change` | `current` DECIMAL, `previous` DECIMAL, `places?` INTEGER → DECIMAL | The change from `previous` to `current` as a percentage: (current − previous) ÷ previous × 100. A zero `previous` is refused rather than reported as infinity. | current=125, previous=100, places=2 → 25.00 |

Both are `BigDecimal` end to end. A money figure that passed through a `double` on its way is the
defect this choice exists to prevent, which is also why `percent_change` refuses a zero `previous`
rather than reporting infinity.

### 2.3 Values

| kind | Inputs → Output | What it computes | Example |
|---|---|---|---|
| `coalesce` | `values[]` ANY → ANY | The first value that is not null; null when every one of them is. | values=["$requested_region", "GLOBAL"] → GLOBAL |
| `if_null` | `value` ANY, `default` ANY → ANY | The value, or `default` when the value is null. `coalesce` for exactly two candidates. | value=$requested_region, default=GLOBAL → GLOBAL |
| `map` | `value` ANY, `from[]` ANY, `to[]` ANY, `default?` ANY → ANY | Translates a value through a lookup carried in the node itself: the value at the position in `from` where it matches, or `default`. The pairs are parallel arrays rather than a list of objects so each element types like any other literal. | value=GB, from=["GB", "US"], to=["United Kingdom", "United States"] → United Kingdom |

`map` carries its lookup in the node — parallel `from`/`to` arrays, so each element types like any
other literal. It is for a handful of pairs an author can read at a glance; a lookup with hundreds
of rows belongs in a table and a SQL join.

---

## 3. When it fails

A calculator that cannot evaluate the inputs it was given fails its node with
`pipeline.node.calculator_failed` ([Pipeline Contract §13.4](pipeline-contract.md)). The failure
record names the `kind`, the input at fault and the reason — an unknown `unit`, a `format` that does
not compile, text that does not match its pattern, a zero denominator. The execution then fails
fail-fast like any other node failure, and the record reaches SSE, `executions_get` and the run's
detail page unchanged.

Most defects never get that far: `kind` exists, every required input is present, literals type-check,
`context_key` is well-named and collides with nothing, and every `$reference` resolves to a key that
is genuinely upstream — all of that is refused at **save** time
([Pipeline Contract §12.10](pipeline-contract.md)).

---

## 4. Growing the catalog

The catalog is **additive, forever**. A `kind` is written into pipeline bodies that are versioned,
exported and promoted, so removing one — or changing what one computes — breaks a body that already
validated. A changed meaning is a new kind with a new name.

Adding one means: the kind in `modules/calculators`, its unit tests, and its row in this document,
in the same commit. The drift guard makes that non-optional in both directions.
