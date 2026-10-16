locals {
  name = "mujungi"
  # ECR 레포 & 로그 그룹 대상 — 추가하려면 여기에 한 단어만
  app_images    = ["nginx", "web", "core-api", "realtime", "migrate", "backup"]
  image_tag     = "0b70df0-10161400"
  ecr           = { for k, r in aws_ecr_repository.app : k => r.repository_url }
  origin_domain = "mujoong.duckdns.org"
}