# ──────────── 스케줄러 전용 IAM 역할 ────────────
data "aws_iam_policy_document" "scheduler_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["scheduler.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "scheduler" {
  name               = "${local.name}-scheduler-role"
  assume_role_policy = data.aws_iam_policy_document.scheduler_assume.json
}

data "aws_iam_policy_document" "scheduler_stop" {
  statement {
    actions   = ["ec2:StopInstances"]
    resources = [aws_instance.ecs.arn]
  }

  statement {
    actions   = ["rds:StopDBInstance"]
    resources = [aws_db_instance.main.arn]
  }
}

resource "aws_iam_role_policy" "scheduler_stop" {
  name   = "${local.name}-scheduler-stop"
  role   = aws_iam_role.scheduler.id
  policy = data.aws_iam_policy_document.scheduler_stop.json
}

# ──────────── 중지 스케줄 ────────────
locals {
  stop_schedules = {
    weekday-1800 = "cron(0 18 ? * MON-FRI *)" # 평일 18:00
    daily-2200   = "cron(0 22 * * ? *)"       # 매일 22:00
  }
}

resource "aws_scheduler_schedule" "ec2_stop" {
  for_each = local.stop_schedules

  name  = "${local.name}-ec2-stop-${each.key}"
  state = "ENABLED"

  schedule_expression          = each.value
  schedule_expression_timezone = "Asia/Seoul"

  flexible_time_window {
    mode = "OFF" # 정각에 실행
  }

  target {
    arn      = "arn:aws:scheduler:::aws-sdk:ec2:stopInstances"
    role_arn = aws_iam_role.scheduler.arn
    input    = jsonencode({ InstanceIds = [aws_instance.ecs.id] })

    retry_policy {
      maximum_retry_attempts = 0
    }
  }
}

resource "aws_scheduler_schedule" "rds_stop" {
  for_each = local.stop_schedules

  name  = "${local.name}-rds-stop-${each.key}"
  state = "ENABLED"

  schedule_expression          = each.value
  schedule_expression_timezone = "Asia/Seoul"

  flexible_time_window {
    mode = "OFF"
  }

  target {
    arn      = "arn:aws:scheduler:::aws-sdk:rds:stopDBInstance"
    role_arn = aws_iam_role.scheduler.arn
    input    = jsonencode({ DbInstanceIdentifier = aws_db_instance.main.identifier })

    retry_policy {
      maximum_retry_attempts = 0
    }
  }
}