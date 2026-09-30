#!/bin/sh
# Ed25519 signing keys for Surge tokens.
#
#   keys.sh [DIR]          create a key pair per token type, only if none exists
#   keys.sh --rotate [DIR] add a new key per type; the old one becomes "previous"
#
# Layout (DIR defaults to ./secrets/jwt):
#   DIR/<type>/<kid>.key   PKCS#8 private key (Admission only)
#   DIR/<type>/<kid>.pub   SPKI public key    (gateway keyset)
#   DIR/<type>/current     kid used for signing
#   DIR/<type>/previous    kid still accepted by the gateway (after a rotation)
#
# Types: admission, session. They never share a kid, so the gateway can bind a
# token to its route by typ and kid.
#
# KEY_OWNER=uid[:gid] chowns the result (used by the compose keygen container).
set -eu

rotate=0
if [ "${1:-}" = "--rotate" ]; then rotate=1; shift; fi
dir="${1:-./secrets/jwt}"

new_key() {
  type_dir="$1"; type="$2"
  kid="${type}-$(date -u +%Y%m%dT%H%M%SZ)-$(openssl rand -hex 3)"
  umask 077
  openssl genpkey -algorithm ed25519 -out "$type_dir/$kid.key"
  openssl pkey -in "$type_dir/$kid.key" -pubout -out "$type_dir/$kid.pub"
  chmod 644 "$type_dir/$kid.pub"
  echo "$kid"
}

for type in admission session; do
  type_dir="$dir/$type"
  mkdir -p "$type_dir"
  if [ -f "$type_dir/current" ] && [ "$rotate" -eq 0 ]; then
    echo "$type: key $(cat "$type_dir/current") exists, leaving it"
    continue
  fi
  kid="$(new_key "$type_dir" "$type")"
  if [ -f "$type_dir/current" ]; then
    old="$(cat "$type_dir/current")"
    # Keep exactly one previous key; anything older is no longer accepted.
    if [ -f "$type_dir/previous" ]; then
      stale="$(cat "$type_dir/previous")"
      rm -f "$type_dir/$stale.key" "$type_dir/$stale.pub"
    fi
    echo "$old" > "$type_dir/previous"
  fi
  echo "$kid" > "$type_dir/current"
  echo "$type: created key $kid"
done

# Service images run as a fixed non-root uid (10001); let it read the keys.
if [ -n "${KEY_OWNER:-}" ]; then chown -R "$KEY_OWNER" "$dir"; fi
