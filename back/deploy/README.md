# Ubuntu Deployment

This deployment uses Docker Compose. Java, Maven, Node.js, Rust, MySQL, and Nginx are built or run inside containers, so they do not need to be installed on the Ubuntu host.

Prerequisites:

- Ubuntu 24.04 x86_64 with internet access to Docker Hub, the Aliyun Docker CE mirror, and the application dependency registries.
- A user that can run `sudo`.
- Recommended: 4 CPU cores, 8 GiB RAM, and 20 GiB free disk.

From the project root on the target machine, run:

```bash
bash deploy/deploy-ubuntu.sh
```

By default, the script treats the parent directory of `deploy/` as the project root and expects `docker-compose.yml` to be there. If your upload directory has an extra nesting level, either run the script from the real project root or pass it explicitly:

```bash
# Find the real project root first.
find /root/back -maxdepth 4 -name docker-compose.yml -print

# Example: use the directory that contains docker-compose.yml.
bash /root/back/deploy/deploy-ubuntu.sh --project-dir /root/back
```

The Bash deployment script:

1. Reports CPU, memory, disk space, Docker availability, and required port conflicts.
2. Installs Docker Engine and the Docker Compose plugin from the Aliyun Docker CE Ubuntu mirror when needed.
3. Preserves an existing `.env`; on a first deployment it creates one with a random MySQL password.
4. Builds and starts the MySQL, backend, knowledge-graph, and frontend services.
5. Verifies the backend health endpoint and frontend HTTP response.

Useful modes:

```bash
# Deploy a project whose docker-compose.yml is not in deploy/..
bash deploy/deploy-ubuntu.sh --project-dir /path/to/project

# Inspect host requirements and project configuration without changing or starting services.
bash deploy/deploy-ubuntu.sh --no-start --skip-install

# Start without rebuilding existing images.
bash deploy/deploy-ubuntu.sh --skip-build

# Recreate containers and rebuild images.
bash deploy/deploy-ubuntu.sh --force-rebuild
```

The deployment publishes ports `80`, `3306`, `8080`, and `8101`; all must be free before the first run. The Python `parser-service` is optional and is not part of the current Docker Compose topology, so advanced local MinerU/Marker/PaddleOCR parsing is not installed by this deployment.
