# Infra — local docker-compose

Runs the single-instance topology: **gateway → Redis** (atomic Lua limiting),
**gateway → demo-backend** (proxied `/api/**`), and the **observability stack**
(Prometheus scrapes the gateway, Grafana visualizes it).

```
client ──▶ gateway:8080 ──(atomic Lua)──▶ redis:6379
                 ├────────(proxy /api/**)──▶ demo-backend:9000
                 └────────(/actuator/prometheus) ◀── prometheus:9090 ──▶ grafana:3000
```

## Prerequisites
- Docker (Desktop) running
- JDK 17 + Maven (to build the app jars)

## 1. Build the application jars

```bash
mvn -DskipTests package
```

## 2. Build images + start the stack

The app images are built from each module's `Dockerfile` (slim JRE + the repackaged jar)
via the docker CLI:

```bash
docker compose -f infra/docker-compose.yml up -d --build
```

This produces and runs:
- `rate-limiter/gateway-service:0.1.0-SNAPSHOT`
- `rate-limiter/demo-backend:0.1.0-SNAPSHOT`
- `redis:7-alpine`
- `prom/prometheus` (scrapes the gateway every 5s)
- `grafana/grafana` (auto-provisioned datasource + dashboard)

The gateway waits for Redis to be healthy before starting. Stop with:

```bash
docker compose -f infra/docker-compose.yml down
```

## 3. Demo — watch 200s turn into 429

Rules are resolved per request by **tier** (from the API key) and **route**. On `demo-api`
the default-shipped config gives free callers **Fixed Window, 5 / 1s**; the `premium-demo-key`
maps to the premium tier (**Token Bucket, 200 / 1s**). Hammer the endpoint with an unmapped
(free) key and it drains:

```bash
for i in $(seq 1 10); do
  curl -s -o /dev/null -w "%{http_code} " -H "X-API-Key: demo" http://localhost:8080/api/echo
done; echo
# e.g. 200 200 200 200 200 429 429 429 429 429

# the premium key sails past the free limit:
for i in $(seq 1 10); do
  curl -s -o /dev/null -w "%{http_code} " -H "X-API-Key: premium-demo-key" http://localhost:8080/api/echo
done; echo
# 200 200 200 200 200 200 200 200 200 200
```

Inspect the rate-limit headers on a single call:

```bash
curl -i -H "X-API-Key: demo" http://localhost:8080/api/echo
# X-RateLimit-Limit: 5
# X-RateLimit-Remaining: 4
# X-RateLimit-Reset: 1
# ...proxied JSON body from demo-backend
```

When blocked you get **HTTP 429** with `Retry-After`, `X-RateLimit-Remaining: 0`, and a
JSON body `{"error":"rate_limit_exceeded","retryAfterSeconds":N}`.

Different API keys (or client IPs) have independent buckets — key resolution is
`X-API-Key` → client IP, keyed in Redis as `rl:{tier}:{algo}:{route}:{identity}`.

## 4. Observability — Prometheus + Grafana

Every decision increments `ratelimit_requests_total{outcome,tier,route,algorithm}`. Generate
some traffic, then open the pre-provisioned dashboard:

```bash
# mix allowed + blocked traffic across both tiers
for i in $(seq 1 60); do
  curl -s -o /dev/null -H "X-API-Key: demo"             http://localhost:8080/api/echo
  curl -s -o /dev/null -H "X-API-Key: premium-demo-key" http://localhost:8080/api/echo
done
```

- **Grafana** → http://localhost:3000 (anonymous access on; dashboard *Rate Limiter — Gateway*)
- **Prometheus** → http://localhost:9090 (try the query `sum by (outcome) (rate(ratelimit_requests_total[1m]))`)

## Endpoints
| URL | What |
|---|---|
| `http://localhost:8080/api/echo` | proxied echo (rate limited) |
| `http://localhost:8080/api/work?delayMs=20` | proxied simulated work (rate limited) |
| `http://localhost:8080/actuator/health` | gateway health |
| `http://localhost:8080/actuator/prometheus` | gateway metrics (scraped by Prometheus) |
| `http://localhost:9090` | Prometheus UI |
| `http://localhost:3000` | Grafana dashboards |

## Configuration
Limiting is fully config-driven (no rebuild): `ratelimit.*` defines `tiers` (algorithm + limit +
window), an `api-keys` → tier map, and per-`routes` overrides. Override via env or edit
`gateway-service/src/main/resources/application.yml`.
