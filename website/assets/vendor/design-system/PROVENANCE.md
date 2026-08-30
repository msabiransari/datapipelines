# Design-system provenance

The files in this directory are **byte-identical copies** of the datapipelines
product's own design system, vendored so the marketing site styles itself with
the same tokens the app uses. Do not edit them here — edit the source and
re-copy.

- **Source:** `modules/web/src/main/resources/static/vendor/design-system/`
  in <https://github.com/msabiransari/datapipelines>
- **Copied at commit:** `d733f9a5a70a165ac0b27c7e88f60e283f45ce0a`
- **Re-verified byte-identical at:** `a8cce57` (origin/main, 2026-08-29) —
  `git show origin/main:<source-path> | shasum -a 256` matches every hash below.

| File | SHA-256 |
|---|---|
| `tokens.css` | `0d86fc432d63ebbf277d4645163e392c317da8f3cbdfcb4d7a51339fa7078250` |
| `base.css` | `23b89cc846804dd328189f42b7fdaf62f8c41b2c2bc04de5476028814ecf1636` |
| `motion.css` | `1f32f4f6018752b434ad30114710ca10c7dfe514e5419003325da824c8ff608b` |
| `icons.css` | `68b21794e9c5e0c6586fd30dae189d63e716cbeaa49a434f46a5db47b827a622` |
| `themes/auto.css` | `761999df995d7923caa1a591bef407071cef7bdb1be3f609eedc2537c4d8dcc0` |
| `themes/light.css` | `0802ba391d3cc209e5f95da35a418e6bbc48ec96b096b2531a6492cb592e1c10` |
| `themes/dark.css` | `ead04337e892a1c98918d70103179daab95fea3973fc012b56a6faa7ef1b31fa` |

Deliberately **not** copied:

- `primitives.css` — app component styles; the site styles its own components
  from tokens.
- `vendor-manifest.json` — its checksum strings trip gitleaks'
  `generic-api-key` rule outside its path-scoped allowlist entry. This file is
  its replacement for drift detection.

Drift check (run from the repo root):

```sh
for f in tokens.css base.css motion.css icons.css themes/auto.css themes/light.css themes/dark.css; do
  diff <(git show origin/main:modules/web/src/main/resources/static/vendor/design-system/$f) \
       website/assets/vendor/design-system/$f >/dev/null || echo "DRIFT: $f"
done
```
