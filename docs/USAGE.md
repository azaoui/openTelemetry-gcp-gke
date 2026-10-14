# Guide d'usage : OpenTelemetry dans la console Google Cloud

Captures réalisées sur ce lab déployé sur GKE, avec le loadgen actif. Les identifiants de projet sont floutés.

Légende des captures :
- cadre **orange** = élément cliqué ;
- cadre **rouge** = zone à observer ;
- les numéros renvoient au bandeau d'explication en bas de chaque image.

Vidéo commentée (narration en français) : [demo-opentelemetry-cloud-trace.mp4](demo-opentelemetry-cloud-trace.mp4)

| # | Écran | Ce qu'on apprend | Capture |
|---|---|---|---|
| 1 | Explorateur Trace | Carte de densité, filtres de segments, liste des spans | [01](screenshots/01-explorateur-trace.png) |
| 2 | Filtre service | Isoler un service via `service.name` | [02](screenshots/02-filtrer-sur-un-service.png) |
| 3 | Trace lente | Lire un waterfall : appels séquentiels = goulot ; span `persistOrder` sans code | [03](screenshots/03-ouvrir-une-trace-lente.png) |
| 4 | Attributs du span | Attributs HTTP + enrichissement Kubernetes (`k8s_attributes`) | [04](screenshots/04-attributs-dun-span.png) |
| 5 | Attributs ressource | `service.*` (variables `OTEL_*`), infos agent/SDK, `url.full` | [05](screenshots/05-attributs-de-ressource-et-de-lagent.png) |
| 6 | Filtre Erreur | Lister les spans en erreur | [06](screenshots/06-ne-garder-que-les-erreurs.png) |
| 7 | Trace en erreur | Trouver le span où naît l'erreur et la voir remonter | [07](screenshots/07-trace-en-erreur.png) |
| 8 | Logs du span | Corrélation log ↔ trace (`trace`, `spanId`) | [08](screenshots/08-logs-rattaches-au-span.png) |
| 9 | Affichage groupé | P50 / P90 / P95 et taux d'erreur par opération | [09](screenshots/09-affichage-groupe.png) |
| 10 | Cloud Logging | Requête LQL sur les logs OTLP | [10](screenshots/10-cloud-logging.png) |
| 11 | Log déplié | Champs de corrélation, logs de la même trace regroupés | [11](screenshots/11-un-log-deplie.png) |
| 12 | Metrics Explorer | PromQL p95 sur les métriques de l'agent (Managed Prometheus) | [12](screenshots/12-metriques.png) |

## Comment lire les chiffres

- **P50 ≈ 145 ms mais P95 ≈ 3 s.** La médiane cache la queue de latence : les ~10 % de requêtes `slow` font exploser le P95. Les SLO de latence se définissent sur des percentiles.
- **Taux d'erreur client > serveur sur le même appel** (GET order → inventory). L'écart vient des 404 (`item=xyz`). Selon les conventions sémantiques OTel, un 4xx est une erreur pour l'appelant, pas pour le serveur.
- **Comptes décroissants POST /orders → POST /inventory/ → persistOrder.** On lit un entonnoir de commandes (reçues → stock réservé → persistées) directement dans les traces.

## Requêtes utiles

```text
# Cloud Logging (LQL) : logs applicatifs en warning/erreur
logName="projects/PROJECT_ID/logs/otel-lab" severity>=WARNING
```

```promql
# Metrics Explorer (PromQL) : latence p95 par service
histogram_quantile(0.95, sum by (le, job) (rate(http_server_request_duration_seconds_bucket[5m])))

# Débit par code HTTP
sum by (job, http_response_status_code) (rate(http_server_request_duration_seconds_count[5m]))
```

## Note : erreurs 409

Après un moment, des `409 Checkout failed` apparaissent : le loadgen a épuisé le stock en mémoire de certains items (50 laptops au départ). C'est le comportement normal de l'application (`out of stock`). Pour remettre le stock à zéro :

```bash
kubectl -n shop rollout restart deploy/inventory-service
```
