# 🏢 VPC — 우리 전용 네트워크
resource "aws_vpc" "main" {
  cidr_block           = "10.0.0.0/16"
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = { Name = "${local.name}-vpc" }
}

# 🚪 인터넷 게이트웨이 — 인터넷 출입구
resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id

  tags = { Name = "${local.name}-igw" }
}

# 🌐 Public 서브넷 — EC2
resource "aws_subnet" "public_a" {
  vpc_id            = aws_vpc.main.id
  cidr_block        = "10.0.1.0/24"
  availability_zone = "ap-northeast-2a"

  tags = { Name = "${local.name}-public-a" }
}

# 🔒 Private 서브넷 — RDS
resource "aws_subnet" "private_a" {
  vpc_id            = aws_vpc.main.id
  cidr_block        = "10.0.11.0/24"
  availability_zone = "ap-northeast-2a"

  tags = { Name = "${local.name}-private-a" }
}

# 🔒 Private 서브넷 — RDS 서브넷 그룹 규칙(AZ 2개) 충족용
resource "aws_subnet" "private_c" {
  vpc_id            = aws_vpc.main.id
  cidr_block        = "10.0.12.0/24"
  availability_zone = "ap-northeast-2c"

  tags = { Name = "${local.name}-private-c" }
}

# 🪧 Public 라우팅 — 밖으로 가는 건 IGW로
resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }

  tags = { Name = "${local.name}-public-rt" }
}

resource "aws_route_table_association" "public_a" {
  subnet_id      = aws_subnet.public_a.id
  route_table_id = aws_route_table.public.id
}

# 🪧 Private 라우팅 — 인터넷 길 없음 (VPC 내부만)
resource "aws_route_table" "private" {
  vpc_id = aws_vpc.main.id

  tags = { Name = "${local.name}-private-rt" }
}

resource "aws_route_table_association" "private_a" {
  subnet_id      = aws_subnet.private_a.id
  route_table_id = aws_route_table.private.id
}

resource "aws_route_table_association" "private_c" {
  subnet_id      = aws_subnet.private_c.id
  route_table_id = aws_route_table.private.id
}