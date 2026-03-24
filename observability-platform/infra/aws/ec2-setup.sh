#!/bin/bash
set -euo pipefail

# EC2 Bootstrap Script for Amazon Linux 2023
# Usage: bash ec2-setup.sh
# This script installs Docker, Docker Compose, and prepares the instance
# to run the observability platform.

COMPOSE_VERSION="v2.29.2"

echo "=== Observability Platform - EC2 Setup ==="

# Install Docker
echo "--- Installing Docker ---"
sudo dnf update -y
sudo dnf install -y docker git
sudo systemctl enable docker
sudo systemctl start docker
sudo usermod -aG docker ec2-user

# Install Docker Compose plugin (pinned version)
echo "--- Installing Docker Compose $COMPOSE_VERSION ---"
sudo mkdir -p /usr/local/lib/docker/cli-plugins
sudo curl -SL "https://github.com/docker/compose/releases/download/${COMPOSE_VERSION}/docker-compose-linux-$(uname -m)" \
  -o /usr/local/lib/docker/cli-plugins/docker-compose
sudo chmod +x /usr/local/lib/docker/cli-plugins/docker-compose

# Verify installations
docker --version
docker compose version

# Configure Docker daemon for log rotation
sudo tee /etc/docker/daemon.json > /dev/null <<'DAEMON'
{
  "log-driver": "json-file",
  "log-opts": {
    "max-size": "10m",
    "max-file": "3"
  }
}
DAEMON
sudo systemctl restart docker

# Configure swap (4GB RAM may OOM with 12 containers)
echo "--- Configuring swap ---"
if [ ! -f /swapfile ]; then
  sudo dd if=/dev/zero of=/swapfile bs=128M count=16
  sudo chmod 600 /swapfile
  sudo mkswap /swapfile
  sudo swapon /swapfile
  echo '/swapfile swap swap defaults 0 0' | sudo tee -a /etc/fstab
  echo "  2GB swap configured"
else
  echo "  Swap already configured"
fi

# Create app directory
APP_DIR=/home/ec2-user/observability-platform
mkdir -p "$APP_DIR"

echo ""
echo "=== Setup Complete ==="
echo "Next steps:"
echo "  1. Clone your repo or copy docker/ directory to $APP_DIR"
echo "  2. Login to ECR: aws ecr get-login-password --region us-east-1 | docker login --username AWS --password-stdin <ACCOUNT_ID>.dkr.ecr.us-east-1.amazonaws.com"
echo "  3. cd $APP_DIR/docker && docker compose -f docker-compose.prod.yml up -d"
