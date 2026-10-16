# 내 AWS 계정 정보 조회 (버킷 이름에 계정 ID 붙이려고 — 코드엔 안 드러남)
data "aws_caller_identity" "current" {}

# ──────────── S3: 음성 + 백업 ────────────
resource "aws_s3_bucket" "media" {
  bucket = "${local.name}-media-${data.aws_caller_identity.current.account_id}"

  tags = { Name = "${local.name}-media" }
}

# 퍼블릭 접근 전부 차단
resource "aws_s3_bucket_public_access_block" "media" {
  bucket                  = aws_s3_bucket.media.id
  block_public_acls       = true
  ignore_public_acls      = true
  block_public_policy     = true
  restrict_public_buckets = true
}

# 서버 측 암호화
resource "aws_s3_bucket_server_side_encryption_configuration" "media" {
  bucket = aws_s3_bucket.media.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# Lifecycle: 백업은 14일 지나면 자동 삭제
resource "aws_s3_bucket_lifecycle_configuration" "media" {
  bucket = aws_s3_bucket.media.id

  rule {
    id     = "expire-old-backups"
    status = "Enabled"

    filter {
      prefix = "backup/"
    }

    expiration {
      days = 14
    }
  }
}

# ──────────── ECR: 이미지 저장소 6개 ────────────
resource "aws_ecr_repository" "app" {
  for_each = toset(local.app_images)

  name                 = "mujoong-${each.key}"
  image_tag_mutability = "IMMUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  tags = { Name = "mujoong-${each.key}" }
}

# 최근 5개 이미지만 유지
resource "aws_ecr_lifecycle_policy" "app" {
  for_each   = aws_ecr_repository.app
  repository = each.value.name

  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Keep last 5 images"
      selection = {
        tagStatus   = "any"
        countType   = "imageCountMoreThan"
        countNumber = 5
      }
      action = { type = "expire" }
    }]
  })
}

# ──────────── CloudWatch 로그 그룹 ────────────
resource "aws_cloudwatch_log_group" "app" {
  for_each = toset(local.app_images)

  name              = "/ecs/mujoong-${each.key}"
  retention_in_days = 7
}