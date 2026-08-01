#!/usr/bin/env bash
#
# Decrypt data/keywords.txt.age -> data/keywords.txt (gitignored plaintext).
#
# Usage:
#   ./scripts/keywords-decrypt.sh                 # -> data/keywords.txt
#   ./scripts/keywords-decrypt.sh -f              # overwrite an existing one
#   ./scripts/keywords-decrypt.sh /tmp/check.txt  # somewhere else (round-trip test)
#
# The identity (private key) defaults to ~/.config/chezmoi/key.txt and can be
# pointed elsewhere with LIVETYPE_AGE_IDENTITY. Without it this cannot work —
# that is the point of committing only the ciphertext.
set -euo pipefail

REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
CIPHERTEXT="$REPO_ROOT/data/keywords.txt.age"
IDENTITY="${LIVETYPE_AGE_IDENTITY:-$HOME/.config/chezmoi/key.txt}"

die() {
    printf 'keywords-decrypt: %s\n' "$1" >&2
    exit 1
}

FORCE=0
OUTPUT=""
while [ "$#" -gt 0 ]; do
    case "$1" in
        -f|--force) FORCE=1 ;;
        -h|--help)
            cat <<'USAGE'
Decrypt data/keywords.txt.age -> data/keywords.txt (gitignored plaintext).

  ./scripts/keywords-decrypt.sh                 # -> data/keywords.txt
  ./scripts/keywords-decrypt.sh -f              # overwrite an existing one
  ./scripts/keywords-decrypt.sh /tmp/check.txt  # somewhere else (round-trip test)

Identity: $LIVETYPE_AGE_IDENTITY, default ~/.config/chezmoi/key.txt
USAGE
            exit 0
            ;;
        -*) die "unknown option '$1' (see --help)" ;;
        *)
            [ -z "$OUTPUT" ] || die "more than one output path given"
            OUTPUT="$1"
            ;;
    esac
    shift
done
OUTPUT="${OUTPUT:-$REPO_ROOT/data/keywords.txt}"

command -v age >/dev/null 2>&1 || die "\`age\` is not on PATH.
  Install it:  brew install age        (or https://age-encryption.org)"

[ -f "$CIPHERTEXT" ] || die "no ciphertext at
    $CIPHERTEXT
  It should be committed to the repo — check you are on a complete checkout."

[ -f "$IDENTITY" ] || die "no age identity at
    $IDENTITY
  This file holds the private key and is deliberately not in the repo. Point
  LIVETYPE_AGE_IDENTITY at yours, e.g.
    LIVETYPE_AGE_IDENTITY=~/.age/key.txt ./scripts/keywords-decrypt.sh
  Without the key the keyword list cannot be recovered; the app builds fine
  without it (the debug default just falls back to res/values/strings.xml)."

if [ -e "$OUTPUT" ] && [ "$FORCE" -ne 1 ]; then
    die "$OUTPUT already exists.
  Refusing to overwrite local edits. Re-run with --force to replace it, or give
  a different output path."
fi

mkdir -p -- "$(dirname -- "$OUTPUT")"

TMP="$(mktemp "${OUTPUT}.XXXXXX")"
trap 'rm -f -- "$TMP"' EXIT

age -d -i "$IDENTITY" -o "$TMP" -- "$CIPHERTEXT" \
    || die "age failed to decrypt $CIPHERTEXT with identity $IDENTITY
  Wrong key? The file is encrypted to
    age1aqdf22l6p03g408sg9m9jxu6hwmml0vn9sr7jukff0ty35dwsuuswv9ak4"

mv -f -- "$TMP" "$OUTPUT"
trap - EXIT

printf 'keywords-decrypt: wrote %s (%s terms)\n' \
    "${OUTPUT#"$REPO_ROOT"/}" \
    "$(grep -cEv '^[[:space:]]*(#|$)' -- "$OUTPUT" || true)"
