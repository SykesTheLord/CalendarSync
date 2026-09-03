#!/usr/bin/env bash
# PostToolUse (Write|Edit) check: after a .java edit, run an incremental offline
# compile so a syntax or type error surfaces at the edit that caused it rather
# than several steps later at the next build.
#
# ~1.7s incremental. Checkstyle is skipped deliberately: it is bound to the
# validate phase, is advisory (failOnViolation=false), and its warnings are
# swallowed by -q anyway, so paying for it on every edit buys nothing.
#
# Exit 2 hands stderr back to Claude, which is what makes the failure actionable.
set -uo pipefail

f=$(jq -r '.tool_input.file_path // .tool_response.filePath // empty')

case "$f" in
  *.java) ;;
  *) exit 0 ;;
esac

dir="${CLAUDE_PROJECT_DIR:-$(cd "$(dirname "$0")/../.." && pwd)}"
cd "$dir" || exit 0

if ! out=$(mvn -o -q compile -Dcheckstyle.skip=true 2>&1); then
  {
    printf 'Compile failed after editing %s\n\n' "$f"
    printf '%s\n' "$out" | tail -40
  } >&2
  exit 2
fi

exit 0
