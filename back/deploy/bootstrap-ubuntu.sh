#!/usr/bin/env bash
# Backward-compatible Bash entry point for the native deployment script.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec bash "${SCRIPT_DIR}/deploy-ubuntu.sh" "$@"
