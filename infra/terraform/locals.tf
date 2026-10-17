locals {
  name = "mujungi"
  # ECR 레포 & 로그 그룹 대상 — 추가하려면 여기에 한 단어만
  app_images        = ["nginx", "web", "core-api", "realtime", "migrate", "backup"]
  image_tag         = "0b70df0-10161400"
  origin_domain     = "mujoong.duckdns.org"
  backend_image_tag = "3d8d4cd-10171120"
  ssm_prefix        = "arn:aws:ssm:ap-northeast-2:${data.aws_caller_identity.current.account_id}:parameter/mujoong"
}