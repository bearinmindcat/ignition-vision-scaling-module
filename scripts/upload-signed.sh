#!/usr/bin/env bash
# Builds the signed module for a release tag and attaches it to that GitHub release, next to the
# unsigned module the Release workflow already attached.
#
#   scripts/upload-signed.sh v0.1.5
#
# Needs your signing keystore: the ignition.signing.* properties (keystoreFile, keystorePassword,
# certFile, certAlias, certPassword) in ~/.gradle/gradle.properties, and the GitHub CLI signed in.
set -euo pipefail

tag="${1:?usage: scripts/upload-signed.sh vX.Y.Z}"
cd "$(dirname "$0")/.."

if ! grep -q '^ignition\.signing\.keystoreFile' "$HOME/.gradle/gradle.properties" 2>/dev/null; then
  echo "No ignition.signing.* properties in ~/.gradle/gradle.properties, so the module can't be signed." >&2
  exit 1
fi
if [ -n "$(git status --porcelain)" ]; then
  echo "Commit or stash your changes first (the script checks out $tag)." >&2
  exit 1
fi

git fetch --quiet --tags
back="$(git rev-parse --abbrev-ref HEAD)"
git checkout --quiet "$tag"
trap 'git checkout --quiet "$back"' EXIT

./gradlew clean build -Pversion="${tag#v}"
gh release upload "$tag" build/Vision-Scaling-Fix.modl --clobber
echo "Attached the signed build/Vision-Scaling-Fix.modl to release $tag."
