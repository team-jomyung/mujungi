# 내 AWS 계정 ID 조회 (SSM 파라미터 등의 ARN에 넣는다 — 계정 ID를 코드에 직접 쓰지 않으려고)
data "aws_caller_identity" "current" {}

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