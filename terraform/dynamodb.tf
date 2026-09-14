resource "aws_dynamodb_table" "findings" {
  name         = "CostSavingsFindings"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "resource_id"
  range_key    = "resource_type"

  attribute {
    name = "resource_id"
    type = "S"
  }

  attribute {
    name = "resource_type"
    type = "S"
  }

  tags = local.common_tags
}
