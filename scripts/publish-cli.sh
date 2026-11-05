#!/usr/bin/env bash
# Build and validate the independently owned npm package; no git/CI dependency.
set -euo pipefail

REPO_ROOT="${REPO_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
case "${1:---dry-run}" in
  --dry-run) publish=false ;;
  --publish) publish=true ;;
  *) echo "Usage: $0 [--dry-run|--publish]" >&2; exit 2 ;;
esac
if [[ $# -gt 1 ]]; then echo "Unexpected argument: $2" >&2; exit 2; fi

cd "$REPO_ROOT/cli"
node -e '
  const p = require("./package.json");
  if (p.name !== "@jiahao6635/skill-center" || !p.bin["skill-center"]) {
    throw new Error("Unexpected package identity; inspect cli/package.json before publishing");
  }
'
bun run check
node dist/index.js version
SKILL_CENTER_TEST_ENTRY="$REPO_ROOT/cli/dist/index.js" bun test test/integration/manage-command.test.ts
if [[ -n "${NPM_REGISTRY:-}" ]]; then export npm_config_registry="$NPM_REGISTRY"; fi
if $publish; then
  npm publish --ignore-scripts --access public
else
  mkdir -p dist
  npm pack --ignore-scripts --pack-destination dist
fi
