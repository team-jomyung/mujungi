# Local host storage survives task replacement; existing hosts must prepare its ownership.
resource "aws_cloudwatch_log_group" "neo4j" {
  name              = "/ecs/mujoong-neo4j"
  retention_in_days = 7
}

resource "aws_ecs_task_definition" "neo4j" {
  family                   = "mujoong-neo4j"
  network_mode             = "bridge"
  requires_compatibilities = ["EC2"]
  execution_role_arn       = aws_iam_role.ecs_execution.arn

  volume {
    name      = "data"
    host_path = "/var/lib/mujoong/neo4j/data"
  }
  volume {
    name      = "logs"
    host_path = "/var/lib/mujoong/neo4j/logs"
  }
  container_definitions = jsonencode([{
    name         = "neo4j"
    image        = "neo4j:5.26.31-community"
    user         = "7474:7474"
    essential    = true
    memory       = 1536
    portMappings = [{ containerPort = 7474, hostPort = 7474, protocol = "tcp" }]
    environment = [
      { name = "NEO4J_server_memory_heap_initial__size", value = "256m" },
      { name = "NEO4J_server_memory_heap_max__size", value = "512m" },
      { name = "NEO4J_server_memory_pagecache_size", value = "128m" }
    ]
    secrets = [{ name = "NEO4J_AUTH", valueFrom = "${local.ssm_prefix}/app/NEO4J_AUTH" }]
    mountPoints = [
      { sourceVolume = "data", containerPath = "/data", readOnly = false },
      { sourceVolume = "logs", containerPath = "/logs", readOnly = false }
    ]
    healthCheck = {
      command     = ["CMD-SHELL", "wget -q -O /dev/null -T 2 http://127.0.0.1:7474/"]
      interval    = 10
      timeout     = 3
      retries     = 6
      startPeriod = 60
    }
    linuxParameters = { capabilities = { drop = ["ALL"] } }
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.neo4j.name
        awslogs-region        = "ap-northeast-2"
        awslogs-stream-prefix = "neo4j"
      }
    }
  }])
}

resource "aws_ecs_service" "neo4j" {
  name                               = "mujoong-neo4j"
  cluster                            = aws_ecs_cluster.main.id
  task_definition                    = aws_ecs_task_definition.neo4j.arn
  desired_count                      = 1
  launch_type                        = "EC2"
  deployment_minimum_healthy_percent = 0
  deployment_maximum_percent         = 100
}
