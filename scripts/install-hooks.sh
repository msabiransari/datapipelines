#!/usr/bin/env bash
# install-hooks.sh — point git at the committed hooks directory (.githooks/).
#
# Plain `core.hooksPath`, no hooks framework: the setting is per-clone git
# config, so every developer (and every fresh clone) runs this once:
#
#   ./scripts/install-hooks.sh
#
# Currently installed hooks:
#   pre-commit — gitleaks staged-changes scan (scripts/secret-scan.sh --staged)
#
# To bypass a hook once (you will be asked why in review): git commit --no-verify
set -euo pipefail
cd "$(dirname "$0")/.."
git config core.hooksPath .githooks
echo "hooks installed: core.hooksPath=$(git config core.hooksPath)"
ls -1 .githooks/
