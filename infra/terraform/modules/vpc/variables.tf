variable "name_prefix" {
  description = "Prefix for all resource names"
  type        = string
}

variable "vpc_cidr" {
  description = "VPC CIDR block"
  type        = string
}

variable "availability_zones" {
  description = "List of availability zones"
  type        = list(string)
}

variable "public_subnet_cidrs" {
  description = "CIDRs for public subnets"
  type        = list(string)
}

variable "private_subnet_cidrs" {
  description = "CIDRs for private subnets"
  type        = list(string)
}

variable "database_subnet_cidrs" {
  description = "CIDRs for database subnets"
  type        = list(string)
}

variable "tags" {
  description = "Common resource tags"
  type        = map(string)
  default     = {}
}
