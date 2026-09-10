output "namespace_names" {
  description = "List of created Kubernetes namespace names"
  value       = [for ns in kubernetes_namespace.this : ns.metadata[0].name]
}
