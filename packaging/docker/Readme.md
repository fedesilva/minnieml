# Linux builders

Both builders use the shared Dockerfile. Their service, image, and container names identify
both the operating system and architecture:

| Name | Docker platform | Clang target architecture |
| --- | --- | --- |
| `mml-linux-arm64` | `linux/arm64` | `aarch64` |
| `mml-linux-amd64` | `linux/amd64` | `x86_64` |

Build and open the desired architecture from the repository root:

```sh
./packaging/docker/dockerinit.sh arm64
./packaging/docker/linux-builder-shell.sh arm64

./packaging/docker/dockerinit.sh amd64
./packaging/docker/linux-builder-shell.sh amd64
```

The scripts require an architecture. Compose profiles keep an unqualified `up` from starting
both builders. Explicit service commands also work:

```sh
docker compose up -d mml-linux-amd64
docker compose exec mml-linux-amd64 uname -m
docker compose exec mml-linux-amd64 clang -dumpmachine
docker compose exec mml-linux-amd64 sbtn test
docker compose stop mml-linux-amd64
```

Sources are bind-mounted at `/workspace`. Each container has separate Docker volumes for
sbt targets and the compiler's default `build` directory. Build outputs in those directories
stay in the container volumes; source edits are visible on the host. Do not run host and
container build commands concurrently. Benchmark outputs outside those directories remain
shared and must be cleaned before switching architectures.

Use `sbtn` inside the builders, following [the development tools guide](../../context/dev-tools.md).
On an ARM host, amd64 execution uses Docker's emulation. Record it as emulated Linux x86-64
execution, separately from cross-compilation and native hardware execution. Emulated timings
are unsuitable for native performance comparisons.
