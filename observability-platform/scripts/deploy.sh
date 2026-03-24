#!/bin/bash
set -euo pipefail

# Deploy script: build images, push to ECR, deploy on EC2
# Usage: bash deploy.sh [version]
# Prerequisites: aws-outputs.env must exist (created by aws-setup.sh)

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
VERSION="${1:-$(git -C "$PROJECT_ROOT" rev-parse --short HEAD 2>/dev/null || echo 'latest')}"

# Load AWS outputs
if [ -f "$SCRIPT_DIR/../infra/aws/aws-outputs.env" ]; then
  source "$SCRIPT_DIR/../infra/aws/aws-outputs.env"
elif [ -f "$SCRIPT_DIR/aws-outputs.env" ]; then
  source "$SCRIPT_DIR/aws-outputs.env"
else
  echo "ERROR: aws-outputs.env not found. Run aws-setup.sh first."
  exit 1
fi

echo "=== Deploying Observability Platform ==="
echo "ECR: $ECR_URI"
echo "EC2: $PUBLIC_IP"
echo "Version: $VERSION"

# --- 1. Build Docker images with Jib ---
echo ""
echo "--- Building images with Jib (pushing to ECR) ---"
aws ecr get-login-password --region "$REGION" | docker login --username AWS --password-stdin "$ECR_URI"

cd "$PROJECT_ROOT"
for svc in order-service risk-service notification-service; do
  echo "  Building $svc..."
  mvn compile com.google.cloud.tools:jib-maven-plugin:3.4.4:build \
    -pl "$svc" \
    -Djib.to.image="$ECR_URI/observability-platform/$svc:$VERSION" \
    -Djib.to.auth.username=AWS \
    -Djib.to.auth.helper=ecr-login \
    -q
  echo "  Pushed $ECR_URI/observability-platform/$svc:$VERSION"
done

# --- 2. Sync config files to EC2 ---
echo ""
echo "--- Syncing config files to EC2 ---"
SSH_OPTS="-i $SCRIPT_DIR/../infra/aws/$KEY_FILE -o StrictHostKeyChecking=accept-new"
REMOTE_DIR="/home/ec2-user/observability-platform/docker"

ssh $SSH_OPTS ec2-user@"$PUBLIC_IP" "mkdir -p $REMOTE_DIR/grafana/provisioning/datasources $REMOTE_DIR/grafana/provisioning/dashboards $REMOTE_DIR/grafana/dashboards $REMOTE_DIR/otel-agent"

# Sync docker directory (includes docker-compose.prod.yml)
scp $SSH_OPTS -r "$PROJECT_ROOT/docker/"* ec2-user@"$PUBLIC_IP":"$REMOTE_DIR/"

echo "  Files synced"

# --- 3. Deploy on EC2 ---
echo ""
echo "--- Deploying on EC2 ---"
ssh $SSH_OPTS ec2-user@"$PUBLIC_IP" << DEPLOY
  cd $REMOTE_DIR
  export ECR_URI=$ECR_URI
  export VERSION=$VERSION
  aws ecr get-login-password --region $REGION | docker login --username AWS --password-stdin $ECR_URI
  docker compose -f docker-compose.prod.yml pull
  docker compose -f docker-compose.prod.yml up -d
  echo "Waiting for services to start..."
  sleep 30
  docker compose -f docker-compose.prod.yml ps
DEPLOY

echo ""
echo "=========================================="
echo "  Deployment Complete!"
echo "=========================================="
echo ""
echo "  Version: $VERSION"
echo "  Grafana: http://$ALB_DNS"
echo "  (Direct): http://$PUBLIC_IP:3000"
echo ""
echo "  To seed data:"
echo "    ssh $SSH_OPTS ec2-user@$PUBLIC_IP"
echo "    curl -X POST http://localhost:8081/api/orders -H 'Content-Type: application/json' -d '{\"userId\":\"demo\",\"amount\":500,\"currency\":\"USD\"}'"
echo ""
echo "  To run load test:"
echo "    k6 run -e BASE_URL=http://$PUBLIC_IP:8081 scripts/load-test.js"
