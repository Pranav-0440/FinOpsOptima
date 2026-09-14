terraform {
  required_version = ">= 1.5.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

provider "aws" {
  region = var.aws_region
}

variable "aws_region" {
  type        = string
  default     = "us-east-1"
  description = "The target AWS region for all resources."
}

variable "environment" {
  type        = string
  default     = "production"
  description = "Application deployment environment."
}

variable "email_recipient" {
  type        = string
  default     = "admin@example.com"
  description = "Email address to receive cost optimization digests."
}

variable "mock_aws" {
  type        = bool
  default     = true
  description = "Whether scanner Lambdas run in mock data generation mode."
}

locals {
  app_name = "aws-cost-optimizer"
  common_tags = {
    Project     = "AWS Cost Optimization System"
    Environment = var.environment
    ManagedBy   = "Terraform"
  }
}

output "api_endpoint" {
  value       = "${aws_api_gateway_stage.api_stage.invoke_url}/api"
  description = "API Gateway endpoint URL"
}

output "sns_topic_arn" {
  value       = aws_sns_topic.cost_alerts.arn
  description = "SNS Topic ARN for cost notifications"
}

output "step_function_arn" {
  value       = aws_sfn_state_machine.cost_scanner_state_machine.arn
  description = "State Machine ARN orchestrator"
}
