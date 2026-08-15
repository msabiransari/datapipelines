# Contributing to datapipelines.co

Thanks for your interest. This project welcomes contributions — with a
deliberately simple governance model:

- **All changes land via pull request.** There are no direct pushes and no
  co-maintainers; the project owner reviews and merges every PR.
- **Deploying and using the project as-is requires nothing from you.** See
  [LICENSE](LICENSE) (AGPL-3.0) — in short: use freely; if you modify it and
  offer it over a network, you must publish your modifications under the same
  license. Contributing upstream is usually the cheaper path.
- **Every contributor signs the CLA once** ([CLA.md](CLA.md)) before their
  first PR is merged. The CLA keeps the project relicensable by a single
  owner (e.g. for a future commercial edition) while your contribution stays
  available to everyone under the AGPL.

## Before you open a PR

1. Read [DEVELOPMENT.md](DEVELOPMENT.md) for environment setup, build
   commands, and conventions.
2. **Tests are written with the code, not after.** PRs without tests for the
   changed behavior are not merged.
3. **Docs are load-bearing.** Several specs under `docs/` are parsed by
   drift tests (error-code catalog, MCP tool schemas, scope matrix). If your
   change touches a catalogued value, the doc amendment and the code change
   must be in the same commit, or the build goes red.
4. Run the full gate locally and include the result in your PR description:

   ```
   ./gradlew build          # must be green
   ./scripts/docs-audit.sh  # must exit 0
   ```

5. Keep commits imperative and scoped (`feat: ...`, `fix: ...`, `docs: ...`).
   No AI-attribution trailers.

## What makes a PR easy to merge

- One concern per PR; small beats big.
- A failing test that demonstrates the defect, then the fix (for bug fixes).
- A sentence in the description about what you checked beyond the diff
  (neighboring call sites, doc coupling).

## Proposing larger changes

Open an issue first describing the problem and the intended approach.
Significant surface changes (new endpoints, MCP tools, pipeline contract
fields) are spec-first in this project — expect the discussion to start at
the `docs/` level, not the code level.
