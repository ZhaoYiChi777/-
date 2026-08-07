#!/usr/bin/env bash
# One-command Docker deployment for the Intelligence Platform on Ubuntu.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEFAULT_PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
PROJECT_DIR="$DEFAULT_PROJECT_DIR"
SKIP_INSTALL=false
SKIP_BUILD=false
NO_START=false
FORCE_REBUILD=false
USE_SUDO_DOCKER=false

usage() {
  cat <<'EOF'
Usage: bash deploy/deploy-ubuntu.sh [options]

Options:
  --project-dir DIR  Project root that contains docker-compose.yml.
  --no-start       Check the host and Compose configuration, then exit.
  --skip-install   Do not install Docker or Docker Compose when missing.
  --skip-build     Start services without rebuilding images.
  --force-rebuild  Rebuild images and recreate containers.
  -h, --help       Show this help.
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
    --skip-install) SKIP_INSTALL=true ;;
    --skip-build) SKIP_BUILD=true ;;
    --force-rebuild) FORCE_REBUILD=true ;;
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

resolve_project_dir() {
  if [[ ! -d "$PROJECT_DIR" ]]; then fail "Project directory does not exist: ${PROJECT_DIR}."; fi
  PROJECT_DIR="$(cd "$PROJECT_DIR" && pwd)"
  if [[ -f "${PROJECT_DIR}/docker-compose.yml" ]]; then return; fi

  if [[ "$PROJECT_DIR" != "$DEFAULT_PROJECT_DIR" ]]; then
    fail "docker-compose.yml was not found in ${PROJECT_DIR}. Pass the directory that contains docker-compose.yml."
  fi

  local candidates=()
  mapfile -t candidates < <(find "$PROJECT_DIR" -mindepth 2 -maxdepth 4 -type f -name docker-compose.yml -printf '%h\n' 2>/dev/null | sort -u)
  if [[ "${#candidates[@]}" -eq 1 ]]; then
    PROJECT_DIR="${candidates[0]}"
    warn 'Project directory' "docker-compose.yml found in ${PROJECT_DIR}; using it as the project root."
    return
  fi

  if [[ "${#candidates[@]}" -eq 0 ]]; then
    fail "docker-compose.yml was not found in ${PROJECT_DIR}. Copy the complete project directory here, or rerun with --project-dir <path>."
  fi

  printf '[ERROR] Multiple docker-compose.yml files were found below %s:\n' "$PROJECT_DIR" >&2
  printf '  %s\n' "${candidates[@]}" >&2
  fail 'Rerun with --project-dir <one of the directories above>.'
}

if [[ "$(uname -s)" != "Linux" ]] || ! grep -qx 'ID=ubuntu' /etc/os-release; then fail 'This script supports Ubuntu Linux only.'; fi
if [[ "$(uname -m)" != "x86_64" ]]; then fail 'This project currently supports x86_64 hosts only.'; fi
resolve_project_dir
if ! command_exists sudo; then fail 'sudo is required for Docker installation and service access.'; fi

printf 'Intelligence Platform Ubuntu deployment\nProject: %s\n' "$PROJECT_DIR"
stage 'Inspecting system resources'
cpu_count="$(nproc)"
memory_gib="$(awk '/MemTotal/ { printf "%.1f", $2 / 1024 / 1024 }' /proc/meminfo)"
free_gib="$(df -Pk "$PROJECT_DIR" | awk 'NR==2 { printf "%.1f", $4 / 1024 / 1024 }')"
[[ "$cpu_count" -ge 4 ]] && ok 'CPU cores' "$cpu_count" || warn 'CPU cores' "$cpu_count (recommended: 4 or more)"
awk "BEGIN { exit !($memory_gib >= 8) }" && ok 'Memory (GiB)' "$memory_gib" || warn 'Memory (GiB)' "$memory_gib (recommended: 8 or more)"
awk "BEGIN { exit !($free_gib >= 20) }" && ok 'Free disk (GiB)' "$free_gib" || warn 'Free disk (GiB)' "$free_gib (recommended: 20 or more)"

stage 'Checking Docker and Docker Compose'
docker_ready=false
if command_exists docker && docker info >/dev/null 2>&1; then
  docker_ready=true
elif command_exists docker && sudo docker info >/dev/null 2>&1; then
  docker_ready=true
  USE_SUDO_DOCKER=true
fi

install_docker_repository() {
  # Use the Aliyun Docker CE APT mirror for faster domestic package downloads.
  sudo apt-get update
  sudo apt-get install -y ca-certificates curl
  sudo install -m 0755 -d /etc/apt/keyrings
  sudo curl -fsSL https://mirrors.aliyun.com/docker-ce/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.gpg
  sudo chmod a+r /etc/apt/keyrings/docker.gpg
  source /etc/os-release
  local architecture
  architecture="$(dpkg --print-architecture)"
  sudo tee /etc/apt/sources.list.d/docker.sources >/dev/null <<EOF
Types: deb
URIs: https://mirrors.aliyun.com/docker-ce/linux/ubuntu
Suites: ${VERSION_CODENAME}
Components: stable
Architectures: ${architecture}
Signed-By: /etc/apt/keyrings/docker.gpg
EOF
  sudo apt-get update
}

if [[ "$docker_ready" == false ]]; then
  [[ "$SKIP_INSTALL" == true ]] && fail 'Docker Engine is unavailable and --skip-install was specified.'
  stage 'Installing Docker from the Aliyun Docker CE mirror'
  install_docker_repository
  sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
  sudo usermod -aG docker "$(id -un)"
  USE_SUDO_DOCKER=true
  warn 'Docker permissions' 'This run uses sudo. Re-login before using Docker without sudo in a later shell.'
fi
ok 'Docker' "$(run_docker --version)"
if ! run_docker compose version >/dev/null 2>&1; then
  [[ "$SKIP_INSTALL" == true ]] && fail 'Docker Compose plugin is unavailable and --skip-install was specified.'
  stage 'Installing Docker Compose plugin'
  install_docker_repository
  sudo apt-get install -y docker-compose-plugin
fi
ok 'Docker Compose' "$(run_docker compose version)"

stage 'Checking project configuration and ports'
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

existing_containers="$(run_docker compose ps -q 2>/dev/null || true)"
for port in 80 3306 8080 8101; do
  if ss -ltnH "sport = :${port}" 2>/dev/null | grep -q .; then
    if [[ -n "$existing_containers" ]]; then warn "TCP port ${port}" 'in use; an existing deployment may own this port';
    else fail "Required host port ${port} is already in use. Resolve the conflict before deploying."; fi
  else ok "TCP port ${port}" 'available'; fi
done

if [[ "$NO_START" == true ]]; then
  printf '\nChecks completed. No services were started because --no-start was specified.\n'
  exit 0
fi

stage 'Building and starting services'
up_args=(compose up -d --remove-orphans)
[[ "$SKIP_BUILD" == false ]] && up_args+=(--build)
[[ "$FORCE_REBUILD" == true ]] && up_args+=(--force-recreate)
run_docker "${up_args[@]}"

stage 'Waiting for backend health check'
deadline=$((SECONDS + 300))
backend_healthy=false
while (( SECONDS < deadline )); do
  if curl -fsS --max-time 5 http://127.0.0.1:8080/api/health >/dev/null; then backend_healthy=true; break; fi
  sleep 5
done
if [[ "$backend_healthy" == false ]]; then
  run_docker compose ps
  echo 'Backend did not become healthy within five minutes. Recent backend logs:' >&2
  run_docker compose logs --tail 100 backend
  fail 'Deployment failed health verification.'
fi
curl -fsS --max-time 15 http://127.0.0.1/ >/dev/null || fail 'Frontend health verification failed.'
run_docker compose ps
host_ip="$(hostname -I | awk '{print $1}')"
printf '\nDeployment completed.\nWeb UI: http://%s/\nAPI:    http://%s:8080/api/health\n' "$host_ip" "$host_ip"
warn 'Optional parser-service' 'MinerU/Marker/PaddleOCR is not included in docker-compose.yml and was not installed.'
