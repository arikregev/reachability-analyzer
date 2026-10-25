#!/usr/bin/env bash
# Mock identity provider for local runs: mints RS256 bearer tokens that reachability-api accepts under the
# "local" profile. The key pair is generated on first use in deploy/local/.keys (git-ignored). Local use only.
#
#   deploy/local/token.sh --public-key        print the public key for REACH_LOCAL_PUBLIC_KEY
#   deploy/local/token.sh [role ...]          print a token; default roles: scan-submitter results-reader
set -euo pipefail

dir="$(cd "$(dirname "$0")" && pwd)/.keys"
mkdir -p "$dir"
if [[ ! -f "$dir/private.pem" ]]; then
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$dir/private.pem" 2>/dev/null
  openssl pkey -in "$dir/private.pem" -pubout -out "$dir/public.pem"
fi

if [[ "${1:-}" == "--public-key" ]]; then
  grep -v -- '-----' "$dir/public.pem" | tr -d '\n'
  echo
  exit 0
fi

roles=("$@")
if [[ ${#roles[@]} -eq 0 ]]; then
  roles=(scan-submitter results-reader)
fi

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }
groups=$(printf '"%s",' "${roles[@]}")
now=$(date +%s)
header='{"alg":"RS256","typ":"JWT"}'
payload=$(printf '{"iss":"reach-local","sub":"local-user","aud":"reachability-api","iat":%d,"exp":%d,"groups":[%s]}' \
  "$now" "$((now + 3600))" "${groups%,}")
unsigned="$(printf '%s' "$header" | b64url).$(printf '%s' "$payload" | b64url)"
signature=$(printf '%s' "$unsigned" | openssl dgst -sha256 -sign "$dir/private.pem" -binary | b64url)
echo "$unsigned.$signature"
