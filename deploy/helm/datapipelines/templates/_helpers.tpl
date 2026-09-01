{{/*
Chart helpers — names and the standard label set only. No fullname override
machinery: the reference chart is minimal on purpose (deployment.md §6.4).
*/}}

{{- define "datapipelines.name" -}}
{{- .Chart.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "datapipelines.fullname" -}}
{{- printf "%s-%s" .Release.Name (include "datapipelines.name" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "datapipelines.labels" -}}
app.kubernetes.io/name: {{ include "datapipelines.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version }}
{{- end -}}

{{- define "datapipelines.selectorLabels" -}}
app.kubernetes.io/name: {{ include "datapipelines.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "datapipelines.image" -}}
{{- printf "%s:%s" .Values.image.repository (.Values.image.tag | default .Chart.AppVersion) -}}
{{- end -}}
