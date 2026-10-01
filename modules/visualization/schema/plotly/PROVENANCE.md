# Vendored Plotly plot-schema — provenance

| Fact | Value |
|---|---|
| Product | plotly.js |
| Release | v4.1.1 (the same release the client bundles vendor, spec §10.4) |
| Upstream commit | `0aabc3c5cc4f1d91fd6dd3846c5353de1beb0885` (the `v4.1.1` tag's commit; L3a's pin) |
| Source file | `dist/plot-schema.json`, fetched from `https://raw.githubusercontent.com/plotly/plotly.js/v4.1.1/dist/plot-schema.json` |
| Source SHA-256 | `64895178a4f8cbc3cd10d7824e7ee44439066c15c08d258a09f1e60f463b6d1a` |
| Source size | 3,899,959 bytes |
| Version corroboration | `https://raw.githubusercontent.com/plotly/plotly.js/v4.1.1/package.json` → `"version": "4.1.1"`, `"license": "MIT"` |
| License | MIT (`LICENSE`, verbatim from the tagged release) |
| Reduced output | `src/main/resources/co/datapipelines/visualization/plot-schema-reduced.json` (committed) |
| Reducer | `co.datapipelines.visualization.PlotlySchemaReducer` — deterministic (sorted keys, no timestamps) |
| Regeneration | `./gradlew :modules:visualization:reducePlotlySchema` — reads THIS checked-in source, rewrites the committed resource; a clean build never fetches upstream |

## Reduction

The reduced file keeps, for the nine supported traces (`scatter`, `bar`, `pie`, `histogram`, `box`,
`heatmap`, `scatter3d`, `surface`, `mesh3d` — §10.4's bundles), the trace's `attributes` tree; plus the
`layout.layoutAttributes` tree and the Plotly `config` attributes. Per attribute it keeps only
`valType`, `values`, `freeLength`, `arrayOk`, `role` and the recursed `items`; `description`, `dflt`,
`editType`, `anim`, `impliedEdits` and every `_`-prefixed meta key are dropped. Output keys are sorted.

## Verification

`PlotlySchemaProvenanceTest` (this module) asserts, on every build:

1. the committed resource IS `PlotlySchemaReducer.reduce(source)` over THIS checked-in source, byte for
   byte, and running the reduction twice yields the same bytes (determinism);
2. the recorded `source_sha256` equals the checked-in source's actual hash;
3. the recorded `upstream_commit`/`plotly_version` are this file's values;
4. the LICENSE file is present with the MIT grant text.
