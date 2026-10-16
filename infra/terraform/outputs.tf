output "ec2_public_ip" {
  value = aws_eip.ecs.public_ip
}

output "ec2_instance_id" {
  value = aws_instance.ecs.id
}

output "ecr_urls" {
  value = { for k, r in aws_ecr_repository.app : k => r.repository_url }
}

output "cloudfront_url" {
  value = "https://${aws_cloudfront_distribution.main.domain_name}"
}