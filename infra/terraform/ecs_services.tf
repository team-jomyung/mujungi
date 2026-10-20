# ──────────── web (Next.js) ────────────
resource "aws_ecs_task_definition" "web" {
  family                   = "mujoong-web"
  network_mode             = "bridge"
  requires_compatibilities = ["EC2"]
  execution_role_arn       = aws_iam_role.ecs_execution.arn

  container_definitions = jsonencode([{
    name      = "web"
    image     = "${local.ecr["web"]}:${local.web_image_tag}"
    essential = true
    memory    = 512

    portMappings = [{
      containerPort = 3000
      hostPort      = 3000 # 고정! nginx가 172.17.0.1:3000으로 찾아옴
      protocol      = "tcp"
    }]

    environment = [
      { name = "PORT", value = "3000" },
      { name = "HOSTNAME", value = "0.0.0.0" },
      { name = "MUJOONG_SECURITY_ALLOWED_ORIGIN", value = local.cloudfront_origin },
      { name = "GOOGLE_OAUTH_CLIENT_ID", value = local.google_oauth_client_id },
      { name = "GOOGLE_OAUTH_REDIRECT_URI", value = local.google_oauth_redirect_uri }
    ]

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.app["web"].name
        awslogs-region        = "ap-northeast-2"
        awslogs-stream-prefix = "web"
      }
    }
  }])
}

resource "aws_ecs_service" "web" {
  name            = "mujoong-web"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.web.arn
  desired_count   = 1
  launch_type     = "EC2"

  # 단일 EC2 + 고정 포트 → 기존 태스크 내리고 새로 올림
  deployment_minimum_healthy_percent = 0
  deployment_maximum_percent         = 100

  # 서비스의 실제 배포 버전(태스크 정의 리비전)은 CD(deploy.yml)가 관리한다.
  # Terraform이 CD의 변경을 되돌리지 않도록 이 속성만 무시한다.
  lifecycle {
    ignore_changes = [task_definition]
  }

  # ECS가 새 배포를 실패로 판정하면(태스크 기동 실패 · 반복 종료 등),
  # 이전 COMPLETED 배포가 있는 경우 이 서비스만 롤백한다.
  # 컨테이너 healthCheck가 없으므로 "실행 중이지만 앱이 비정상(예: 503)"은 감지하지 못한다.
  # 공개 주소 검사 실패 · DB · 서비스 묶음 롤백은 별도(deploy.yml ROLLBACK 수동 실행).
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }
}

# ──────────── nginx ────────────
resource "aws_ecs_task_definition" "nginx" {
  family                   = "mujoong-nginx"
  network_mode             = "bridge"
  requires_compatibilities = ["EC2"]
  execution_role_arn       = aws_iam_role.ecs_execution.arn

  # EC2의 인증서 폴더 통째로 연결
  volume {
    name      = "letsencrypt"
    host_path = "/etc/letsencrypt"
  }

  container_definitions = jsonencode([{
    name      = "nginx"
    image     = "${local.ecr["nginx"]}:${local.nginx_image_tag}"
    essential = true
    memory    = 128

    portMappings = [{
      containerPort = 443
      hostPort      = 443
      protocol      = "tcp"
    }]

    mountPoints = [{
      sourceVolume  = "letsencrypt"
      containerPath = "/etc/letsencrypt"
      readOnly      = true
    }]

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.app["nginx"].name
        awslogs-region        = "ap-northeast-2"
        awslogs-stream-prefix = "nginx"
      }
    }
  }])
}

resource "aws_ecs_service" "nginx" {
  name            = "mujoong-nginx"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.nginx.arn
  desired_count   = 1
  launch_type     = "EC2"

  deployment_minimum_healthy_percent = 0
  deployment_maximum_percent         = 100

  # 서비스의 실제 배포 버전(태스크 정의 리비전)은 CD(deploy.yml)가 관리한다.
  # Terraform이 CD의 변경을 되돌리지 않도록 이 속성만 무시한다.
  lifecycle {
    ignore_changes = [task_definition]
  }

  # ECS가 새 배포를 실패로 판정하면(태스크 기동 실패 · 반복 종료 등),
  # 이전 COMPLETED 배포가 있는 경우 이 서비스만 롤백한다.
  # 컨테이너 healthCheck가 없으므로 "실행 중이지만 앱이 비정상(예: 503)"은 감지하지 못한다.
  # 공개 주소 검사 실패 · DB · 서비스 묶음 롤백은 별도(deploy.yml ROLLBACK 수동 실행).
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  depends_on = [aws_ecs_service.web]
}

# ──────────── migrate (일회성 태스크 — 서비스 없음) ────────────
resource "aws_ecs_task_definition" "migrate" {
  family                   = "mujoong-migrate"
  network_mode             = "bridge"
  requires_compatibilities = ["EC2"]
  execution_role_arn       = aws_iam_role.ecs_execution.arn

  container_definitions = jsonencode([{
    name      = "migrate"
    image     = "${local.ecr["migrate"]}:${local.backend_image_tag}"
    essential = true
    memory    = 512

    environment = [{
      name  = "MUJOONG_MIGRATE_JDBC_URL"
      value = "jdbc:postgresql://${aws_db_instance.main.address}:5432/${aws_db_instance.main.db_name}?sslmode=require"
    }]

    secrets = [{
      name      = "DB_OWNER_PASSWORD"
      valueFrom = "arn:aws:ssm:ap-northeast-2:${data.aws_caller_identity.current.account_id}:parameter/mujoong/db/DB_OWNER_PASSWORD"
    }]

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.app["migrate"].name
        awslogs-region        = "ap-northeast-2"
        awslogs-stream-prefix = "migrate"
      }
    }
  }])
}

# ──────────── core-api (Spring Boot) ────────────
resource "aws_ecs_task_definition" "core_api" {
  family                   = "mujoong-core-api"
  network_mode             = "bridge"
  requires_compatibilities = ["EC2"]
  execution_role_arn       = aws_iam_role.ecs_execution.arn

  container_definitions = jsonencode([{
    name      = "core-api"
    image     = "${local.ecr["core-api"]}:${local.backend_image_tag}"
    essential = true
    memory    = 1640

    portMappings = [{
      containerPort = 8080
      hostPort      = 8080
      protocol      = "tcp"
    }]

    # 공개 설정값 — 코드 바뀌면 여기만 고치면 됨
    environment = [
      { name = "SPRING_PROFILES_ACTIVE", value = "demo" },
      { name = "SPRING_DATASOURCE_URL", value = local.jdbc_url },
      { name = "SPRING_DATASOURCE_USERNAME", value = "core_app" },
      { name = "MUJOONG_KNOWLEDGE_NEO4J_ENABLED", value = "true" },
      { name = "MUJOONG_KNOWLEDGE_NEO4J_ENDPOINT", value = "http://172.17.0.1:7474" },
      { name = "MUJOONG_SECURITY_ALLOWED_ORIGIN", value = local.cloudfront_origin },
      { name = "MUJOONG_INTERNALHTTP_REALTIMEBASEURL", value = "http://172.17.0.1:8081" },
      { name = "SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES", value = "^172\\.17\\.0\\.1$" },
      { name = "GOOGLE_OAUTH_CLIENT_ID", value = local.google_oauth_client_id },
      { name = "GOOGLE_OAUTH_REDIRECT_URI", value = local.google_oauth_redirect_uri },
      { name = "JAVA_TOOL_OPTIONS", value = local.java_tool_options }
    ]

    # 비밀값 — SSM에서 실행 순간 주입
    secrets = [
      { name = "SPRING_DATASOURCE_PASSWORD", valueFrom = "${local.ssm_prefix}/db/DB_CORE_PASSWORD" },
      { name = "NEO4J_AUTH", valueFrom = "${local.ssm_prefix}/app/NEO4J_AUTH" },
      { name = "INTERNAL_SERVICE_TOKEN", valueFrom = "${local.ssm_prefix}/app/INTERNAL_SERVICE_TOKEN" },
      { name = "MUJOONG_OBSERVABILITY_OWNER_HASH_SECRET", valueFrom = "${local.ssm_prefix}/app/MUJOONG_OBSERVABILITY_OWNER_HASH_SECRET" },
      { name = "GOOGLE_OAUTH_CLIENT_SECRET", valueFrom = "${local.ssm_prefix}/app/GOOGLE_OAUTH_CLIENT_SECRET" },
      { name = "OPENAI_API_KEY", valueFrom = "${local.ssm_prefix}/app/OPENAI_API_KEY" }
    ]

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.app["core-api"].name
        awslogs-region        = "ap-northeast-2"
        awslogs-stream-prefix = "core-api"
      }
    }
  }])
}

resource "aws_ecs_service" "core_api" {
  depends_on      = [aws_ecs_service.neo4j]
  name            = "mujoong-core-api"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.core_api.arn
  desired_count   = 1
  launch_type     = "EC2"

  deployment_minimum_healthy_percent = 0
  deployment_maximum_percent         = 100

  # 서비스의 실제 배포 버전(태스크 정의 리비전)은 CD(deploy.yml)가 관리한다.
  # Terraform이 CD의 변경을 되돌리지 않도록 이 속성만 무시한다.
  lifecycle {
    ignore_changes = [task_definition]
  }

  # ECS가 새 배포를 실패로 판정하면(태스크 기동 실패 · 반복 종료 등),
  # 이전 COMPLETED 배포가 있는 경우 이 서비스만 롤백한다.
  # 컨테이너 healthCheck가 없으므로 "실행 중이지만 앱이 비정상(예: 503)"은 감지하지 못한다.
  # 공개 주소 검사 실패 · DB · 서비스 묶음 롤백은 별도(deploy.yml ROLLBACK 수동 실행).
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }
}

# ──────────── realtime (Spring Boot) ────────────
resource "aws_ecs_task_definition" "realtime" {
  family                   = "mujoong-realtime"
  network_mode             = "bridge"
  requires_compatibilities = ["EC2"]
  execution_role_arn       = aws_iam_role.ecs_execution.arn

  container_definitions = jsonencode([{
    name      = "realtime"
    image     = "${local.ecr["realtime"]}:${local.backend_image_tag}"
    essential = true
    memory    = 1640

    portMappings = [{
      containerPort = 8081
      hostPort      = 8081
      protocol      = "tcp"
    }]

    environment = [
      { name = "SPRING_PROFILES_ACTIVE", value = "demo" },
      { name = "SPRING_DATASOURCE_URL", value = local.jdbc_url_demo },
      { name = "SPRING_DATASOURCE_USERNAME", value = "realtime_app" },
      { name = "MUJOONG_INTERNALHTTP_COREBASEURL", value = "http://172.17.0.1:8080" },
      { name = "MUJOONG_SECURITY_ALLOWED_ORIGIN", value = local.cloudfront_origin },
      { name = "SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES", value = "^172\\.17\\.0\\.1$" },
      { name = "JEV_BASE_URL", value = local.jev_base_url },
      { name = "JEV_MODEL", value = local.jev_model },
      { name = "JAVA_TOOL_OPTIONS", value = local.java_tool_options }
    ]

    # PROVIDER_API_KEY 제거: 앱은 OPENAI_API_KEY를 읽는다 (SSM 파라미터 삭제는 배포 성공 후)
    secrets = [
      { name = "SPRING_DATASOURCE_PASSWORD", valueFrom = "${local.ssm_prefix}/db/DB_REALTIME_PASSWORD" },
      { name = "INTERNAL_SERVICE_TOKEN", valueFrom = "${local.ssm_prefix}/app/INTERNAL_SERVICE_TOKEN" },
      { name = "MUJOONG_OBSERVABILITY_OWNER_HASH_SECRET", valueFrom = "${local.ssm_prefix}/app/MUJOONG_OBSERVABILITY_OWNER_HASH_SECRET" },
      { name = "OPENAI_API_KEY", valueFrom = "${local.ssm_prefix}/app/OPENAI_API_KEY" },
      { name = "JEV_API_KEY", valueFrom = "${local.ssm_prefix}/app/JEV_API_KEY" }
    ]

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.app["realtime"].name
        awslogs-region        = "ap-northeast-2"
        awslogs-stream-prefix = "realtime"
      }
    }
  }])
}

resource "aws_ecs_service" "realtime" {
  name            = "mujoong-realtime"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.realtime.arn
  desired_count   = 1
  launch_type     = "EC2"

  deployment_minimum_healthy_percent = 0
  deployment_maximum_percent         = 100

  # 서비스의 실제 배포 버전(태스크 정의 리비전)은 CD(deploy.yml)가 관리한다.
  # Terraform이 CD의 변경을 되돌리지 않도록 이 속성만 무시한다.
  lifecycle {
    ignore_changes = [task_definition]
  }

  # ECS가 새 배포를 실패로 판정하면(태스크 기동 실패 · 반복 종료 등),
  # 이전 COMPLETED 배포가 있는 경우 이 서비스만 롤백한다.
  # 컨테이너 healthCheck가 없으므로 "실행 중이지만 앱이 비정상(예: 503)"은 감지하지 못한다.
  # 공개 주소 검사 실패 · DB · 서비스 묶음 롤백은 별도(deploy.yml ROLLBACK 수동 실행).
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }
}
