# AWS가 미리 만들어둔 정책들 조회
data "aws_cloudfront_cache_policy" "disabled" {
  name = "Managed-CachingDisabled"
}

data "aws_cloudfront_cache_policy" "optimized" {
  name = "Managed-CachingOptimized"
}

data "aws_cloudfront_origin_request_policy" "all_viewer_except_host" {
  name = "Managed-AllViewerExceptHostHeader"
}

resource "aws_cloudfront_distribution" "main" {
  enabled     = true
  comment     = "${local.name} - 10/17 demo"
  price_class = "PriceClass_200" # 한국 포함, 가장 비싼 리전 제외

  # 🏭 오리진: EC2 (DuckDNS 도메인, HTTPS만)
  origin {
    domain_name = local.origin_domain
    origin_id   = "ec2"

    custom_origin_config {
      http_port              = 80
      https_port             = 443
      origin_protocol_policy = "https-only"
      origin_ssl_protocols   = ["TLSv1.2"]
    }
  }

  # 기본: 캐시 끔 (화면·API·WebSocket)
  default_cache_behavior {
    target_origin_id         = "ec2"
    viewer_protocol_policy   = "redirect-to-https"
    allowed_methods          = ["DELETE", "GET", "HEAD", "OPTIONS", "PATCH", "POST", "PUT"]
    cached_methods           = ["GET", "HEAD"]
    cache_policy_id          = data.aws_cloudfront_cache_policy.disabled.id
    origin_request_policy_id = data.aws_cloudfront_origin_request_policy.all_viewer_except_host.id
    compress                 = true
  }

  # Next.js 정적 파일: 캐시 켬
  ordered_cache_behavior {
    path_pattern           = "/_next/static/*"
    target_origin_id       = "ec2"
    viewer_protocol_policy = "redirect-to-https"
    allowed_methods        = ["GET", "HEAD"]
    cached_methods         = ["GET", "HEAD"]
    cache_policy_id        = data.aws_cloudfront_cache_policy.optimized.id
    compress               = true
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  # 브라우저 ↔ CloudFront 인증서: *.cloudfront.net 기본 인증서
  viewer_certificate {
    cloudfront_default_certificate = true
  }

  tags = { Name = "${local.name}-cdn" }
}