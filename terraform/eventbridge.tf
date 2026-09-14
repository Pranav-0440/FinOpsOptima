resource "aws_iam_role" "eventbridge_sfn_role" {
  name = "${local.app_name}-eventbridge-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action = "sts:AssumeRole"
        Effect = "Allow"
        Principal = {
          Service = "events.amazonaws.com"
        }
      }
    ]
  })

  tags = local.common_tags
}

resource "aws_iam_policy" "eventbridge_sfn_policy" {
  name        = "${local.app_name}-eventbridge-policy"
  description = "Allows EventBridge to start execution of the cost scanner state machine."

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "states:StartExecution"
        ]
        Resource = aws_sfn_state_machine.cost_scanner_state_machine.arn
      }
    ]
  })
}

resource "aws_iam_role_policy_attachment" "eventbridge_sfn_policy_attach" {
  role       = aws_iam_role.eventbridge_sfn_role.name
  policy_arn = aws_iam_policy.eventbridge_sfn_policy.arn
}

resource "aws_cloudwatch_event_rule" "daily_scan_rule" {
  name                = "${local.app_name}-daily-scan"
  description         = "Trigger the cost optimization scanner pipeline daily"
  schedule_expression = "rate(1 day)"
  tags                = local.common_tags
}

resource "aws_cloudwatch_event_target" "trigger_sfn" {
  rule      = aws_cloudwatch_event_rule.daily_scan_rule.name
  target_id = "TriggerCostScannerStateMachine"
  arn       = aws_sfn_state_machine.cost_scanner_state_machine.arn
  role_arn  = aws_iam_role.eventbridge_sfn_role.arn
}
