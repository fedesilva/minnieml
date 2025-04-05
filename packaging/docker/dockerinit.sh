#!/bin/bash
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" &>/dev/null && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../../" &>/dev/null && pwd)"

# Build the Docker image with the name matching docker-compose.yml
docker build -t ubuntu-graal-sbt "${SCRIPT_DIR}"

echo "Docker image built successfully. To use it:"
echo "1. Run './packaging/docker/linux-builder-shell.sh' to get a shell"
echo "2. Run './packaging/docker/linux-builder-distro.sh' to build the distro"