#!/usr/bin/env bash
# source-fetch Job (architecture.md 3.3, D-008): clone one commit with its submodules, pack it, PUT it to S3.
#
# Inputs (set by scan-controller's JobLauncher):
#   REPO_URL, GIT_HOST, COMMIT_SHA, MAX_TARBALL_BYTES     environment
#   $REACH_SECRETS_DIR/github-token                       GitHub App installation token (contents: read)
#   $REACH_SECRETS_DIR/source-put-url                     presigned S3 PUT for source.tar.zst
#
# Exit codes, mapped to scan reason codes by the controller's JobWatcher:
#   10 COMMIT_NOT_FOUND   11 SOURCE_TOO_LARGE   12 GITHUB_UNAVAILABLE   13 S3_UNAVAILABLE
# Any other non-zero code is treated as GITHUB_UNAVAILABLE (retryable).
set -euo pipefail

secrets="${REACH_SECRETS_DIR:-/var/run/reach}"
work="${WORK_DIR:-/work}"
src="$work/src"
tarball="$work/source.tar.zst"
: "${REPO_URL:?}" "${GIT_HOST:?}" "${COMMIT_SHA:?}"
max_bytes="${MAX_TARBALL_BYTES:-5368709120}"
token="$(cat "$secrets/github-token")"

log() { printf '%s source-fetch: %s\n' "$(date -u +%FT%TZ)" "$*" >&2; }

# The token travels only as an HTTP header scoped to the GitHub host, so it is never written to .git/config,
# never sent to another host, and never appears in a URL or the process list.
auth="$(printf 'x-access-token:%s' "$token" | base64 | tr -d '\n')"
export GIT_CONFIG_COUNT=3
export GIT_CONFIG_KEY_0="http.https://${GIT_HOST}/.extraHeader" GIT_CONFIG_VALUE_0="Authorization: Basic ${auth}"
export GIT_CONFIG_KEY_1="protocol.allow" GIT_CONFIG_VALUE_1="never"
export GIT_CONFIG_KEY_2="protocol.https.allow" GIT_CONFIG_VALUE_2="always"
export GIT_TERMINAL_PROMPT=0 GIT_LFS_SKIP_SMUDGE=1
# Only https is allowed, so a hostile .gitmodules cannot use file://, ssh or ext::. test.sh alone sets
# REACH_FETCH_TEST_ALLOW_FILE to run against local repositories.
if [[ -n "${REACH_FETCH_TEST_ALLOW_FILE:-}" ]]; then
  export GIT_CONFIG_COUNT=4 GIT_CONFIG_KEY_3="protocol.file.allow" GIT_CONFIG_VALUE_3="always"
fi

git_fail() {
  local out="$1"
  if grep -qiE "not found|repository .* does not exist|couldn't find remote ref|not our ref|unadvertised object" <<<"$out"; then
    log "commit or repository not found"
    exit 10
  fi
  log "git failed: $(tail -n 3 <<<"$out")"
  exit 12
}

log "fetching ${REPO_URL}@${COMMIT_SHA}"
rm -rf "$src" && mkdir -p "$src"
git -C "$src" init -q
git -C "$src" remote add origin "$REPO_URL"
out="$(git -C "$src" fetch -q --depth 1 origin "$COMMIT_SHA" 2>&1)" || git_fail "$out"
out="$(git -C "$src" -c advice.detachedHead=false checkout -q FETCH_HEAD 2>&1)" || git_fail "$out"
if [[ -f "$src/.gitmodules" ]]; then
  out="$(git -C "$src" submodule update -q --init --recursive --depth 1 2>&1)" || git_fail "$out"
fi

log "packing"
tar --zstd --exclude-vcs -cf "$tarball" -C "$src" .
size="$(stat -c %s "$tarball")"
if (( size > max_bytes )); then
  log "tarball is ${size} bytes, over the ${max_bytes}-byte single-PUT limit"
  exit 11
fi

log "uploading ${size} bytes"
if ! curl -fsS --retry 3 --retry-all-errors -X PUT -H "Content-Type: application/zstd" \
     --upload-file "$tarball" "$(cat "$secrets/source-put-url")" -o /dev/null; then
  log "upload failed"
  exit 13
fi
log "done"
