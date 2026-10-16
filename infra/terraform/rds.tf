resource "aws_db_subnet_group" "main" {
  name       = "${local.name}-db-subnet"
  subnet_ids = [aws_subnet.private_a.id, aws_subnet.private_c.id]

  tags = { Name = "${local.name}-db-subnet" }
}

resource "aws_db_instance" "main" {
  identifier     = "${local.name}-db"
  engine         = "postgres"
  engine_version = "16.15"
  instance_class = "db.t4g.micro"

  allocated_storage = 20
  storage_type      = "gp2"
  storage_encrypted = true

  db_name                     = "mujoong"
  username                    = "mujoong_admin"
  manage_master_user_password = true

  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.rds.id]
  publicly_accessible    = false
  multi_az               = false
  availability_zone      = "ap-northeast-2a"

  backup_retention_period = 7
  backup_window           = "01:00-01:30"         # KST 10:00~10:30
  maintenance_window      = "mon:02:00-mon:02:30" # KST 월 11:00~11:30

  auto_minor_version_upgrade = false
  deletion_protection        = true
  skip_final_snapshot        = false
  final_snapshot_identifier  = "${local.name}-db-final"
  apply_immediately          = true

  tags = { Name = "${local.name}-db" }
}