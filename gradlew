#!/bin/sh
# Minimal wrapper for SwapKu.
# CI installs Gradle 8.7 directly and calls `gradle`, so this script is
# only a convenience for local development.
if command -v gradle >/dev/null 2>&1; then
  exec gradle "$@"
else
  echo "Gradle not found. Install Gradle 8.7 or use the GitHub Actions workflow."
  exit 1
fi
