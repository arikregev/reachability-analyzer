#!/usr/bin/env bash
# Tests fetch.sh against local mocks: a git repository with a submodule (instead of GitHub) and a small HTTP
# server that accepts PUT (instead of the presigned S3 URL). Needs git, tar, zstd, curl and python3.
#   images/source-fetch/test.sh
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
tmp="$(mktemp -d)"
trap 'kill "${server_pid:-0}" 2>/dev/null || true; rm -rf "$tmp"' EXIT
failures=0

git_quiet() { git -c user.name=t -c user.email=t@example.invalid -c init.defaultBranch=main "$@" >/dev/null 2>&1; }

# Upstream repositories: lib (the submodule) and app.
git_quiet init "$tmp/lib" && echo 'lib' >"$tmp/lib/lib.txt"
git_quiet -C "$tmp/lib" add . && git_quiet -C "$tmp/lib" commit -m lib
git_quiet init "$tmp/app" && echo 'app' >"$tmp/app/app.txt"
git_quiet -C "$tmp/app" -c protocol.file.allow=always submodule add "$tmp/lib" vendor/lib
git_quiet -C "$tmp/app" add . && git_quiet -C "$tmp/app" commit -m app
for r in lib app; do git -C "$tmp/$r" config uploadpack.allowAnySHA1InWant true; done
commit="$(git -C "$tmp/app" rev-parse HEAD)"

# Mock S3: stores each PUT body under $tmp/s3, 503 for paths under /down.
mkdir -p "$tmp/s3"
cat >"$tmp/s3.py" <<'PY'
import http.server, os, sys
root = sys.argv[2]
class H(http.server.BaseHTTPRequestHandler):
    def do_PUT(self):
        body = self.rfile.read(int(self.headers["Content-Length"]))
        if self.path.startswith("/down"):
            self.send_response(503); self.end_headers(); return
        with open(os.path.join(root, "upload"), "wb") as f:
            f.write(body)
        self.send_response(200); self.end_headers()
    def log_message(self, *a):
        pass
http.server.ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1])), H).serve_forever()
PY
port=$((20000 + RANDOM % 20000))
python3 "$tmp/s3.py" "$port" "$tmp/s3" &
server_pid=$!
for _ in $(seq 50); do curl -s -o /dev/null "http://127.0.0.1:$port/" && break || sleep 0.1; done

run() {
  local name="$1" expected="$2" sha="$3" put_path="$4" max="${5:-5368709120}"
  rm -rf "$tmp/work" "$tmp/secrets" "$tmp/s3/upload" && mkdir -p "$tmp/work" "$tmp/secrets"
  echo "ghs_test" >"$tmp/secrets/github-token"
  echo "http://127.0.0.1:$port$put_path" >"$tmp/secrets/source-put-url"
  set +e
  REPO_URL="$tmp/app" GIT_HOST=github.com COMMIT_SHA="$sha" MAX_TARBALL_BYTES="$max" \
    REACH_SECRETS_DIR="$tmp/secrets" WORK_DIR="$tmp/work" REACH_FETCH_TEST_ALLOW_FILE=1 \
    "$here/fetch.sh" 2>"$tmp/log"
  local code=$?
  set -e
  if [[ "$code" == "$expected" ]]; then
    echo "ok   $name (exit $code)"
  else
    echo "FAIL $name: exit $code, expected $expected"; sed 's/^/     /' "$tmp/log"; failures=$((failures + 1))
  fi
}

run "clones the commit with submodules and uploads" 0 "$commit" /work/source.tar.zst
listing="$(tar --zstd -tf "$tmp/s3/upload")"
for f in ./app.txt ./vendor/lib/lib.txt; do
  if grep -qx "$f" <<<"$listing"; then echo "ok   tarball has $f"; else echo "FAIL tarball lacks $f"; failures=$((failures + 1)); fi
done
if grep -q '\.git/' <<<"$listing"; then echo "FAIL tarball contains .git"; failures=$((failures + 1)); else echo "ok   tarball has no .git"; fi
if grep -q ghs_test "$tmp/log"; then echo "FAIL token in the log"; failures=$((failures + 1)); else echo "ok   token not logged"; fi

run "unknown commit is COMMIT_NOT_FOUND" 10 "$(printf '1%.0s' $(seq 40))" /work/source.tar.zst
run "tarball over the limit is SOURCE_TOO_LARGE" 11 "$commit" /work/source.tar.zst 10
run "failed upload is S3_UNAVAILABLE" 13 "$commit" /down/source.tar.zst

if (( failures > 0 )); then
  echo "$failures failure(s)"; exit 1
fi
echo "all passed"
