# GitHub Actions → AWS 배포용 OIDC 역할 (장기 액세스 키 없음)
# - images 작업(ci.yml, main push): ECR 게시                 → sub = repo:<레포>:ref:refs/heads/main
# - deploy 작업(deploy.yml, environment demo): ECS 배포       → sub = repo:<레포>:environment:demo
# 역할 이름은 deploy.yml의 검증 규칙(role/mujoong-gha-deploy)과 같아야 한다.

locals {
  github_repo = "team-jomyung/mujungi"
}

# GitHub OIDC 공급자 (계정에 URL당 1개만 존재 가능 — 이미 있으면 import 또는 data로 교체)
resource "aws_iam_openid_connect_provider" "github" {
  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]

  tags = { Name = "${local.name}-github-oidc" }
}

# ──────────── 신뢰 정책: 이 레포의 main push · demo 환경만 ────────────
data "aws_iam_policy_document" "gha_assume" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github.arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values = [
        "${local.gha_repo_sub}:ref:refs/heads/main",
        "${local.gha_repo_sub}:environment:demo",
      ]
    }
  }
}

resource "aws_iam_role" "gha_deploy" {
  name                 = "mujoong-gha-deploy"
  assume_role_policy   = data.aws_iam_policy_document.gha_assume.json
  max_session_duration = 3600

  tags = { Name = "mujoong-gha-deploy" }
}

# ──────────── 권한: ECR 게시 · ECS 배포 · migrate 실행 (최소 범위) ────────────
data "aws_iam_policy_document" "gha_deploy" {
  # ECR 로그인 토큰 (리소스 지정 불가)
  statement {
    sid       = "EcrAuth"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  # 우리 레포에만 push · 조회
  statement {
    sid = "EcrPushPull"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:InitiateLayerUpload",
      "ecr:UploadLayerPart",
      "ecr:CompleteLayerUpload",
      "ecr:PutImage",
      "ecr:BatchGetImage",
      "ecr:GetDownloadUrlForLayer",
      "ecr:DescribeImages",
    ]
    resources = [for r in aws_ecr_repository.app : r.arn]
  }

  # 태스크 정의 조회 · 등록 (리소스 지정 불가)
  statement {
    sid       = "EcsTaskDefinitions"
    actions   = ["ecs:DescribeTaskDefinition", "ecs:RegisterTaskDefinition"]
    resources = ["*"]
  }

  # 우리 클러스터의 서비스만 갱신 · 조회
  statement {
    sid     = "EcsServices"
    actions = ["ecs:UpdateService", "ecs:DescribeServices"]
    resources = [
      aws_ecs_service.nginx.id,
      aws_ecs_service.web.id,
      aws_ecs_service.core_api.id,
      aws_ecs_service.realtime.id,
    ]
  }

  # migrate 일회성 태스크 실행 (우리 클러스터에서만)
  statement {
    sid       = "EcsRunMigrate"
    actions   = ["ecs:RunTask"]
    resources = ["arn:aws:ecs:ap-northeast-2:${data.aws_caller_identity.current.account_id}:task-definition/mujoong-migrate:*"]

    condition {
      test     = "ArnEquals"
      variable = "ecs:cluster"
      values   = [aws_ecs_cluster.main.arn]
    }
  }

  # 태스크 상태 · 컨테이너 인스턴스 연결 확인
  statement {
    sid = "EcsDescribe"
    actions = [
      "ecs:DescribeTasks",
      "ecs:ListContainerInstances",
      "ecs:DescribeContainerInstances",
    ]
    resources = ["*"]
  }

  # 새 리비전 등록 · migrate 실행 시 실행 역할 전달 (ECS 태스크에게만)
  statement {
    sid       = "PassExecutionRole"
    actions   = ["iam:PassRole"]
    resources = [aws_iam_role.ecs_execution.arn]

    condition {
      test     = "StringEquals"
      variable = "iam:PassedToService"
      values   = ["ecs-tasks.amazonaws.com"]
    }
  }

  # migrate 실패 시 로그 일부 출력
  statement {
    sid       = "MigrateLogs"
    actions   = ["logs:FilterLogEvents", "logs:DescribeLogStreams", "logs:GetLogEvents"]
    resources = ["${aws_cloudwatch_log_group.app["migrate"].arn}:*"]
  }
}

resource "aws_iam_role_policy" "gha_deploy" {
  name   = "mujoong-gha-deploy"
  role   = aws_iam_role.gha_deploy.id
  policy = data.aws_iam_policy_document.gha_deploy.json
}

output "gha_deploy_role_arn" {
  description = "GitHub 저장소 변수 AWS_DEPLOY_ROLE_ARN에 넣는 값"
  value       = aws_iam_role.gha_deploy.arn
}
