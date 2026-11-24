#!/usr/bin/env bash
# Applies the patches in patches/core/ to the `core` submodule.
#
# `core` is the upstream author's repository, so changes the fork needs live here as patch files
# instead of commits in core. Safe to run repeatedly: a patch that is already applied is skipped,
# and one that no longer applies (core moved on) stops the build instead of being half-applied.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CORE="$ROOT/core"

if [ ! -d "$CORE/media" ]; then
  echo "core/ is empty. Run: git submodule update --init --recursive" >&2
  exit 1
fi

shopt -s nullglob
for patch in "$ROOT"/patches/core/*.patch; do
  name="$(basename "$patch")"
  if git -C "$CORE" apply --reverse --ignore-whitespace --check "$patch" >/dev/null 2>&1; then
    echo "already applied: $name"
  elif git -C "$CORE" apply --ignore-whitespace --check "$patch" >/dev/null 2>&1; then
    git -C "$CORE" apply --ignore-whitespace "$patch"
    echo "applied: $name"
  else
    echo "ERROR: $name does not apply to this version of core. Rebase the patch." >&2
    exit 1
  fi
done
