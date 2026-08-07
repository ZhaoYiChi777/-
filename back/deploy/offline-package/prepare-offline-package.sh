#!/usr/bin/env bash
# Build and export an offline deployment package on a machine with internet access.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
STAMP="$(date +%Y%m%d-%H%M%S)"
OUTPUT_DIR="${1:-${SCRIPT_DIR}/dist/intelligence-platform-offline-${STAMP}}"

IMAGES=(
  "mysql:8.0"
  "intelligence-platform/backend:offline"
  "intelligence-platform/frontend:offline"
  "intelligence-platform/kg-compute:offline"
)

stage() { printf '\n==> %s\n' "$1"; }
fail() { printf '[ERROR] %s\n' "$1" >&2; exit 1; }
command_exists() { command -v "$1" >/dev/null 2>&1; }

command_exists docker || fail 'docker is required.'
docker compose version >/dev/null 2>&1 || fail 'Docker Compose plugin is required.'
[[ -f "${PROJECT_DIR}/docker-compose.yml" ]] || fail "docker-compose.yml was not found in ${PROJECT_DIR}."

stage 'Preparing output directory'
mkdir -p "${OUTPUT_DIR}/images" "${OUTPUT_DIR}/project"

stage 'Building application images'
cd "$PROJECT_DIR"
docker compose build

stage 'Pulling base/runtime images'
docker pull mysql:8.0

stage 'Verifying required images'
for image in "${IMAGES[@]}"; do
  docker image inspect "$image" >/dev/null 2>&1 || fail "Image is missing after build/pull: ${image}"
done

stage 'Saving images'
printf '%s\n' "${IMAGES[@]}" > "${OUTPUT_DIR}/IMAGE_LIST.txt"
if command_exists gzip; then
  docker save "${IMAGES[@]}" | gzip -c > "${OUTPUT_DIR}/images/intelligence-platform-images.tar.gz"
else
  docker save -o "${OUTPUT_DIR}/images/intelligence-platform-images.tar" "${IMAGES[@]}"
fi

stage 'Copying project files'
if command_exists rsync; then
  rsync -a \
    --exclude '.git/' \
    --exclude 'deploy/offline-package/dist/' \
    --exclude '**/node_modules/' \
    --exclude '**/target/' \
    --exclude '**/__pycache__/' \
    --exclude 'logs/' \
    --exclude 'uploads/' \
    --exclude 'data/' \
    --exclude '.env' \
    "${PROJECT_DIR}/" "${OUTPUT_DIR}/project/"
else
  tar \
    --exclude='.git' \
    --exclude='deploy/offline-package/dist' \
    --exclude='*/node_modules' \
    --exclude='*/target' \
    --exclude='*/__pycache__' \
    --exclude='logs' \
    --exclude='uploads' \
    --exclude='data' \
    --exclude='.env' \
    -C "$PROJECT_DIR" -cf - . | tar -C "${OUTPUT_DIR}/project" -xf -
fi

cp "${SCRIPT_DIR}/load-offline-package.sh" "${OUTPUT_DIR}/load-offline-package.sh"
chmod +x "${OUTPUT_DIR}/load-offline-package.sh"

cat > "${OUTPUT_DIR}/README-OFFLINE.txt" <<EOF
Intelligence Platform offline package

Generated at: ${STAMP}

Files:
  images/intelligence-platform-images.tar.gz or .tar
  project/
  load-offline-package.sh
  IMAGE_LIST.txt

On the offline Ubuntu server:
  cd <this package directory>
  bash load-offline-package.sh --project-dir /root/back

Docker Engine and the Docker Compose plugin must already be installed on the offline server.
EOF

stage 'Package completed'
printf 'Offline package directory:\n%s\n' "$OUTPUT_DIR"
