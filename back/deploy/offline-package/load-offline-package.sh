#!/usr/bin/env bash
# Load an offline image package and start the project on an Ubuntu server.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="/root/back"
NO_START=false
USE_SUDO_DOCKER=false

usage() {
  cat <<'EOF'
Usage: bash load-offline-package.sh [options]

Options:
  --project-dir DIR  Where to place/run the project. Default: /root/back
  --no-start         Load images and copy project files, then exit.
  -h, --help         Show this help.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --project-dir)
      [[ $# -ge 2 ]] || { echo '--project-dir requires a directory path.' >&2; usage; exit 2; }
      PROJECT_DIR="$2"
      shift
      ;;
    --no-start) NO_START=true ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage; exit 2 ;;
  esac
  shift
done

stage() { printf '\n==> %s\n' "$1"; }
ok() { printf '[OK] %s: %s\n' "$1" "$2"; }
warn() { printf '[WARN] %s: %s\n' "$1" "$2" >&2; }
fail() { printf '[ERROR] %s\n' "$1" >&2; exit 1; }
command_exists() { command -v "$1" >/dev/null 2>&1; }
run_docker() {
  if [[ "$USE_SUDO_DOCKER" == true ]]; then sudo docker "$@"; else docker "$@"; fi
}

if [[ "$(uname -s)" != "Linux" ]] || ! grep -qx 'ID=ubuntu' /etc/os-release; then fail 'This script supports Ubuntu Linux only.'; fi
if [[ "$(uname -m)" != "x86_64" ]]; then fail 'This project currently supports x86_64 hosts only.'; fi
command_exists docker || fail 'Docker Engine is required before loading the offline package.'
if docker info >/dev/null 2>&1; then
  USE_SUDO_DOCKER=false
elif sudo docker info >/dev/null 2>&1; then
  USE_SUDO_DOCKER=true
else
  fail 'Docker is installed but not running or not accessible.'
fi
run_docker compose version >/dev/null 2>&1 || fail 'Docker Compose plugin is required before loading the offline package.'

IMAGE_ARCHIVE=""
if [[ -f "${SCRIPT_DIR}/images/intelligence-platform-images.tar.gz" ]]; then
  IMAGE_ARCHIVE="${SCRIPT_DIR}/images/intelligence-platform-images.tar.gz"
elif [[ -f "${SCRIPT_DIR}/images/intelligence-platform-images.tar" ]]; then
  IMAGE_ARCHIVE="${SCRIPT_DIR}/images/intelligence-platform-images.tar"
else
  fail "Offline image archive was not found in ${SCRIPT_DIR}/images."
fi
[[ -d "${SCRIPT_DIR}/project" ]] || fail "Project files were not found in ${SCRIPT_DIR}/project."

stage 'Loading Docker images'
if [[ "$IMAGE_ARCHIVE" == *.gz ]]; then
  command_exists gzip || fail 'gzip is required to load .tar.gz image archive.'
  gzip -dc "$IMAGE_ARCHIVE" | run_docker load
else
  run_docker load -i "$IMAGE_ARCHIVE"
fi

stage 'Copying project files'
sudo mkdir -p "$PROJECT_DIR"
if command_exists rsync; then
  sudo rsync -a "${SCRIPT_DIR}/project/" "${PROJECT_DIR}/"
else
  (cd "${SCRIPT_DIR}/project" && tar -cf - .) | sudo tar -C "$PROJECT_DIR" -xf -
fi
sudo chown -R "$(id -u):$(id -g)" "$PROJECT_DIR" 2>/dev/null || true
ok 'Project directory' "$PROJECT_DIR"

stage 'Preparing .env'
cd "$PROJECT_DIR"
if [[ ! -f .env ]]; then
  [[ -f .env.example ]] || fail '.env and .env.example are both missing.'
  password="$(od -An -N12 -tx1 /dev/urandom | tr -d ' \n')"
  sed "s/^MYSQL_ROOT_PASSWORD=.*/MYSQL_ROOT_PASSWORD=${password}/" .env.example > .env
  chmod 600 .env
  ok '.env' 'created with a generated MySQL password'
else
  ok '.env' 'existing file preserved'
fi

run_docker compose config -q
ok 'Compose configuration' 'valid'

for port in 80 3306 8080 8101; do
  if ss -ltnH "sport = :${port}" 2>/dev/null | grep -q .; then
    warn "TCP port ${port}" 'already in use; compose may fail if this is not an existing deployment.'
  else
    ok "TCP port ${port}" 'available'
  fi
done

if [[ "$NO_START" == true ]]; then
  printf '\nImages were loaded and project files were copied. No services were started because --no-start was specified.\n'
  exit 0
fi

stage 'Starting services without building'
run_docker compose up -d --no-build --remove-orphans

stage 'Waiting for backend health check'
deadline=$((SECONDS + 300))
backend_healthy=false
while (( SECONDS < deadline )); do
  if curl -fsS --max-time 5 http://127.0.0.1:8080/api/health >/dev/null; then backend_healthy=true; break; fi
  sleep 5
done
if [[ "$backend_healthy" == false ]]; then
  run_docker compose ps
  run_docker compose logs --tail 100 backend
  fail 'Deployment failed health verification.'
fi

curl -fsS --max-time 15 http://127.0.0.1/ >/dev/null || fail 'Frontend health verification failed.'
run_docker compose ps
host_ip="$(hostname -I | awk '{print $1}')"
printf '\nOffline deployment completed.\nWeb UI: http://%s/\nAPI:    http://%s:8080/api/health\n' "$host_ip" "$host_ip"
