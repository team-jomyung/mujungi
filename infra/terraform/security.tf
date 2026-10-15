# 📋 CloudFront가 우리 서버로 접속할 때 쓰는 IP 목록 (AWS가 관리)
data "aws_ec2_managed_prefix_list" "cloudfront" {
  name = "com.amazonaws.global.cloudfront.origin-facing"
}

# ──────────── EC2 보안 그룹 ────────────
resource "aws_security_group" "ec2" {
  name        = "${local.name}-ec2-sg"
  description = "EC2 - HTTPS from CloudFront only"
  vpc_id      = aws_vpc.main.id

  tags = { Name = "${local.name}-ec2-sg" }
}

# 들어오는 것: 443은 CloudFront에서만
resource "aws_vpc_security_group_ingress_rule" "ec2_https_from_cloudfront" {
  security_group_id = aws_security_group.ec2.id
  prefix_list_id    = data.aws_ec2_managed_prefix_list.cloudfront.id
  from_port         = 443
  to_port           = 443
  ip_protocol       = "tcp"
  description       = "HTTPS from CloudFront"
}

# 나가는 것: 전부 허용 (외부 API, ECR, S3, SSM 등)
resource "aws_vpc_security_group_egress_rule" "ec2_all_out" {
  security_group_id = aws_security_group.ec2.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
  description       = "All outbound"
}

# ──────────── RDS 보안 그룹 ────────────
resource "aws_security_group" "rds" {
  name        = "${local.name}-rds-sg"
  description = "RDS - PostgreSQL from EC2 only"
  vpc_id      = aws_vpc.main.id

  tags = { Name = "${local.name}-rds-sg" }
}

# 들어오는 것: 5432는 EC2 SG에서만
resource "aws_vpc_security_group_ingress_rule" "rds_from_ec2" {
  security_group_id            = aws_security_group.rds.id
  referenced_security_group_id = aws_security_group.ec2.id
  from_port                    = 5432
  to_port                      = 5432
  ip_protocol                  = "tcp"
  description                  = "PostgreSQL from EC2"
}