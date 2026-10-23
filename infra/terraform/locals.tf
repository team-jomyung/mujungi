locals {
  name = "mujungi"
  # ECR 레포 & 로그 그룹 대상 — 추가하려면 여기에 한 단어만
  app_images        = ["nginx", "web", "core-api", "realtime", "migrate", "backup"]
  web_image_tag     = "0b70df0-10161400"
  nginx_image_tag   = "eeadd75-10171547"
  image_tag         = "0b70df0-10161400"
  origin_domain     = "mujoong.duckdns.org"
  backend_image_tag = "3d8d4cd-10171120"
  ssm_prefix        = "arn:aws:ssm:ap-northeast-2:${data.aws_caller_identity.current.account_id}:parameter/mujoong"
  gha_repo_sub      = "repo:team-jomyung@341322885/mujungi@1415195348"

  # ── 파생 주소 (리소스에서 계산) ──
  ecr               = { for k, r in aws_ecr_repository.app : k => r.repository_url }
  jdbc_url          = "jdbc:postgresql://${aws_db_instance.main.address}:5432/${aws_db_instance.main.db_name}?sslmode=require"
  cloudfront_origin = "https://${aws_cloudfront_distribution.main.domain_name}"
  # demo 전용: 접속 시 데이터 등급 표지 (RDS는 DB 단위 커스텀 설정 불가 → 세션 단위로 우회)
  # 실제 아동 데이터 DB에는 절대 사용 금지
  jdbc_url_demo = "${local.jdbc_url}&options=-c%20mujoong_db.data_class=synthetic"

  # ── 앱 런타임 공개 설정 (비밀 아님, 비밀은 SSM) ──
  # 이름 규칙: 컨테이너 환경변수 이름을 소문자로
  # Google 로그인: web·core가 같은 값을 써야 한다 (core가 콜백 주소 일치 검사)
  google_oauth_client_id    = "223791608425-adqf9g80l78nd0glntvrlrrkj0o0cctr.apps.googleusercontent.com"
  google_oauth_redirect_uri = "${local.cloudfront_origin}/parent/login/callback"

  jev_base_url = "https://api.typesafe.ai"
  jev_model    = "jev-1.13.0"

  # Compose(compose.demo.yml)와 같은 JVM 옵션 — 값은 그대로 복사
  java_tool_options = "-Xmx1g -Xms512m -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError -XX:-HeapDumpOnOutOfMemoryError -XX:MaxMetaspaceSize=160m -XX:ReservedCodeCacheSize=64m -XX:MaxDirectMemorySize=96m -Duser.timezone=UTC"
}