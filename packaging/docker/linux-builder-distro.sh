#!/bin/bash

# Get the directory of this script and the project root
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" &>/dev/null && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../../" &>/dev/null && pwd)"

# Change to project root
cd "$PROJECT_ROOT"

# Get container name dynamically
CONTAINER_ID=$(docker compose ps -q mml 2>/dev/null)

# Check if container is running
if [ -z "$CONTAINER_ID" ] || ! docker ps | grep -q "$CONTAINER_ID"; then
  # Container not running
  echo "MML container not running, starting it..."
  docker compose up -d
  # Get the container ID after starting
  CONTAINER_ID=$(docker compose ps -q mml)
  
  # Check if container started successfully
  if [ -z "$CONTAINER_ID" ]; then
    echo "Error: Failed to start container or get container ID"
    exit 1
  fi
fi

# Run the build command in the container
echo "Running 'sbt mmlcDistro' in the container..."
docker exec "$CONTAINER_ID" bash -c "cd /workspace && sbt mmlcDistro"

echo "Build completed."