{{/*
Service name. Required: every release must say which service it is.
*/}}
{{- define "homefix-service.name" -}}
{{- $svc := required "serviceName is required (set it in helm/services/<service>.yaml)" .Values.serviceName -}}
{{- default $svc .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Resource name. Deliberately the bare service name (not "<release>-<name>"), so
in-cluster DNS is <service>:<port> exactly as in docker-compose.core.yml and the
services' built-in URL defaults resolve without extra wiring.
*/}}
{{- define "homefix-service.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- include "homefix-service.name" . -}}
{{- end -}}
{{- end -}}

{{/*
Chart name and version label.
*/}}
{{- define "homefix-service.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Port the service listens on.
*/}}
{{- define "homefix-service.port" -}}
{{- required "containerPort is required (set it in helm/services/<service>.yaml)" .Values.containerPort | int -}}
{{- end -}}

{{/*
Common labels applied to every resource.
*/}}
{{- define "homefix-service.labels" -}}
helm.sh/chart: {{ include "homefix-service.chart" . }}
{{ include "homefix-service.selectorLabels" . }}
app.kubernetes.io/version: {{ .Values.image.tag | default .Chart.AppVersion | trunc 63 | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: homefix
environment: {{ required "environment is required (values-staging.yaml or values-production.yaml)" .Values.environment | quote }}
{{- end -}}

{{/*
Selector labels — stable across upgrades.
*/}}
{{- define "homefix-service.selectorLabels" -}}
app.kubernetes.io/name: {{ include "homefix-service.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app: {{ include "homefix-service.name" . }}
{{- end -}}

{{/*
ServiceAccount name to use.
*/}}
{{- define "homefix-service.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "homefix-service.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{/*
Name of the existing Secret the service reads credentials from.
*/}}
{{- define "homefix-service.secretName" -}}
{{- default (printf "%s-secrets" (include "homefix-service.fullname" .)) .Values.secrets.name -}}
{{- end -}}
