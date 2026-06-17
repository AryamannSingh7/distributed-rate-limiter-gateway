# Infra — local docker-compose

Runs the single-instance topology: **gateway → Redis** (atomic Lua limiting) and
**gateway → demo-backend** (proxied `/api/**`).

```
client ──▶ gateway:8080 ──(atomic Lua)──▶ redis:6379
                 └────────(proxy /api/**)──▶ demo-backend:9000
```

> Prometheus + Grafana join this stack in M4.

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

The gateway waits for Redis to be healthy before starting. Stop with:

```bash
docker compose -f infra/docker-compose.yml down
```

## 3. Demo — watch 200s turn into 429

The default rule (`application.yml`) is **Token Bucket, 5 requests / 1s**. Hammer the
endpoint with one API key and the bucket drains:

```bash
for i in $(seq 1 10); do
  curl -s -o /dev/null -w "%{http_code} " -H "X-API-Key: demo" http://localhost:8080/api/echo
done; echo
# e.g. 200 200 200 200 200 429 429 429 429 429
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
`X-API-Key` → client IP, keyed in Redis as `rl:{algo}:{route}:{identity}`.

## Endpoints
| URL | What |
|---|---|
| `http://localhost:8080/api/echo` | proxied echo (rate limited) |
| `http://localhost:8080/api/work?delayMs=20` | proxied simulated work (rate limited) |
| `http://localhost:8080/actuator/health` | gateway health |
| `http://localhost:8080/actuator/prometheus` | gateway metrics (used in M4) |

## Configuration
Tune limiting without rebuilding by overriding env / `ratelimit.*` properties on the
gateway service (algorithm, limit, window, fail-open). See `gateway-service/src/main/resources/application.yml`.
