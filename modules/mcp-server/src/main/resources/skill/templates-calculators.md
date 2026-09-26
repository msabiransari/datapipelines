---
area: templates
layer: reference
purpose: A value computed once and bound downstream — the CALCULATOR node, the Context keys, and how to override a computed value.
---

# Calculators — computing a value the SQL then binds

A `CALCULATOR` node evaluates one **pure function the server ships** and writes one typed
value, or a named set of them, into the execution Context. Downstream nodes bind it exactly
like a parameter.

```json
{ "id": "fiscal_q", "type": "CALCULATOR",
  "kind": "fiscal_quarter",
  "inputs": {"date": "$current_date", "fiscal_start": "$org_fiscal_start_date"},
  "context_key": "run_fiscal_quarter",
  "depends_on": [] }
```

A kind whose answer is genuinely several values declares a named **output set** instead, and
the node maps EVERY output to a key through `context_keys` — never both fields, never neither,
no partial mapping:

```json
{ "id": "window", "type": "CALCULATOR",
  "kind": "trailing_periods",
  "inputs": {"date": "$current_date", "unit": "quarter"},
  "context_keys": {"start": "window_start", "end": "window_end"},
  "depends_on": [] }
```

…and then, in a node that `depends_on: ["fiscal_q"]` (or `["window"]` — every key it binds
needs the edge):

```sql
SELECT region, SUM(amount) AS total
FROM orders
WHERE fiscal_quarter = :run_fiscal_quarter
GROUP BY region
```

**Call `calculators_list` before you author one.** The kind names and their input names are
not guessable; the tool returns every kind with typed inputs, its output type and a worked
example. A **single-output** kind carries `output` (the type, or `"ANY"`); a **multi-output**
kind carries `"output": null` and `outputs` — the `[{name, type, description}]` set your
`context_keys` maps, every name mapped or the save is refused (`calculator_output_unknown` /
`calculator_outputs_incomplete`; the wrong field for the kind's shape is
`calculator_output_shape_mismatch`). Each kind also lists `phrases` — the everyday phrases it
answers — and matching the question's words against them is how a kind is chosen (the
pipelines guide's window rule). `calculators_get {kind}` is the same entry for one kind.

Four rules, and the third is the one that bites:

1. **`$name` is a reference, anything else is a literal.** `"fiscal_start":
   "$org_fiscal_start_date"` reads the deployment's setting; `"fiscal_start": "09-15"` pins
   this pipeline's own. Literals are type-checked at save.
2. **`context_key` is not `output` — and neither is `context_keys`.** They name Context
   values, never a table. A CALCULATOR node carries no `source`, no `template` and no `output`
   — declaring any of them is refused. `context_key` is the single-output kind's field,
   `context_keys` the multi-output kind's; a node carries exactly one of them.
3. **Sequencing is `depends_on`, not array order.** A node that references another node's key —
   in `inputs` OR as a `:bind` in its SQL — must depend on the producer, directly or
   transitively. Otherwise the save is refused with
   `pipeline.validation.calculator_input_unordered`, and the fix is one entry in `depends_on`.
4. **Row-level transforms are NOT calculators.** A calculator computes one value — or one
   named set — for the whole run. Transforming columns is SQL's job — on the source engine, in
   tempdb, or through a library template macro. There is no row calculator and there is not
   going to be one.

## Context keys you can reference without declaring anything

| Key | Type | What it is |
|---|---|---|
| `org_currency_name`, `org_currency_symbol` | STRING | The deployment's currency |
| `org_fiscal_start_date` | STRING | `MM-DD` — when the fiscal year starts |
| `org_week_start` | STRING | `monday` or `sunday` |
| `org_timezone` | STRING | IANA zone id |
| `current_date` | DATE | Today, in `org_timezone`, fixed at execution start |
| `current_timestamp` | TIMESTAMP | The execution's start instant |
| `execution_id` | STRING | This execution's id |

Precedence, lowest first — **org config < platform < declared `parameters` < execute-time
inputs < calculator outputs**. Declaring a parameter named `org_timezone` overrides the
deployment's, and that override is visible in the body, which is the point. A calculator may
shadow an org or platform key; it may **never** shadow a declared parameter
(`calculator_output_collision`), and two nodes may not write the same key.

After a run, `executions_get` shows each CALCULATOR node's `context_key` and `context_value` in
its `node_stats` entry, and `parameters` carries the fully resolved Context — every tier, the
calculator outputs included. That is where you look when a computed number is not what you
expected.

## Overriding a calculator

Every key a calculator node writes is also an implicit OPTIONAL input of `pipelines_execute` —
`pipelines_get` lists these under `parameters` with `"derived": true`. Supply the key and the
node is **skipped**: the supplied value is what downstream nodes bind, and the node's
`executions_get.node_stats[]` entry shows `provided_by: "caller"`. Omit it and the node
computes from the Context exactly as before. For a multi-output node the override is
**all-or-nothing**: supply EVERY key it writes or none — a proper subset is refused before
anything runs with `pipeline.execution.calculator_keys_partial` (`details.missing` names the
rest). The value is typed by the key's output, so a re-run for an old quarter passes a JSON
**number** where the kind outputs INTEGER, never a spelled label (which fails coercion with
`pipeline.execution.invalid_parameter_type`).
