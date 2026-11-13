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

{{/*
The managed lake reads' egress proxy (#845, values.yaml `lake.egressProxy`).
Its own name and label set: `app.kubernetes.io/name` is
`<chart>-egress-proxy`, so the app's selectorLabels (Service, PDB, HPA, the
management NetworkPolicy) never select a proxy pod and the proxy's never
select an app pod. The port is ONE definition, read by the Deployment, the
Service, the NetworkPolicy, the Envoy listener and the app's env entry.
*/}}
{{- define "datapipelines.egressProxy.fullname" -}}
{{- printf "%s-egress-proxy" (include "datapipelines.fullname" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "datapipelines.egressProxy.selectorLabels" -}}
app.kubernetes.io/name: {{ printf "%s-egress-proxy" (include "datapipelines.name" .) | trunc 63 | trimSuffix "-" }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: egress-proxy
{{- end -}}

{{- define "datapipelines.egressProxy.labels" -}}
{{ include "datapipelines.egressProxy.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version }}
{{- end -}}

{{- define "datapipelines.egressProxy.port" -}}3128{{- end -}}

{{/*
#863 — lake.egressProxy.replicaCount as a positive whole number of replicas,
the ONE value the Deployment's `replicas` renders from. Validated whenever the
proxy is enabled (the Deployment consumes it); with the proxy disabled nothing
consumes or validates it and no proxy object renders.
*/}}
{{- define "datapipelines.egressProxy.replicaCount" -}}
{{- $value := .Values.lake.egressProxy.replicaCount -}}
{{- if not (or (kindIs "float64" $value) (kindIs "int64" $value) (kindIs "int" $value)) -}}
{{- fail "lake.egressProxy.replicaCount must be a whole number of replicas, unquoted (deployment.md §6.4)" -}}
{{- end -}}
{{- if or (le (float64 $value) 0.0) (ne (float64 $value) (float64 (int64 $value))) -}}
{{- fail "lake.egressProxy.replicaCount must be a whole number of replicas greater than 0 (deployment.md §6.4)" -}}
{{- end -}}
{{- if gt (float64 $value) 2147483647.0 -}}
{{- fail "lake.egressProxy.replicaCount must not exceed 2147483647 (Kubernetes int32, deployment.md §6.4)" -}}
{{- end -}}
{{- int64 $value -}}
{{- end -}}

{{/*
#863 — lake.egressProxy.podDisruptionBudget.minAvailable, consumed only by the
PDB template (which renders only with the proxy AND the PDB enabled, where this
helper is the coercion point): a whole number ≥ 1 and STRICTLY below
replicaCount. minAvailable = replicaCount can never be disrupted — a budget the
eviction controller can never honour on a one-node deployment deadlocks every
voluntary eviction — and a non-positive budget promises nothing; both are
refused at render.
*/}}
{{- define "datapipelines.egressProxy.pdb.minAvailable" -}}
{{- $value := .Values.lake.egressProxy.podDisruptionBudget.minAvailable -}}
{{- $replicas := int64 (include "datapipelines.egressProxy.replicaCount" .) -}}
{{- if not (or (kindIs "float64" $value) (kindIs "int64" $value) (kindIs "int" $value)) -}}
{{- fail "lake.egressProxy.podDisruptionBudget.minAvailable must be a whole number, unquoted (deployment.md §6.4)" -}}
{{- end -}}
{{- if or (lt (float64 $value) 1.0) (ne (float64 $value) (float64 (int64 $value))) -}}
{{- fail "lake.egressProxy.podDisruptionBudget.minAvailable must be a whole number at least 1 (deployment.md §6.4)" -}}
{{- end -}}
{{- if ge (int64 $value) $replicas -}}
{{- fail (printf "lake.egressProxy.podDisruptionBudget.minAvailable (%d) must be strictly below lake.egressProxy.replicaCount (%d) — minAvailable = replicaCount can never be disrupted and deadlocks every voluntary eviction (deployment.md §6.4)" (int64 $value) $replicas) -}}
{{- end -}}
{{- int64 $value -}}
{{- end -}}

{{/*
#863 — the three lake.egressProxy.topologySpreadConstraints fields, consumed
only by the Deployment's spread block (which renders only with the spread
enabled). The constraint's labelSelector is NOT a value: the template renders
the proxy's own selectorLabels under it, so a spread constraint can never
select app pods. maxSkew is a positive whole number; whenUnsatisfiable is one
of Kubernetes' two values — ScheduleAnyway keeps pods schedulable when the
cluster is too small to satisfy the spread (the documented example's choice),
DoNotSchedule refuses the placement; topologyKey names the domain (the
hostname: one proxy pod per node, best effort).
*/}}
{{- define "datapipelines.egressProxy.spread.maxSkew" -}}
{{- $value := .Values.lake.egressProxy.topologySpreadConstraints.maxSkew -}}
{{- if not (or (kindIs "float64" $value) (kindIs "int64" $value) (kindIs "int" $value)) -}}
{{- fail "lake.egressProxy.topologySpreadConstraints.maxSkew must be a whole number, unquoted (deployment.md §6.4)" -}}
{{- end -}}
{{- if or (le (float64 $value) 0.0) (ne (float64 $value) (float64 (int64 $value))) -}}
{{- fail "lake.egressProxy.topologySpreadConstraints.maxSkew must be a whole number greater than 0 (deployment.md §6.4)" -}}
{{- end -}}
{{- if gt (float64 $value) 2147483647.0 -}}
{{- fail "lake.egressProxy.topologySpreadConstraints.maxSkew must not exceed 2147483647 (Kubernetes int32, deployment.md §6.4)" -}}
{{- end -}}
{{- int64 $value -}}
{{- end -}}

{{- define "datapipelines.egressProxy.spread.whenUnsatisfiable" -}}
{{- $value := toString .Values.lake.egressProxy.topologySpreadConstraints.whenUnsatisfiable -}}
{{- if not (or (eq $value "ScheduleAnyway") (eq $value "DoNotSchedule")) -}}
{{- fail (printf "lake.egressProxy.topologySpreadConstraints.whenUnsatisfiable must be ScheduleAnyway or DoNotSchedule, not %q (deployment.md §6.4)" $value) -}}
{{- end -}}
{{- $value -}}
{{- end -}}

{{- define "datapipelines.egressProxy.spread.topologyKey" -}}
{{- $value := .Values.lake.egressProxy.topologySpreadConstraints.topologyKey -}}
{{- if or (not (kindIs "string" $value)) (eq (toString $value) "") -}}
{{- fail "lake.egressProxy.topologySpreadConstraints.topologyKey must be a non-empty string, e.g. kubernetes.io/hostname (deployment.md §6.4)" -}}
{{- end -}}
{{- toString $value -}}
{{- end -}}

{{- define "datapipelines.egressProxy.image" -}}
{{- $image := .Values.lake.egressProxy.image -}}
{{- if not (hasPrefix "sha256:" (toString $image.digest)) -}}
{{- fail "lake.egressProxy.image.digest must be a sha256: digest — the proxy image is pinned by digest (deployment.md §6.4)" -}}
{{- end -}}
{{- printf "%s:%s@%s" $image.repository $image.tag $image.digest -}}
{{- end -}}

{{/*
lake.egressProxy.maxAgeSeconds as a positive whole number of seconds — the ONE
value the Envoy config (both durations and the /dp-egress declaration) and the
app's DATAPIPELINES_LAKE_READS_EGRESS_MAX_AGE_SECONDS are rendered from.
*/}}
{{- define "datapipelines.egressProxy.maxAgeSeconds" -}}
{{- $value := .Values.lake.egressProxy.maxAgeSeconds -}}
{{- if not (or (kindIs "float64" $value) (kindIs "int64" $value) (kindIs "int" $value)) -}}
{{- fail "lake.egressProxy.maxAgeSeconds must be a whole number of seconds, unquoted (deployment.md §6.4)" -}}
{{- end -}}
{{- if or (le (float64 $value) 0.0) (ne (float64 $value) (float64 (int64 $value))) -}}
{{- fail "lake.egressProxy.maxAgeSeconds must be a whole number of seconds greater than 0 (deployment.md §6.4)" -}}
{{- end -}}
{{- int64 $value -}}
{{- end -}}

{{/*
#862 — the five lake-storage env names the renderer reads, as one comma-separated
list (deployment.md §4.3). The proxy pod's init container receives ONLY these
(least privilege: no other app value reaches the proxy pod), each from extraEnv when
set there, else from the same-named key of existingSecret (optional) — the app
container's own precedence. A string rather than JSON because helm's fromJson
decodes dictionaries only.
*/}}
{{- define "datapipelines.egressRender.envNames" -}}
DATAPIPELINES_LAKE_ENABLED,DATAPIPELINES_LAKE_S3_BUCKET,DATAPIPELINES_LAKE_S3_ENDPOINT,DATAPIPELINES_LAKE_S3_REGION,DATAPIPELINES_LAKE_S3_PATH_STYLE
{{- end -}}

{{/*
#862 — every chart value the renderer consumes, as one YAML doc for the checksum
that rolls the proxy pods: the extraEnv lake-storage values actually set, the
chart's own max-age, and the existingSecret NAME the unset inputs are read from. A
changed input changes the checksum; the proxy pod rolls and its init container
re-renders (deployment.md §6.4). The Secret's content is not chart input: a changed
key needs a restart of both deployments (deployment.md §6.4).
*/}}
{{- define "datapipelines.egressRender.inputs" -}}
{{- $names := splitList "," (include "datapipelines.egressRender.envNames" .) -}}
{{- $m := dict "DATAPIPELINES_LAKE_READS_EGRESS_MAX_AGE_SECONDS" (include "datapipelines.egressProxy.maxAgeSeconds" .) "existingSecret" (toString .Values.existingSecret) -}}
{{- range $name := $names -}}
{{- if hasKey $.Values.extraEnv $name -}}
{{- $_ := set $m $name (get $.Values.extraEnv $name) -}}
{{- end -}}
{{- end -}}
{{- toYaml $m -}}
{{- end -}}
