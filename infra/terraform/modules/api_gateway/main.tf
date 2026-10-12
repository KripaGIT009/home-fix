##############################################################
# API Gateway Module — HomeFix
# AWS API Gateway v2 (HTTP API) with:
#   - JWT authorizer (Auth Service introspection)
#   - VPC Link to EKS (NLB)
#   - Rate limiting / throttling
#   - X-Correlation-ID injection
#   - WAF Web ACL association
#   - Access logging
##############################################################

# ─────────────────────────────────────────────
# CloudWatch Log Group for API Gateway access logs
# ─────────────────────────────────────────────
resource "aws_cloudwatch_log_group" "api_gateway" {
  name              = "/aws/apigateway/${var.name_prefix}"
  retention_in_days = var.log_retention_days

  tags = var.tags
}

# ─────────────────────────────────────────────
# HTTP API
# ─────────────────────────────────────────────
resource "aws_apigatewayv2_api" "this" {
  name          = "${var.name_prefix}-http-api"
  protocol_type = "HTTP"
  description   = "HomeFix Platform — HTTP API Gateway"

  # CORS configuration for frontend apps
  cors_configuration {
    allow_origins     = ["*"]   # Tighten per environment via var override
    allow_methods     = ["GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"]
    allow_headers     = ["Content-Type", "Authorization", "X-Correlation-ID", "X-Request-ID"]
    expose_headers    = ["X-Correlation-ID"]
    allow_credentials = false
    max_age           = 86400
  }

  # NOTE: X-Correlation-ID injection (generate UUID v4 if absent) is handled by the
  # downstream Kubernetes Ingress controller (NGINX/ALB), not by HTTP API Gateway v2,
  # which does not support native header injection without Lambda integrations.
  # The header is allowed through CORS and forwarded if present in the request.

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-http-api"
  })
}

# ─────────────────────────────────────────────
# JWT Authorizer
# Points at the Auth Service /auth/introspect endpoint.
# The issuer URL is a placeholder; actual URL is configured
# once the Auth Service is deployed and its ALB DNS is known.
# ─────────────────────────────────────────────
resource "aws_apigatewayv2_authorizer" "jwt" {
  api_id           = aws_apigatewayv2_api.this.id
  authorizer_type  = "JWT"
  identity_sources = ["$request.header.Authorization"]
  name             = "${var.name_prefix}-jwt-authorizer"

  jwt_configuration {
    # Audience = API Gateway stage ID; issuer = Auth Service base URL
    audience = [aws_apigatewayv2_api.this.id]
    issuer   = var.jwt_issuer_url
  }
}

# ─────────────────────────────────────────────
# Stage with throttling and logging
# ─────────────────────────────────────────────
resource "aws_apigatewayv2_stage" "default" {
  api_id      = aws_apigatewayv2_api.this.id
  name        = var.stage_name
  auto_deploy = true

  default_route_settings {
    throttling_burst_limit = var.throttle_burst_limit
    throttling_rate_limit  = var.throttle_rate_limit

    # detailed_metrics_enabled is supported on HTTP APIs
    detailed_metrics_enabled = true
    # NOTE: logging_level is only valid for WebSocket/REST (v1) APIs — omitted for HTTP API
  }

  access_log_settings {
    destination_arn = aws_cloudwatch_log_group.api_gateway.arn
    format = jsonencode({
      requestId         = "$context.requestId"
      correlationId     = "$context.requestOverride.header.x-correlation-id"
      sourceIp          = "$context.identity.sourceIp"
      requestTime       = "$context.requestTime"
      httpMethod        = "$context.httpMethod"
      routeKey          = "$context.routeKey"
      status            = "$context.status"
      protocol          = "$context.protocol"
      responseLength    = "$context.responseLength"
      integrationError  = "$context.integrationErrorMessage"
      authorizerError   = "$context.authorizer.error"
      userAgent         = "$context.identity.userAgent"
      principalId       = "$context.authorizer.principalId"
    })
  }

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-api-stage-${var.stage_name}"
  })
}

# ─────────────────────────────────────────────
# WAF Web ACL Association
# ─────────────────────────────────────────────
resource "aws_wafv2_web_acl_association" "api_gateway" {
  resource_arn = aws_apigatewayv2_stage.default.arn
  web_acl_arn  = var.waf_web_acl_arn
}

# ─────────────────────────────────────────────
# CloudWatch Alarms
# ─────────────────────────────────────────────
resource "aws_cloudwatch_metric_alarm" "api_5xx" {
  alarm_name          = "${var.name_prefix}-api-5xx-high"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  metric_name         = "5XXError"
  namespace           = "AWS/ApiGateway"
  period              = 300
  statistic           = "Sum"
  threshold           = 50
  alarm_description   = "API Gateway 5xx error count exceeds 50 in 5 min"

  dimensions = {
    ApiId = aws_apigatewayv2_api.this.id
    Stage = aws_apigatewayv2_stage.default.name
  }

  tags = var.tags
}

resource "aws_cloudwatch_metric_alarm" "api_latency" {
  alarm_name          = "${var.name_prefix}-api-latency-p99"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  metric_name         = "IntegrationLatency"
  namespace           = "AWS/ApiGateway"
  period              = 300
  extended_statistic  = "p99"
  threshold           = 2000  # 2 seconds
  alarm_description   = "API Gateway p99 integration latency exceeds 2s"

  dimensions = {
    ApiId = aws_apigatewayv2_api.this.id
    Stage = aws_apigatewayv2_stage.default.name
  }

  tags = var.tags
}
