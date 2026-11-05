#!/usr/bin/env bash
set -euo pipefail
SOURCE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TEST_DIR="$(mktemp -d)"
trap 'rm -rf "$TEST_DIR"' EXIT
mkdir -p "$TEST_DIR/cli/dist" "$TEST_DIR/bin"
cat > "$TEST_DIR/cli/package.json" <<'JSON'
{"name":"@jiahao6635/skill-center","bin":{"skill-center":"dist/index.js"}}
JSON
echo 'console.log("Skill Center CLI 1.0.0")' > "$TEST_DIR/cli/dist/index.js"
cat > "$TEST_DIR/bin/bun" <<'STUB'
#!/usr/bin/env bash
[[ "${CLI_TEST_FAIL:-}" != "true" ]] || exit 1
STUB
cat > "$TEST_DIR/bin/npm" <<'STUB'
#!/usr/bin/env bash
printf '%s|%s\n' "$*" "${npm_config_registry:-}" >> "$CLI_TEST_LOG"
STUB
chmod +x "$TEST_DIR/bin/bun" "$TEST_DIR/bin/npm"
export PATH="$TEST_DIR/bin:$PATH" REPO_ROOT="$TEST_DIR" CLI_TEST_LOG="$TEST_DIR/npm.log"

bash "$SOURCE_ROOT/scripts/publish-cli.sh" --dry-run >/dev/null
[[ "$(cat "$CLI_TEST_LOG")" == 'pack --ignore-scripts --pack-destination dist|' ]]
: > "$CLI_TEST_LOG"
NPM_REGISTRY=https://npm.example.test bash "$SOURCE_ROOT/scripts/publish-cli.sh" --publish >/dev/null
[[ "$(cat "$CLI_TEST_LOG")" == 'publish --ignore-scripts --access public|https://npm.example.test' ]]
: > "$CLI_TEST_LOG"
if CLI_TEST_FAIL=true bash "$SOURCE_ROOT/scripts/publish-cli.sh" --publish >/dev/null 2>&1; then
  echo 'Expected check failure to stop publishing' >&2; exit 1
fi
[[ ! -s "$CLI_TEST_LOG" ]]
if bash "$SOURCE_ROOT/scripts/publish-cli.sh" --unknown >/dev/null 2>&1; then
  echo 'Expected unknown flag to fail' >&2; exit 1
fi
echo 'publish-cli tests passed'
