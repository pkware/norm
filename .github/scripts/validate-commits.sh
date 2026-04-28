#!/usr/bin/env bash
set -euo pipefail

BASE_REF="${1:?Usage: validate-commits.sh <base-ref>}"
PATTERN='^(feat|fix|chore|deps|docs|ci|refactor|test)(\([^)]+\))?!?: .+'

failed=0
while IFS= read -r msg; do
  # Skip fixup, squash, and merge commits — present during review, squashed before rebase
  if [[ "$msg" =~ ^(fixup!|squash!|Merge) ]]; then
    continue
  fi
  if ! [[ "$msg" =~ $PATTERN ]]; then
    echo "Invalid commit message: \"$msg\""
    echo "  Expected: <type>: <description>"
    echo "  Valid types: feat, fix, chore, deps, docs, ci, refactor, test"
    failed=1
  fi
done < <(git log --format="%s" "origin/${BASE_REF}..HEAD")

exit $failed
