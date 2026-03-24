#!/bin/bash
set -euo pipefail

# AWS Infrastructure Setup Script
# Prerequisites: AWS CLI configured with appropriate permissions
# Usage: bash aws-setup.sh <YOUR_IP_ADDRESS>
#   e.g., bash aws-setup.sh 203.0.113.50

if [ $# -lt 1 ]; then
  echo "Usage: bash aws-setup.sh <YOUR_PUBLIC_IP>"
  echo "  e.g., bash aws-setup.sh 203.0.113.50"
  exit 1
fi

MY_IP="$1"
REGION="us-east-1"
PROJECT="observability-platform"
INSTANCE_TYPE="t3.medium"

echo "=== Creating AWS Infrastructure for $PROJECT ==="
echo "Region: $REGION"
echo "Your IP: $MY_IP"

# --- 1. ECR Repositories ---
echo ""
echo "--- Creating ECR Repositories ---"
for svc in order-service risk-service notification-service; do
  aws ecr create-repository \
    --repository-name "$PROJECT/$svc" \
    --region "$REGION" \
    --image-scanning-configuration scanOnPush=true \
    --encryption-configuration encryptionType=AES256 \
    2>/dev/null || echo "  Repository $svc already exists"
  echo "  Created: $PROJECT/$svc"
done

ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
ECR_URI="$ACCOUNT_ID.dkr.ecr.$REGION.amazonaws.com"
echo "ECR URI: $ECR_URI"

# --- 2. Get Default VPC and Subnet ---
echo ""
echo "--- Getting VPC Info ---"
VPC_ID=$(aws ec2 describe-vpcs --filters "Name=isDefault,Values=true" \
  --query "Vpcs[0].VpcId" --output text --region "$REGION")
echo "VPC: $VPC_ID"

SUBNET_IDS=$(aws ec2 describe-subnets --filters "Name=vpc-id,Values=$VPC_ID" \
  --query "Subnets[*].SubnetId" --output text --region "$REGION")
SUBNET_1=$(echo "$SUBNET_IDS" | awk '{print $1}')
SUBNET_2=$(echo "$SUBNET_IDS" | awk '{print $2}')
echo "Subnets: $SUBNET_1, $SUBNET_2"

# --- 3. Security Groups ---
echo ""
echo "--- Creating Security Groups ---"

# ALB Security Group
ALB_SG=$(aws ec2 create-security-group \
  --group-name "$PROJECT-alb-sg" \
  --description "ALB - public HTTP access" \
  --vpc-id "$VPC_ID" --region "$REGION" \
  --query "GroupId" --output text 2>/dev/null || \
  aws ec2 describe-security-groups \
    --filters "Name=group-name,Values=$PROJECT-alb-sg" \
    --query "SecurityGroups[0].GroupId" --output text --region "$REGION")

aws ec2 authorize-security-group-ingress --group-id "$ALB_SG" --region "$REGION" \
  --protocol tcp --port 80 --cidr 0.0.0.0/0 2>/dev/null || true
echo "  ALB SG: $ALB_SG (port 80 open to 0.0.0.0/0)"

# EC2 Security Group
EC2_SG=$(aws ec2 create-security-group \
  --group-name "$PROJECT-ec2-sg" \
  --description "EC2 - SSH + ALB traffic only" \
  --vpc-id "$VPC_ID" --region "$REGION" \
  --query "GroupId" --output text 2>/dev/null || \
  aws ec2 describe-security-groups \
    --filters "Name=group-name,Values=$PROJECT-ec2-sg" \
    --query "SecurityGroups[0].GroupId" --output text --region "$REGION")

aws ec2 authorize-security-group-ingress --group-id "$EC2_SG" --region "$REGION" \
  --protocol tcp --port 22 --cidr "$MY_IP/32" 2>/dev/null || true
aws ec2 authorize-security-group-ingress --group-id "$EC2_SG" --region "$REGION" \
  --protocol tcp --port 3000 --source-group "$ALB_SG" 2>/dev/null || true
echo "  EC2 SG: $EC2_SG (SSH from $MY_IP, port 3000 from ALB only)"

# --- 4. IAM Role ---
echo ""
echo "--- Creating IAM Role ---"

TRUST_POLICY='{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": {"Service": "ec2.amazonaws.com"},
    "Action": "sts:AssumeRole"
  }]
}'

aws iam create-role \
  --role-name "$PROJECT-ec2-role" \
  --assume-role-policy-document "$TRUST_POLICY" \
  2>/dev/null || echo "  Role already exists"

POLICY="{
  \"Version\": \"2012-10-17\",
  \"Statement\": [
    {
      \"Effect\": \"Allow\",
      \"Action\": \"ecr:GetAuthorizationToken\",
      \"Resource\": \"*\"
    },
    {
      \"Effect\": \"Allow\",
      \"Action\": [
        \"ecr:BatchGetImage\",
        \"ecr:GetDownloadUrlForLayer\",
        \"ecr:BatchCheckLayerAvailability\"
      ],
      \"Resource\": \"arn:aws:ecr:$REGION:$ACCOUNT_ID:repository/$PROJECT/*\"
    },
    {
      \"Effect\": \"Allow\",
      \"Action\": \"cloudwatch:PutMetricData\",
      \"Resource\": \"*\"
    }
  ]
}"

aws iam put-role-policy \
  --role-name "$PROJECT-ec2-role" \
  --policy-name "$PROJECT-ecr-cw-policy" \
  --policy-document "$POLICY"

aws iam create-instance-profile \
  --instance-profile-name "$PROJECT-ec2-profile" \
  2>/dev/null || echo "  Instance profile already exists"

aws iam add-role-to-instance-profile \
  --instance-profile-name "$PROJECT-ec2-profile" \
  --role-name "$PROJECT-ec2-role" \
  2>/dev/null || true

echo "  IAM Role: $PROJECT-ec2-role"

# Wait for instance profile propagation
sleep 10

# --- 5. Key Pair ---
echo ""
echo "--- Creating Key Pair ---"
KEY_FILE="$PROJECT-key.pem"
if [ ! -f "$KEY_FILE" ]; then
  aws ec2 create-key-pair \
    --key-name "$PROJECT-key" \
    --query "KeyMaterial" --output text --region "$REGION" > "$KEY_FILE"
  chmod 400 "$KEY_FILE"
  echo "  Key saved to $KEY_FILE"
else
  echo "  Key file already exists: $KEY_FILE"
fi

# --- 6. Launch EC2 Instance ---
echo ""
echo "--- Launching EC2 Instance ---"

# Amazon Linux 2023 AMI (latest)
AMI_ID=$(aws ec2 describe-images \
  --owners amazon \
  --filters "Name=name,Values=al2023-ami-2023*-x86_64" "Name=state,Values=available" \
  --query "sort_by(Images, &CreationDate)[-1].ImageId" \
  --output text --region "$REGION")
echo "  AMI: $AMI_ID"

INSTANCE_ID=$(aws ec2 run-instances \
  --image-id "$AMI_ID" \
  --instance-type "$INSTANCE_TYPE" \
  --key-name "$PROJECT-key" \
  --security-group-ids "$EC2_SG" \
  --subnet-id "$SUBNET_1" \
  --iam-instance-profile Name="$PROJECT-ec2-profile" \
  --block-device-mappings '[{"DeviceName":"/dev/xvda","Ebs":{"VolumeSize":30,"VolumeType":"gp3"}}]' \
  --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=$PROJECT}]" \
  --region "$REGION" \
  --query "Instances[0].InstanceId" --output text)

echo "  Instance: $INSTANCE_ID"
echo "  Waiting for instance to be running..."
aws ec2 wait instance-running --instance-ids "$INSTANCE_ID" --region "$REGION"

PUBLIC_IP=$(aws ec2 describe-instances --instance-ids "$INSTANCE_ID" --region "$REGION" \
  --query "Reservations[0].Instances[0].PublicIpAddress" --output text)
echo "  Public IP: $PUBLIC_IP"

# --- 7. Application Load Balancer ---
echo ""
echo "--- Creating ALB ---"

ALB_ARN=$(aws elbv2 create-load-balancer \
  --name "$PROJECT-alb" \
  --subnets $SUBNET_1 $SUBNET_2 \
  --security-groups "$ALB_SG" \
  --scheme internet-facing \
  --type application \
  --region "$REGION" \
  --query "LoadBalancers[0].LoadBalancerArn" --output text 2>/dev/null || \
  aws elbv2 describe-load-balancers --names "$PROJECT-alb" --region "$REGION" \
    --query "LoadBalancers[0].LoadBalancerArn" --output text)

ALB_DNS=$(aws elbv2 describe-load-balancers \
  --load-balancer-arns "$ALB_ARN" --region "$REGION" \
  --query "LoadBalancers[0].DNSName" --output text)
echo "  ALB ARN: $ALB_ARN"
echo "  ALB DNS: $ALB_DNS"

# Target Group
TG_ARN=$(aws elbv2 create-target-group \
  --name "$PROJECT-grafana-tg" \
  --protocol HTTP --port 3000 \
  --vpc-id "$VPC_ID" \
  --target-type instance \
  --health-check-path "/api/health" \
  --region "$REGION" \
  --query "TargetGroups[0].TargetGroupArn" --output text 2>/dev/null || \
  aws elbv2 describe-target-groups --names "$PROJECT-grafana-tg" --region "$REGION" \
    --query "TargetGroups[0].TargetGroupArn" --output text)

aws elbv2 register-targets \
  --target-group-arn "$TG_ARN" \
  --targets Id="$INSTANCE_ID" \
  --region "$REGION"

aws elbv2 create-listener \
  --load-balancer-arn "$ALB_ARN" \
  --protocol HTTP --port 80 \
  --default-actions Type=forward,TargetGroupArn="$TG_ARN" \
  --region "$REGION" 2>/dev/null || true

echo "  Target Group registered, Listener created"

# --- Summary ---
echo ""
echo "=========================================="
echo "  AWS Infrastructure Setup Complete"
echo "=========================================="
echo ""
echo "  ECR URI:      $ECR_URI"
echo "  EC2 Instance: $INSTANCE_ID"
echo "  EC2 Public IP: $PUBLIC_IP"
echo "  ALB DNS:      http://$ALB_DNS"
echo "  Key File:     $KEY_FILE"
echo ""
echo "  SSH:  ssh -i $KEY_FILE ec2-user@$PUBLIC_IP"
echo ""
echo "  Next steps:"
echo "    1. SSH into EC2 and run ec2-setup.sh"
echo "    2. Push images to ECR using deploy.sh"
echo "    3. Start the stack on EC2"
echo ""

# Save outputs for other scripts
cat > aws-outputs.env <<EOF
ACCOUNT_ID=$ACCOUNT_ID
ECR_URI=$ECR_URI
INSTANCE_ID=$INSTANCE_ID
PUBLIC_IP=$PUBLIC_IP
ALB_DNS=$ALB_DNS
ALB_ARN=$ALB_ARN
EC2_SG=$EC2_SG
ALB_SG=$ALB_SG
KEY_FILE=$KEY_FILE
REGION=$REGION
PROJECT=$PROJECT
EOF
echo "  Outputs saved to aws-outputs.env"
