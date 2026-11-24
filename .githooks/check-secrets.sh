#!/bin/sh
# Guard 2 for the hard rule: this public repository contains no secrets.
#
# Usage:
#   check-secrets.sh --cached          scan the files staged for the next commit
#   check-secrets.sh --range <rev>...  scan commits in range that are on no remote
#   check-secrets.sh --all             scan every commit in the repository
#
# Guard 1 is .gitignore. Guard 3 is the secret scan in CI.
# A false positive is cheap. A leaked key is not. So the patterns are broad.
# A hit prints the file and the line number only, never the line itself.

set -u

# File names that are never allowed in the repository.
BLOCKED_NAMES='(^|/)(google-services\.json|keystore\.properties|key\.properties|signing\.properties|\.env(\..*)?|secrets?(/.*)?|credentials(/.*)?)$|(^|/)[^/]*\.(jks|keystore|p12|pfx|pem|key|ppk)$|adminsdk|service[-_]?account[^/]*\.json$'

# Text patterns that are never allowed in the repository.
# Documentation must use placeholders that do not match, for example <AUTH_KEY>.
SECRET_TEXT='tskey-(auth|client)-[A-Za-z0-9]{8,}'
SECRET_TEXT="$SECRET_TEXT|AIza[0-9A-Za-z_-]{35}"
SECRET_TEXT="$SECRET_TEXT|-----BEGIN ([A-Z]+ )*PRIVATE KEY-----"
SECRET_TEXT="$SECRET_TEXT|gh[pousr]_[A-Za-z0-9]{30,}"
SECRET_TEXT="$SECRET_TEXT|github_pat_[A-Za-z0-9_]{20,}"
SECRET_TEXT="$SECRET_TEXT|AKIA[0-9A-Z]{16}"
SECRET_TEXT="$SECRET_TEXT|xox[baprs]-[A-Za-z0-9-]{10,}"
SECRET_TEXT="$SECRET_TEXT|\bsk-(proj-)?[A-Za-z0-9_-]{24,}"
SECRET_TEXT="$SECRET_TEXT|\"private_key\"[[:space:]]*:[[:space:]]*\""
SECRET_TEXT="$SECRET_TEXT|\"client_secret\"[[:space:]]*:[[:space:]]*\"[A-Za-z0-9_-]{12,}\""
SECRET_TEXT="$SECRET_TEXT|\"refresh_token\"[[:space:]]*:[[:space:]]*\"[A-Za-z0-9/_-]{20,}\""
SECRET_TEXT="$SECRET_TEXT|Bearer [A-Za-z0-9._~+/-]{24,}"

# $1 = file name. Returns 0 when the name is on the block list.
name_blocked() {
  printf '%s\n' "$1" | grep -E -q -i "$BLOCKED_NAMES"
}

# $1 = file name. Returns 0 when the content has a secret pattern.
content_blocked() {
  git show ":$1" 2>/dev/null | grep -a -E -q "$SECRET_TEXT"
}

scan_cached() {
  git diff --cached --name-only --diff-filter=ACMR | while IFS= read -r f; do
    [ -n "$f" ] || continue
    if name_blocked "$f"; then
      echo "BLOCKED name: $f"
      echo "  This file name is on the block list. Keep the file outside the repository."
      echo "  Unstage it with: git restore --staged -- \"$f\""
      exit 1
    fi
    if content_blocked "$f"; then
      echo "BLOCKED content: $f"
      echo "  Line(s): $(git show ":$f" 2>/dev/null | grep -a -n -E "$SECRET_TEXT" | cut -d: -f1 | head -5 | tr '\n' ' ')"
      echo "  The content matches a secret pattern. Remove the secret, then stage again."
      exit 1
    fi
  done
}

# $1 = commit. Returns 0 when the commit adds a blocked file or a secret pattern.
commit_blocked() {
  c="$1"
  git diff-tree --no-commit-id --name-only -r --diff-filter=ACMR "$c" | while IFS= read -r f; do
    [ -n "$f" ] || continue
    if name_blocked "$f"; then
      echo "BLOCKED name in commit $(git log -1 --format=%h "$c"): $f"
      exit 1
    fi
  done
  added=$(git show --format= --unified=0 --no-color "$c" | grep -a -E '^\+' | grep -a -v -E '^\+\+\+ ' | grep -a -E "$SECRET_TEXT" | wc -l)
  if [ "$added" -gt 0 ]; then
    echo "BLOCKED content in commit $(git log -1 --format=%h "$c"): $added line(s) with a secret pattern"
    echo "  Files: $(git diff-tree --no-commit-id --name-only -r --diff-filter=ACMR "$c" | tr '\n' ' ')"
    return 1
  fi
  return 0
}

scan_commits() {
  for c in $(git rev-list "$@"); do
    commit_blocked "$c" || exit 1
  done
}

case "${1:-}" in
  --cached)
    scan_cached
    ;;
  --range)
    shift
    scan_commits "$@" --not --remotes
    ;;
  --all)
    scan_commits --all
    ;;
  *)
    echo "usage: check-secrets.sh --cached | --range <rev>... | --all" >&2
    exit 2
    ;;
esac
