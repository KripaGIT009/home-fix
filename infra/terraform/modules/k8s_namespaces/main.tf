##############################################################
# Kubernetes Namespaces Module — HomeFix
# Creates the six namespaces that group the 19 microservices
# as defined in the HomeFix architecture:
#
#   auth-customer-provider          → Auth, Customer, Provider
#   booking-dispatch-location       → Booking, Dispatch, Location
#   payment-invoice-rating          → Payment, Invoice, Rating
#   notification-chat-complaint     → Notification, Chat, Complaint
#   admin-reporting-promotion       → Admin, Reporting, Promotion
#   catalog-pricing-verification    → Catalog, Pricing, Verification
##############################################################

locals {
  namespaces = {
    "auth-customer-provider" = {
      description = "Auth Service, Customer Service, Provider Service"
      tier        = "core-identity"
    }
    "booking-dispatch-location" = {
      description = "Booking Service, Dispatch Engine, Location Service"
      tier        = "core-booking"
    }
    "payment-invoice-rating" = {
      description = "Payment Service, Invoice Service, Rating & Review Service"
      tier        = "core-financial"
    }
    "notification-chat-complaint" = {
      description = "Notification Service, Chat Service, Complaint Service"
      tier        = "engagement"
    }
    "admin-reporting-promotion" = {
      description = "Admin Service, Reporting Service, Promotion Service"
      tier        = "operations"
    }
    "catalog-pricing-verification" = {
      description = "Service Catalog, Pricing Engine, Verification Service, Outbox Processor"
      tier        = "catalog-ops"
    }
  }
}

resource "kubernetes_namespace" "this" {
  for_each = local.namespaces

  metadata {
    name = each.key

    labels = merge(var.labels, {
      "app.kubernetes.io/managed-by" = "terraform"
      "homefix.io/tier"              = each.value.tier
    })

    annotations = {
      "homefix.io/description" = each.value.description
    }
  }
}

# ─────────────────────────────────────────────
# Resource Quotas per Namespace
# Prevents a single namespace from consuming all cluster resources.
# ─────────────────────────────────────────────
resource "kubernetes_resource_quota" "this" {
  for_each = local.namespaces

  metadata {
    name      = "default-quota"
    namespace = kubernetes_namespace.this[each.key].metadata[0].name
  }

  spec {
    hard = {
      "requests.cpu"    = "8"
      "requests.memory" = "16Gi"
      "limits.cpu"      = "16"
      "limits.memory"   = "32Gi"
      "pods"            = "50"
      "services"        = "20"
    }
  }
}

# ─────────────────────────────────────────────
# LimitRange per Namespace
# Sets default resource requests/limits for containers that
# don't specify their own.
# ─────────────────────────────────────────────
resource "kubernetes_limit_range" "this" {
  for_each = local.namespaces

  metadata {
    name      = "default-limits"
    namespace = kubernetes_namespace.this[each.key].metadata[0].name
  }

  spec {
    limit {
      type = "Container"
      default = {
        cpu    = "500m"
        memory = "512Mi"
      }
      default_request = {
        cpu    = "100m"
        memory = "128Mi"
      }
      max = {
        cpu    = "4"
        memory = "8Gi"
      }
    }

    limit {
      type = "Pod"
      max = {
        cpu    = "8"
        memory = "16Gi"
      }
    }
  }
}

# ─────────────────────────────────────────────
# Network Policies
# Default deny-all ingress; services open ports explicitly via
# their own NetworkPolicy resources deployed by Helm charts.
# ─────────────────────────────────────────────
resource "kubernetes_network_policy" "deny_all_ingress" {
  for_each = local.namespaces

  metadata {
    name      = "deny-all-ingress"
    namespace = kubernetes_namespace.this[each.key].metadata[0].name
  }

  spec {
    pod_selector {}  # Selects all pods in namespace

    policy_types = ["Ingress"]
    # No ingress rules = deny all ingress by default
  }
}

resource "kubernetes_network_policy" "allow_same_namespace" {
  for_each = local.namespaces

  metadata {
    name      = "allow-same-namespace"
    namespace = kubernetes_namespace.this[each.key].metadata[0].name
  }

  spec {
    pod_selector {}

    policy_types = ["Ingress"]

    ingress {
      from {
        namespace_selector {
          match_labels = {
            "kubernetes.io/metadata.name" = each.key
          }
        }
      }
    }
  }
}

resource "kubernetes_network_policy" "allow_from_api_gateway" {
  for_each = local.namespaces

  metadata {
    name      = "allow-from-api-gateway"
    namespace = kubernetes_namespace.this[each.key].metadata[0].name
  }

  spec {
    pod_selector {}

    policy_types = ["Ingress"]

    ingress {
      from {
        namespace_selector {
          match_labels = {
            "homefix.io/tier" = "ingress"
          }
        }
      }
      ports {
        port     = "8080"
        protocol = "TCP"
      }
    }
  }
}
