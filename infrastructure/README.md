# infrastructure/

| Path | Contents | Phase |
|---|---|---|
| `docker/otel/collector-config.yaml` | OpenTelemetry Collector pipeline | 0 (extended in 8) |
| `docker/` | Service Dockerfiles | 2, 9, 12 |
| `compose/` | `docker-compose.prod.yml` and overrides | 12 |
| `k8s/` | Kubernetes manifests, verified on `kind` | 13 |

The root `docker-compose.yml` is the development stack and the supported deployment target
(ADR-010). Kubernetes is a deployment artefact, not a hosted environment.
