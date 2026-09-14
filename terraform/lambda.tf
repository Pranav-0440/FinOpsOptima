locals {
  jar_path = "${path.module}/../target/aws-cost-optimization-system-1.0-SNAPSHOT.jar"
}

resource "aws_lambda_function" "ec2_scanner" {
  filename      = local.jar_path
  function_name = "${local.app_name}-ec2-scanner"
  role          = aws_iam_role.lambda_exec_role.arn
  handler       = "com.costoptimizer.handler.Ec2ScannerHandler::handleRequest"
  runtime       = "java21"
  timeout       = 120
  memory_size   = 512

  environment {
    variables = {
      MOCK_AWS   = var.mock_aws ? "true" : "false"
      TABLE_NAME = aws_dynamodb_table.findings.name
    }
  }
  tags = local.common_tags
}

resource "aws_lambda_function" "ebs_scanner" {
  filename      = local.jar_path
  function_name = "${local.app_name}-ebs-scanner"
  role          = aws_iam_role.lambda_exec_role.arn
  handler       = "com.costoptimizer.handler.EbsScannerHandler::handleRequest"
  runtime       = "java21"
  timeout       = 120
  memory_size   = 512

  environment {
    variables = {
      MOCK_AWS   = var.mock_aws ? "true" : "false"
      TABLE_NAME = aws_dynamodb_table.findings.name
    }
  }
  tags = local.common_tags
}

resource "aws_lambda_function" "eip_scanner" {
  filename      = local.jar_path
  function_name = "${local.app_name}-eip-scanner"
  role          = aws_iam_role.lambda_exec_role.arn
  handler       = "com.costoptimizer.handler.EipScannerHandler::handleRequest"
  runtime       = "java21"
  timeout       = 120
  memory_size   = 512

  environment {
    variables = {
      MOCK_AWS   = var.mock_aws ? "true" : "false"
      TABLE_NAME = aws_dynamodb_table.findings.name
    }
  }
  tags = local.common_tags
}

resource "aws_lambda_function" "rds_scanner" {
  filename      = local.jar_path
  function_name = "${local.app_name}-rds-scanner"
  role          = aws_iam_role.lambda_exec_role.arn
  handler       = "com.costoptimizer.handler.RdsScannerHandler::handleRequest"
  runtime       = "java21"
  timeout       = 120
  memory_size   = 512

  environment {
    variables = {
      MOCK_AWS   = var.mock_aws ? "true" : "false"
      TABLE_NAME = aws_dynamodb_table.findings.name
    }
  }
  tags = local.common_tags
}

resource "aws_lambda_function" "s3_scanner" {
  filename      = local.jar_path
  function_name = "${local.app_name}-s3-scanner"
  role          = aws_iam_role.lambda_exec_role.arn
  handler       = "com.costoptimizer.handler.S3ScannerHandler::handleRequest"
  runtime       = "java21"
  timeout       = 120
  memory_size   = 512

  environment {
    variables = {
      MOCK_AWS   = var.mock_aws ? "true" : "false"
      TABLE_NAME = aws_dynamodb_table.findings.name
    }
  }
  tags = local.common_tags
}

resource "aws_lambda_function" "summary_generator" {
  filename      = local.jar_path
  function_name = "${local.app_name}-summary-generator"
  role          = aws_iam_role.lambda_exec_role.arn
  handler       = "com.costoptimizer.handler.SummaryGeneratorHandler::handleRequest"
  runtime       = "java21"
  timeout       = 120
  memory_size   = 512

  environment {
    variables = {
      TABLE_NAME    = aws_dynamodb_table.findings.name
      SNS_TOPIC_ARN = aws_sns_topic.cost_alerts.arn
    }
  }
  tags = local.common_tags
}

resource "aws_lambda_function" "remediation_handler" {
  filename      = local.jar_path
  function_name = "${local.app_name}-remediation-handler"
  role          = aws_iam_role.lambda_exec_role.arn
  handler       = "com.costoptimizer.handler.RemediationHandler::handleRequest"
  runtime       = "java21"
  timeout       = 120
  memory_size   = 512

  environment {
    variables = {
      MOCK_AWS   = var.mock_aws ? "true" : "false"
      TABLE_NAME = aws_dynamodb_table.findings.name
    }
  }
  tags = local.common_tags
}

resource "aws_lambda_function" "api_handler" {
  filename      = local.jar_path
  function_name = "${local.app_name}-api-handler"
  role          = aws_iam_role.lambda_exec_role.arn
  handler       = "com.costoptimizer.handler.ApiHandler::handleRequest"
  runtime       = "java21"
  timeout       = 120
  memory_size   = 512

  environment {
    variables = {
      MOCK_AWS   = var.mock_aws ? "true" : "false"
      TABLE_NAME = aws_dynamodb_table.findings.name
    }
  }
  tags = local.common_tags
}
