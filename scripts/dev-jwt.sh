#!/usr/bin/env bash
# Local JWT key material and tokens for the compose stack.
#
# The services verify RS256 tokens (SecurityConfig), but nothing issues them yet — POST /auth/login
# arrives with RBAC in v2 (SPEC.md §11.1). Until then this script stands in for the issuer so the
# stack can be exercised end to end.
#
#   scripts/dev-jwt.sh keys                     generate the keypair, print the .env line
#   scripts/dev-jwt.sh token <user-uuid> [ROLE...]   mint a token for that user
#
# LOCAL DEVELOPMENT ONLY. The private key lands in .dev/, which is git-ignored, and none of this
# may be reused anywhere a real customer record exists.
set -euo pipefail

DEV_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/.dev"
KEY="$DEV_DIR/jwt.key"
PUB="$DEV_DIR/jwt.pub"

# JWT uses base64url without padding; plain base64 would make every token invalid.
b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }

usage() {
    sed -n '2,12p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
    exit 64
}

cmd_keys() {
    mkdir -p "$DEV_DIR"
    if [ -f "$KEY" ]; then
        echo "Keypair already present at $KEY — reusing it." >&2
    else
        openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$KEY" 2>/dev/null
        openssl rsa -in "$KEY" -pubout -out "$PUB" 2>/dev/null
        chmod 600 "$KEY"
        echo "Wrote $KEY and $PUB" >&2
    fi
    # SecurityConfig strips the PEM armour and all whitespace, so the key travels as one line and
    # needs no volume mount or multi-line env var.
    echo >&2
    echo "Add this line to .env:" >&2
    echo "CLIENT360_JWT_PUBLIC_KEY=$(grep -v -- '-----' "$PUB" | tr -d '\n')"
}

cmd_token() {
    [ $# -ge 1 ] || usage
    local sub="$1"
    shift
    [ -f "$KEY" ] || { echo "No key yet — run: scripts/dev-jwt.sh keys" >&2; exit 1; }

    local roles='"MANAGER"'
    if [ $# -gt 0 ]; then
        roles=$(printf '"%s",' "$@"); roles="${roles%,}"
    fi

    local now exp header payload signing_input signature
    now=$(date +%s)
    exp=$((now + 28800)) # 8 hours, the RB-BR-09 break-glass ceiling; long enough for one session
    header='{"alg":"RS256","typ":"JWT"}'
    payload=$(printf \
        '{"sub":"%s","email":"%s@bank.example","name":"Local Dev","roles":[%s],"iat":%s,"exp":%s}' \
        "$sub" "${sub%%-*}" "$roles" "$now" "$exp")

    signing_input="$(printf '%s' "$header" | b64url).$(printf '%s' "$payload" | b64url)"
    signature=$(printf '%s' "$signing_input" | openssl dgst -sha256 -sign "$KEY" | b64url)
    printf '%s.%s\n' "$signing_input" "$signature"
}

case "${1:-}" in
    keys) cmd_keys ;;
    token) shift; cmd_token "$@" ;;
    *) usage ;;
esac
