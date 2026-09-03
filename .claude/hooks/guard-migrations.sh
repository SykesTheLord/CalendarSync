#!/usr/bin/env bash
# PreToolUse (Write|Edit) guard: refuse to modify a Flyway migration that
# already exists on disk.
#
# Flyway records a checksum for every migration it applies. Editing an applied
# V<n>__ file makes that checksum mismatch, and every database it has already
# run against - the local dev DB, any deployed instance - then fails validation
# at startup. The remedy is always a new migration, never an edit.
#
# Creating a brand-new migration is legitimate, so the guard fires only when the
# target file already exists.
set -uo pipefail

f=$(jq -r '.tool_input.file_path // empty')

case "$f" in
  */db/migration/V*.sql)
    if [ -f "$f" ]; then
      jq -nc --arg p "$(basename "$f")" '{
        hookSpecificOutput: {
          hookEventName: "PreToolUse",
          permissionDecision: "deny",
          permissionDecisionReason: ("\($p) is an existing Flyway migration. Flyway stores a checksum of every applied migration, so editing this file breaks startup validation on every database it has already run against. Add a new V<n>__<description>.sql instead. Remember SQLite cannot ALTER most schema - follow the rebuild-and-copy pattern in V2__rule_scope_surrogate_id.sql. If changing the applied file is genuinely what is wanted, ask the user to confirm rather than retrying.")
        }
      }'
    fi
    ;;
esac

exit 0
