##############################################################
# WAF Module — HomeFix
# AWS WAFv2 Web ACL for REGIONAL scope (API Gateway).
# Rules include: OWASP Core Rule Set, Known Bad Inputs,
# Anonymous IP list, SQL injection, rate limiting.
##############################################################

# ─────────────────────────────────────────────
# CloudWatch Log Group for WAF
# ─────────────────────────────────────────────
resource "aws_cloudwatch_log_group" "waf" {
  # WAF log group name MUST start with "aws-waf-logs-"
  name              = "aws-waf-logs-${var.name_prefix}"
  retention_in_days = 90

  tags = var.tags
}

# ─────────────────────────────────────────────
# WAFv2 Web ACL
# ─────────────────────────────────────────────
resource "aws_wafv2_web_acl" "this" {
  name        = "${var.name_prefix}-web-acl"
  description = "HomeFix WAF Web ACL — OWASP Top 10 + rate limiting"
  scope       = "REGIONAL"

  default_action {
    allow {}
  }

  # ── Rule 1: AWS Managed — AWSManagedRulesCommonRuleSet (OWASP CRS) ──
  rule {
    name     = "AWSManagedRulesCommonRuleSet"
    priority = 1

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        name        = "AWSManagedRulesCommonRuleSet"
        vendor_name = "AWS"

        # Exclude size restriction on bodies — service-level validation handles this
        rule_action_override {
          name = "SizeRestrictions_BODY"
          action_to_use {
            count {}
          }
        }
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.name_prefix}-CRS"
      sampled_requests_enabled   = true
    }
  }

  # ── Rule 2: AWS Managed — Known Bad Inputs ──
  rule {
    name     = "AWSManagedRulesKnownBadInputsRuleSet"
    priority = 2

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        name        = "AWSManagedRulesKnownBadInputsRuleSet"
        vendor_name = "AWS"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.name_prefix}-KBI"
      sampled_requests_enabled   = true
    }
  }

  # ── Rule 3: AWS Managed — SQL Database ──
  rule {
    name     = "AWSManagedRulesSQLiRuleSet"
    priority = 3

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        name        = "AWSManagedRulesSQLiRuleSet"
        vendor_name = "AWS"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.name_prefix}-SQLi"
      sampled_requests_enabled   = true
    }
  }

  # ── Rule 4: AWS Managed — Anonymous IP List ──
  rule {
    name     = "AWSManagedRulesAnonymousIpList"
    priority = 4

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        name        = "AWSManagedRulesAnonymousIpList"
        vendor_name = "AWS"

        # Count (not block) Tor exits — legitimate users may use VPNs
        rule_action_override {
          name = "AnonymousIPList"
          action_to_use {
            count {}
          }
        }
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.name_prefix}-AnonIP"
      sampled_requests_enabled   = true
    }
  }

  # ── Rule 5: AWS Managed — Amazon IP Reputation List ──
  rule {
    name     = "AWSManagedRulesAmazonIpReputationList"
    priority = 5

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        name        = "AWSManagedRulesAmazonIpReputationList"
        vendor_name = "AWS"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.name_prefix}-IPReputation"
      sampled_requests_enabled   = true
    }
  }

  # ── Rule 6: Rate Limiting per IP ──
  rule {
    name     = "RateLimitPerIP"
    priority = 6

    action {
      block {}
    }

    statement {
      rate_based_statement {
        limit              = var.rate_limit_per_5min
        aggregate_key_type = "IP"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.name_prefix}-RateLimit"
      sampled_requests_enabled   = true
    }
  }

  # ── Rule 7: Geo-block (optional) ──
  dynamic "rule" {
    for_each = length(var.block_country_codes) > 0 ? [1] : []

    content {
      name     = "GeoBlock"
      priority = 7

      action {
        block {}
      }

      statement {
        geo_match_statement {
          country_codes = var.block_country_codes
        }
      }

      visibility_config {
        cloudwatch_metrics_enabled = true
        metric_name                = "${var.name_prefix}-GeoBlock"
        sampled_requests_enabled   = true
      }
    }
  }

  visibility_config {
    cloudwatch_metrics_enabled = true
    metric_name                = "${var.name_prefix}-web-acl"
    sampled_requests_enabled   = true
  }

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-web-acl"
  })
}

# ─────────────────────────────────────────────
# WAF Logging Configuration
# ─────────────────────────────────────────────
resource "aws_wafv2_web_acl_logging_configuration" "this" {
  log_destination_configs = [aws_cloudwatch_log_group.waf.arn]
  resource_arn            = aws_wafv2_web_acl.this.arn

  logging_filter {
    default_behavior = "KEEP"

    filter {
      behavior    = "KEEP"
      requirement = "MEETS_ANY"

      condition {
        action_condition {
          action = "BLOCK"
        }
      }

      condition {
        action_condition {
          action = "COUNT"
        }
      }
    }
  }
}
