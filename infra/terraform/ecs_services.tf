# ──────────── web (Next.js) ────────────
resource "aws_ecs_task_definition" "web" {
  family                   = "mujoong-web"
  network_mode             = "bridge"
  requires_compatibilities = ["EC2"]
  execution_role_arn       = aws_iam_role.ecs_execution.arn

  container_definitions = jsonencode([{
    name      = "web"
    image     = "${local.ecr["web"]}:${local.image_tag}"
    essential = true
    memory    = 1024

    portMappings = [{
      containerPort = 3000
      hostPort      = 3000 # 고정! nginx가 172.17.0.1:3000으로 찾아옴
      protocol      = "tcp"
    }]

    environment = [
      { name = "PORT", value = "3000" },
      { name = "HOSTNAME", value = "0.0.0.0" }
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
    image     = "${local.ecr["nginx"]}:${local.image_tag}"
    essential = true
    memory    = 256

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
    image     = "${local.ecr["migrate"]}:${local.migrate_image_tag}"
    essential = true
    memory    = 1024

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