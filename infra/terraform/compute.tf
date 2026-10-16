# 📋 ECS 최적화 AMI ID 조회 (AWS가 관리하는 최신값)
data "aws_ssm_parameter" "ecs_ami" {
  name = "/aws/service/ecs/optimized-ami/amazon-linux-2023/recommended/image_id"
}

# 🏭 ECS 클러스터 — 컨테이너들이 모이는 그룹
resource "aws_ecs_cluster" "main" {
  name = "${local.name}-cluster"

  tags = { Name = "${local.name}-cluster" }
}

# 🖥️ EC2 — 컨테이너를 실제로 돌리는 서버
resource "aws_instance" "ecs" {
  ami                    = data.aws_ssm_parameter.ecs_ami.value
  instance_type          = "t3.large"
  subnet_id              = aws_subnet.public_a.id
  vpc_security_group_ids = [aws_security_group.ec2.id]
  iam_instance_profile   = aws_iam_instance_profile.ec2.name

  # 디스크 30GB 하나 (OS·이미지·Neo4j·인증서 공용)
  root_block_device {
    volume_type = "gp3"
    volume_size = 30
    encrypted   = true
  }

  # 메타데이터 보안 강화 (IMDSv2)
  metadata_options {
    http_tokens = "required"
  }

  # 부팅 시 "나는 이 클러스터 소속이야" 등록
  user_data = <<-EOF
    #!/bin/bash
    echo "ECS_CLUSTER=${aws_ecs_cluster.main.name}" >> /etc/ecs/ecs.config
  EOF

  lifecycle {
    ignore_changes = [ami]
  }

  tags = { Name = "${local.name}-ecs" }
}

# 🌐 Elastic IP — 고정 공인 IP
resource "aws_eip" "ecs" {
  domain   = "vpc"
  instance = aws_instance.ecs.id

  tags = { Name = "${local.name}-eip" }
}