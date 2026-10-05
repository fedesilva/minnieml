#!/bin/bash
set -euo pipefail

case "${1:-}" in
  arm64|amd64) builder_service="mml-linux-$1" ;;
  *) echo "Usage: $0 {arm64|amd64}" >&2; exit 2 ;;
esac

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
project_root="$(cd "$script_dir/../.." && pwd)"

docker compose --project-directory "$project_root" up -d "$builder_service"
docker compose --project-directory "$project_root" exec "$builder_service" bash
