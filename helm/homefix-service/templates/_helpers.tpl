{{/*
Expand the name of the chart / service.
Prefers .Values.serviceName so every microservice gets a stable identity.
*/}}
{{- define "homefix-service.name" -}}
{{- default .Values.serviceName .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Fully qualified app name.
*/}}
{{- define "homefix-service.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Values.serviceName .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{/*
Chart name and version label.
*/}}
{{- define "homefix-service.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Common labels applied to every resource.
*/}}
{{- define "homefix-service.labels" -}}
helm.sh/chart: {{ include "homefix-service.chart" . }}
{{ include "homefix-service.selectorLabels" . }}
app.kubernetes.io/version: {{ .Values.image.tag | default .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: homefix
environment: {{ .Values.environment | quote }}
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
