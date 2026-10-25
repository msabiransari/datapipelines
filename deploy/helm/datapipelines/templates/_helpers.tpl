{{/*
Chart helpers — names, the standard label set, the app port and the
LabelSelector shape check. No fullname override machinery: the reference chart
is minimal on purpose (deployment.md §6.4).
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

{{/*
The app's HTTP containerPort — ONE definition, read by deployment.yaml's `http`
containerPort and by the NetworkPolicy's app-port rule, so the two cannot drift.
*/}}
{{- define "datapipelines.appPort" -}}8080{{- end -}}

{{/*
datapipelines.requireLabelSelector (list <values path> <value>) — refuse a
non-empty value that is not a Kubernetes LabelSelector object, whose only keys
are matchLabels and matchExpressions. A bare label map
(`kubernetes.io/metadata.name: monitoring`) is NOT a selector: the API schema
rejects it, and an apply path that drops unknown fields would keep an EMPTY
selector — which selects every namespace or pod (#712 lander finding 1). An
empty value passes: whether it may be empty is the caller's rule.
*/}}
{{- define "datapipelines.requireLabelSelector" -}}
{{- $path := index . 0 -}}
{{- $selector := index . 1 -}}
{{- if $selector -}}
{{- if not (kindIs "map" $selector) -}}
{{- fail (printf "%s must be a LabelSelector object — matchLabels / matchExpressions (deployment.md §6.4)" $path) -}}
{{- end -}}
{{- range $key, $_ := $selector -}}
{{- if not (has $key (list "matchLabels" "matchExpressions")) -}}
{{- fail (printf "%s must be a LabelSelector object — matchLabels / matchExpressions — not a bare label map (found key %q; deployment.md §6.4)" $path $key) -}}
{{- end -}}
{{- end -}}
{{- end -}}
{{- end -}}
