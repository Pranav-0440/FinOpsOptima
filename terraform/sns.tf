resource "aws_sns_topic" "cost_alerts" {
  name = "${local.app_name}-alerts"
  tags = local.common_tags
}

resource "aws_sns_topic_subscription" "email_sub" {
  topic_arn = aws_sns_topic.cost_alerts.arn
  protocol  = "email"
  endpoint  = var.email_recipient
}
