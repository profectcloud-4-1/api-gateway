#!/usr/bin/env bash
set -euo pipefail

# Script/Root path
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

# Args
usage() {
  cat <<EOF
Usage: $(basename "$0") [-n|--namespace <namespace>] [-i|--image <image>]

Options:
  -n, --namespace   kubectl에 전달할 네임스페이스 (.env의 K8S_NAMESPACE보다 우선, 기본값: default)
  -i, --image       Deployment 템플릿에 주입할 컨테이너 이미지 (.env의 IMAGE보다 우선)
  -h, --help        이 도움말 표시
Notes:
  - 이미지 값은 필수입니다. CLI(--image) 또는 .env의 IMAGE 중 하나는 지정되어야 합니다.
EOF
}

NAMESPACE=""
IMAGE_CLI=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    -n|--namespace)
      if [[ -n "${2:-}" && ! ${2:-} =~ ^- ]]; then
        NAMESPACE="$2"
        shift 2
      else
        # 인자가 없거나 다음 토큰이 옵션이면 default 사용
        NAMESPACE="default"
        shift 1
      fi
      ;;
    -i|--image)
      if [[ -n "${2:-}" && ! ${2:-} =~ ^- ]]; then
        IMAGE_CLI="$2"
        shift 2
      else
        # 값이 없으면 무시하고 계속 진행 (최종 검증에서 처리)
        shift 1
      fi
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "ERROR: 알 수 없는 옵션: $1" >&2
      usage
      exit 1
      ;;
  esac
done

# Requires
command -v envsubst >/dev/null 2>&1 || { echo "ERROR: envsubst(not found). Install gettext."; exit 1; }
command -v kubectl >/dev/null 2>&1 || { echo "ERROR: kubectl(not found)."; exit 1; }

# Load .env from project root
if [ ! -f "$ROOT_DIR/.env" ]; then
  echo "ERROR: $ROOT_DIR/.env 파일이 없습니다." >&2
  exit 1
fi
set -a
. "$ROOT_DIR/.env"
set +a

# Namespace 우선순위: CLI > .env(K8S_NAMESPACE) > default
NAMESPACE_EFFECTIVE="${NAMESPACE:-${K8S_NAMESPACE:-default}}"
KNS_ARGS=(-n "$NAMESPACE_EFFECTIVE")

# Image 우선순위: CLI > .env(IMAGE) > (없음 → 종료)
IMAGE_EFFECTIVE="${IMAGE_CLI:-${IMAGE:-}}"
if [ -z "$IMAGE_EFFECTIVE" ]; then
  echo "ERROR: 이미지가 지정되지 않았습니다. --image 옵션 또는 .env의 IMAGE를 설정하세요." >&2
  exit 1
fi
export IMAGE="$IMAGE_EFFECTIVE"

# Apply ConfigMap (template -> envsubst -> kubectl)
echo "[apply] ConfigMap: $SCRIPT_DIR/configmap.tpl.yaml"
envsubst < "$SCRIPT_DIR/configmap.tpl.yaml" | kubectl apply "${KNS_ARGS[@]}" -f -

# Apply Secret (template -> envsubst -> kubectl)
echo "[apply] Secret: $SCRIPT_DIR/secret.tpl.yaml"
envsubst < "$SCRIPT_DIR/secret.tpl.yaml" | kubectl apply "${KNS_ARGS[@]}" -f -

# Apply Deployment (template -> envsubst -> kubectl)
if [ -f "$SCRIPT_DIR/deployment.tpl.yaml" ]; then
  echo "[apply] Deployment: $SCRIPT_DIR/deployment.tpl.yaml (IMAGE=$IMAGE)"
  envsubst < "$SCRIPT_DIR/deployment.tpl.yaml" | kubectl apply "${KNS_ARGS[@]}" -f -
fi

kubectl apply -n "$NAMESPACE_EFFECTIVE" -f "$SCRIPT_DIR/service.yaml"

echo "✅ Done."