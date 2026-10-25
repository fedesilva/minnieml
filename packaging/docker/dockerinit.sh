#!/bin/bash
set -euo pipefail

case "${1:-}" in
  arm64|amd64) builder_service="mml-linux-$1" ;;
  *) echo "Usage: $0 {arm64|amd64}" >&2; exit 2 ;;
esac

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
project_root="$(cd "$script_dir/../.." && pwd)"

docker compose --project-directory "$project_root" build "$builder_service"
echo "Builder ready. Open it with: ./packaging/docker/linux-builder-shell.sh $1"
