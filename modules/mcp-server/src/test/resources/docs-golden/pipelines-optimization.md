
# How to create optimized pipelines

Start with the question the pipeline answers. State what one output row represents, for example one store or one individual sale. Record which rows must survive, the time window, and the meaning of nulls and duplicates. Then locate the expensive step. In a cross-source pipeline that may be data transfer or staging; in a single-source query it may be the join itself. A faster run that loses customers, doubles sales, or changes the period is an incorrect run.

| Scenario | First shape to try | Evidence to inspect |
|---|---|---|
| Independent reads on two sources | Two source nodes with no edge between them; join depends on both | Dependency graph, timestamps, pool waits, total elapsed time |
| Large fact table with a selective predicate | Apply the period/tenant filter and project needed columns in the source query | Source plan, rows and bytes shipped, boundary cases |
| Tables on one datasource | Join there when its plan can use the keys and the rows share the same semantics | Plan, cardinality before/after, missing keys |
| Tables on different datasources | Reduce each locally, stage under distinct names, then join in tempdb | Per-source outputs, unmatched keys, fan-out |
| Repeated aggregation or skew | Keep sufficient aggregate state; check hot keys separately | Sums, non-null counts, distribution and critical-path duration |
| Repeated staging, scans or timeouts | Measure reuse, index cost, partition pruning and possible concurrency limits | Full run including staging and index build; scan evidence where available |

## Let independent reads run together

Suppose one datasource contains sales and another contains store names. Stage sales by store in `sales_by_store` and the lookup in `store_names`. Both source nodes have empty `depends_on`. The final node reads tempdb and sets `depends_on: ["sales_by_store", "store_names"]`. Those values are node IDs; choose the corresponding output table names explicitly. The executor can overlap the two reads subject to available connections and parallel slots. Chaining the lookup behind the sales read adds a wait without supplying data.

Do keep an edge when its absence changes the answer: a lookup built from the first node's rows, a required validation before publication, or a genuine write ordering. If a dataset has already been fetched once for several consumers, stage it once and let each consumer depend on the producer. Compare the storage and read-back cost with repeated source reads. Inspect the longest dependency path and actual overlap; two ready nodes do not guarantee two available connections.

## Reduce a large table before a join

Question: revenue by store during the half-open interval from start to end. Assume one currency and one store per sale. On the sales datasource, stage only this result:

```sql
SELECT store_id, SUM(amount) AS revenue, COUNT(amount) AS priced_sales
FROM sales
WHERE sold_at >= :start_at AND sold_at < :end_at
GROUP BY store_id
```

Bind timestamps in the source's type and timezone. The half-open window assigns a sale exactly at the end to the next period. If the output needs individual sales or a later filter on product, retain that grain or include product in the intermediate grouping. Pre-aggregating away a needed dimension cannot be repaired at the join. A filter directly on an indexed or partitioned timestamp may enable pruning; inspect the source's actual plan. Do not infer skipped bytes just because a predicate appears in SQL.

## Pick the join location and preserve unmatched rows

When both relations live on one datasource, try one query there before moving rows through tempdb. When they live on different datasources, a source node cannot query the other source's table or its staged output. Stage the reduced fact result above and only the needed lookup columns. The following final query preserves stores with sales even when a lookup name is missing:

```sql
SELECT s.store_id, n.store_name, s.revenue, s.priced_sales
FROM sales_by_store s
LEFT JOIN store_names n ON n.store_id = s.store_id
```

First check that `store_id` is unique in the lookup at the required date. Two names for one store would duplicate its revenue. For a historical lookup, apply the agreed effective-date rule; taking an arbitrary row hides ambiguity. A right-side predicate in `WHERE` can drop unmatched left rows. Put a qualification in `ON` when only the match should be restricted. To include stores with **zero sales**, start with the required store population and left-join sales to it. Those are two different business questions.

## Carry the state needed to combine groups

Suppose group A contains one priced sale of 100 and group B contains three priced sales of 20. The combined mean is (100 + 60) / (1 + 3) = 40. Averaging the two group means gives 60. Carry `SUM(amount)` and `COUNT(amount)` through staging, divide once using the required numeric type, and define the zero-denominator result. COUNT(*) would include unpriced sales. Distinct customers can occur in multiple partitions; summing partition-level distinct counts double-counts them. Deduplicate at the final grain or prove the sets disjoint. See `pipelines-numbers` for precision and reconciliation.

## Tune staging and parallelism where measurements justify them

A large staged table repeatedly joined on the same key may justify a tempdb index. Use a staging DDL node after the load and make its consumers depend on the index. Measure load + index build + all consumers against the same pipeline without the index. A small lookup scanned once may finish sooner without it. For a repeated expensive subquery, compare materializing it once to the optimizer's existing plan; the H2 case in `pipelines-engine-quirks` is engine-specific.

Split one scan into parallel windows only if intervals are disjoint, cover the requested period, and the final result can be recombined correctly. Use [start, middle) and [middle, end) and test a row exactly at middle. A skewed window can dominate total latency. Inspect per-branch rows and duration; stop adding parallel calls when the database, pool or staging writer is saturated. On a lake read, use the real partition keys and inspect measured pruning when available. Raising a timeout or splitting one full scan into several full scans gives no evidence of less work.

## Verify that the rewrite earned its place

1. Inspect schemas, indexes, row estimates and supported plan output via `datasources_get_table_stats` and a bounded read-only `sql_probe`. Use representative parameters. A preview or capped probe can help inspect a plan; it cannot prove equivalence of the whole population.
2. Compare baseline and candidate against the same stable fixture or snapshot, with the same parameters, limits and result consumption. Reconcile keys, duplicate multiplicity, unmatched rows, nulls, totals and boundary cases. Compare equivalent result sets without relying on incidental order.
3. Record end-to-end elapsed time, node_stats duration_ms and rows_out, bytes_out where measured, source wall_ms, and plan or partition counts where the dialect exposes them. Distinguish time waiting for a connection from query, transfer and staging time. Overlapping node durations do not add up to wall-clock latency. Report unavailable values as unknown.
4. Repeat comparable runs, record warm/cold conditions and the range observed. Report the change, evidence and trade-off. If timing is inconclusive, say so. For a write pipeline, use an isolated disposable target and reset fixtures between runs. Node debugging can perform real writes; use read-only SELECT probes for query diagnosis.

For detailed rules read `pipelines-dag`, `pipelines-engine-quirks`, `pipelines-numbers` and `pipelines-verification` via docs_get.
