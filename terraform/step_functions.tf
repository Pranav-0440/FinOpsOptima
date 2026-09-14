resource "aws_iam_role" "sfn_exec_role" {
  name = "${local.app_name}-sfn-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action = "sts:AssumeRole"
        Effect = "Allow"
        Principal = {
          Service = "states.amazonaws.com"
        }
      }
    ]
  })

  tags = local.common_tags
}

resource "aws_iam_policy" "sfn_policy" {
  name        = "${local.app_name}-sfn-policy"
  description = "Permissions for Cost Optimizer Step Functions to invoke Lambdas and publish SNS callback tokens."

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      # Invoke Lambdas
      {
        Effect = "Allow"
        Action = [
          "lambda:InvokeFunction"
        ]
        Resource = [
          aws_lambda_function.ec2_scanner.arn,
          aws_lambda_function.ebs_scanner.arn,
          aws_lambda_function.eip_scanner.arn,
          aws_lambda_function.rds_scanner.arn,
          aws_lambda_function.s3_scanner.arn,
          aws_lambda_function.summary_generator.arn,
          aws_lambda_function.remediation_handler.arn
        ]
      },
      # SNS publish for Wait-for-Callback Token
      {
        Effect = "Allow"
        Action = [
          "sns:Publish"
        ]
        Resource = aws_sns_topic.cost_alerts.arn
      }
    ]
  })
}

resource "aws_iam_role_policy_attachment" "sfn_policy_attach" {
  role       = aws_iam_role.sfn_exec_role.name
  policy_arn = aws_iam_policy.sfn_policy.arn
}

resource "aws_sfn_state_machine" "cost_scanner_state_machine" {
  name     = "${local.app_name}-orchestrator"
  role_arn = aws_iam_role.sfn_exec_role.arn

  definition = jsonencode({
    Comment = "Orchestrator for AWS Cost Optimization System: Parallel Scanning and Summary Generation"
    StartAt = "RunScannersParallel"
    States = {
      # 1. Parallel Scanning
      RunScannersParallel = {
        Type = "Parallel"
        Next = "GenerateSummary"
        Branches = [
          {
            StartAt = "EC2Scanner"
            States = {
              EC2Scanner = {
                Type     = "Task"
                Resource = aws_lambda_function.ec2_scanner.arn
                End      = true
              }
            }
          },
          {
            StartAt = "EBSScanner"
            States = {
              EBSScanner = {
                Type     = "Task"
                Resource = aws_lambda_function.ebs_scanner.arn
                End      = true
              }
            }
          },
          {
            StartAt = "EIPScanner"
            States = {
              EIPScanner = {
                Type     = "Task"
                Resource = aws_lambda_function.eip_scanner.arn
                End      = true
              }
            }
          },
          {
            StartAt = "RDSScanner"
            States = {
              RDSScanner = {
                Type     = "Task"
                Resource = aws_lambda_function.rds_scanner.arn
                End      = true
              }
            }
          },
          {
            StartAt = "S3Scanner"
            States = {
              S3Scanner = {
                Type     = "Task"
                Resource = aws_lambda_function.s3_scanner.arn
                End      = true
              }
            }
          }
        ]
      }
      # 2. Generate Summary Digest
      GenerateSummary = {
        Type     = "Task"
        Resource = aws_lambda_function.summary_generator.arn
        Next     = "RequestRemediationApproval"
      }
      # 3. Request Remediation Approval (Approval Gate Simulation)
      # In a production setup, we can choose to auto-remediate specific systems 
      # or pause execution waiting for the FinOps team to click approve in the dashboard.
      RequestRemediationApproval = {
        Type = "Task"
        Resource = "arn:aws:states:::sns:publish.waitForTaskToken"
        Parameters = {
          TopicArn = aws_sns_topic.cost_alerts.arn
          Subject  = "ACTION REQUIRED: Approve Cost Optimization Remediation"
          Message = {
            "Prompt"      = "Unused or under-utilized resources require remediation. Approve action using the dashboard or API endpoint."
            "Action"      = "Stop instances / delete idle attachments"
            "TaskToken.$" = "$$.Task.Token"
          }
        }
        Next = "ExecuteRemediation"
      }
      # 4. Execute Remediation Action
      ExecuteRemediation = {
        Type     = "Task"
        Resource = aws_lambda_function.remediation_handler.arn
        End      = true
      }
    }
  })

  tags = local.common_tags
}
