output "api_id" {
  description = "API Gateway HTTP API ID"
  value       = aws_apigatewayv2_api.this.id
}

output "invoke_url" {
  description = "URL to invoke the API (stage endpoint)"
  value       = aws_apigatewayv2_stage.default.invoke_url
}

output "execution_arn" {
  description = "Execution ARN for the API (used in Lambda permissions)"
  value       = aws_apigatewayv2_api.this.execution_arn
}

output "authorizer_id" {
  description = "JWT authorizer ID"
  value       = aws_apigatewayv2_authorizer.jwt.id
}

output "stage_name" {
  description = "Deployed stage name"
  value       = aws_apigatewayv2_stage.default.name
}
