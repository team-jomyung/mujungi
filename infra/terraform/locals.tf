locals {
  name = "mujungi"
  # ECR 레포 & 로그 그룹 대상 — 추가하려면 여기에 한 단어만
  app_images = ["nginx", "web", "core-api", "realtime", "migrate", "backup"]
}