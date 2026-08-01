#!/usr/bin/env bash
#
# Encrypt data/keywords.txt -> data/keywords.txt.age.
#
# The plaintext is gitignored (personal vocabulary, public repo); the .age file
# is what gets committed. Encryption needs the *public* recipient key only —
# no private identity is read, copied or required here.
#
# Run this after every edit to data/keywords.txt, then commit the .age file.
set -euo pipefail

# Public key. Safe to keep in the repo; it can only encrypt.
RECIPIENT="age1aqdf22l6p03g408sg9m9jxu6hwmml0vn9sr7jukff0ty35dwsuuswv9ak4"

REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
PLAINTEXT="${1:-$REPO_ROOT/data/keywords.txt}"
CIPHERTEXT="$REPO_ROOT/data/keywords.txt.age"

die() {
    printf 'keywords-encrypt: %s\n' "$1" >&2
    exit 1
}

command -v age >/dev/null 2>&1 || die "\`age\` is not on PATH.
  Install it:  brew install age        (or https://age-encryption.org)"

[ -f "$PLAINTEXT" ] || die "no plaintext to encrypt at
    $PLAINTEXT
  Restore it from the committed ciphertext with
    ./scripts/keywords-decrypt.sh
  or write it by hand: one term per line, '#' comments and blank lines allowed."

[ -r "$PLAINTEXT" ] || die "cannot read $PLAINTEXT (permissions?)"

mkdir -p -- "$(dirname -- "$CIPHERTEXT")"

# Encrypt to a temp file and move it into place, so a failure halfway through
# cannot leave a truncated .age file where the good one used to be.
TMP="$(mktemp "${CIPHERTEXT}.XXXXXX")"
trap 'rm -f -- "$TMP"' EXIT

age -r "$RECIPIENT" -o "$TMP" -- "$PLAINTEXT" \
    || die "age failed to encrypt $PLAINTEXT"

chmod 644 "$TMP"   # mktemp makes it 0600; this file is committed, not secret
mv -f -- "$TMP" "$CIPHERTEXT"
trap - EXIT

printf 'keywords-encrypt: %s -> %s (%s terms)\n' \
    "${PLAINTEXT#"$REPO_ROOT"/}" \
    "${CIPHERTEXT#"$REPO_ROOT"/}" \
    "$(grep -cEv '^[[:space:]]*(#|$)' -- "$PLAINTEXT" || true)"
printf 'keywords-encrypt: age output is randomised — the .age file changes on\n'
printf '                  every run even when the terms did not. Commit it anyway.\n'
