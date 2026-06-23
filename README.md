# Distributed Rate Limiter + API Gateway

[![CI](https://github.com/AryamannSingh7/distributed-rate-limiter-gateway/actions/workflows/ci.yml/badge.svg)](https://github.com/AryamannSingh7/distributed-rate-limiter-gateway/actions/workflows/ci.yml)
[![Java 17](https://img.shields.io/badge/Java-17-orange.svg)](https://adoptium.net/)
[![Spring Boot 3.3](https://img.shields.io/badge/Spring%20Boot-3.3.5-6db33f.svg)](https://spring.io/projects/spring-boot)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

A production-grade, **distributed** rate limiting service built on **Spring Cloud Gateway** and
**Redis**. Rate limits hold *correctly and atomically* across N gateway instances sharing one
Redis — enforced via server-side **Lua scripts** so there are no race conditions or double-counting.

## Why this project

Naive rate limiting (read counter → decide → write) breaks the moment you run more than one
instance: two requests can read the same count and both be allowed. This project does it the right
way — each decision is a single atomic Redis operation — and proves it with a distributed
correctness test plus reproducible load-test numbers.

## Highlights

- **4 algorithms**, switchable via config (no code change): Token Bucket, Sliding Window Log,
  Sliding Window Counter, and Fixed Window (baseline).
- **Atomic across instances** via Redis Lua (`EVALSHA`) — Redis runs Lua single-threaded, so
  check-and-decrement is race-free.
- **Gateway behaviour**: limits per **API key / client IP / route**, with **tiers** (e.g. free vs premium).
- **Standard 429 responses**: `Retry-After`, `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset`.
- **Observability**: Micrometer → Prometheus → Grafana dashboard.
- **Tested**: unit (per algorithm), concurrency (atomicity), and distributed correctness (Testcontainers).
- **Benchmarked**: reproducible k6 load test — ~8k req/s through the full atomic path, p95 < 10 ms.

## Architecture

```
[client / k6] ──▶ Gateway (Spring Cloud Gateway, WebFlux)
                      │  RateLimit GlobalFilter ── atomic Lua ──▶ Redis (shared state)
                      ▼
                  Demo backend (/api/echo, /api/work)

   Observability: gateway /actuator/prometheus ──▶ Prometheus ──▶ Grafana
```

## Modules

| Module | Purpose |
|---|---|
| `rate-limiter-core` | Algorithms, Lua scripts, abstractions. Web-free, reusable. |
| `gateway-service` | Spring Cloud Gateway + rate limiting `GlobalFilter`. |
| `demo-backend` | Tiny WebFlux service the gateway protects. |

## Build & run

Requires Java 17, Maven, Docker.

```bash
# Build everything + run tests (Testcontainers starts Redis automatically)
mvn verify

# Bring up the full stack (gateway, Redis, demo backend, Prometheus, Grafana)
docker compose -f infra/docker-compose.yml up
```

Once the stack is up:

| Service | URL |
|---|---|
| Gateway | http://localhost:8080 |
| Demo backend (echo) | http://localhost:8080/api/echo |
| Prometheus | http://localhost:9090 |
| Grafana (anonymous admin) | http://localhost:3000 |

```bash
# Any unknown/absent API key is the "free" tier. On the demo route (/api/**) free callers hit
# a stricter per-route override (Fixed Window, 5/s) — burst past it to see 429s.
for i in $(seq 1 12); do
  curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/api/echo
done

# premium-demo-key → premium tier (Token Bucket, 200/s) — sails through the same burst.
```

A 429 response carries `Retry-After`, `X-RateLimit-Limit`, `X-RateLimit-Remaining`, and
`X-RateLimit-Reset`. Both `Retry-After` and `X-RateLimit-Reset` are expressed as **seconds from now**
(a delta, not a Unix epoch timestamp). Tiers, algorithms, limits, and per-route overrides are all
defined in [`application.yml`](gateway-service/src/main/resources/application.yml) — changing them
needs no rebuild.

> **Local note (Windows / Docker Desktop):** if Testcontainers can't reach the Docker daemon, start
> a Redis container yourself and run the tests against it:
> `docker run -d -p 6379:6379 redis:7-alpine` then `REDIS_HOST=localhost mvn verify`.

## Continuous integration

[GitHub Actions](.github/workflows/ci.yml) runs `mvn verify` on every push and PR to `main`
(Ubuntu, Temurin JDK 17, cached Maven). On Linux runners Docker is available, so Testcontainers
spins up Redis itself — the full suite, including the **distributed correctness** and
**concurrency/atomicity** tests, runs in CI exactly as it does locally.

## Identity & trust model

How a request is mapped to a bucket, and what this service trusts:

- **Bucket identity** is a *recognized* `X-API-Key` (one listed in `ratelimit.api-keys`), otherwise the
  client IP. An unknown or forged key does **not** mint its own bucket — it falls back to the IP bucket
  — so a caller can't evade limits by rotating a random key on every request.
- **API keys are trust-on-assertion.** The key → tier mapping assumes keys are authenticated upstream;
  this service does not verify a secret, so anyone presenting a known key gets that tier. Put real
  authentication in front of the gateway for production use.
- **Client IP** comes from the socket address by default. Behind a proxy/LB, set
  `ratelimit.trust-forwarded-for: true` to use the leftmost `X-Forwarded-For` entry instead — opt-in
  because that header is client-spoofable and is only trustworthy when a known proxy overwrites it.

## Roadmap

- [x] **P1** — Maven multi-module skeleton
- [x] **P2** — Core abstractions + Token Bucket Lua, proven atomic
- [x] **P3** — End-to-end gateway (filter + demo backend, 429 + headers)
- [x] **P4** — All 4 algorithms + config-driven switching + tiers
- [x] **P5** — Observability (Micrometer + Prometheus + Grafana)
- [x] **P6** — Distributed correctness test (Testcontainers, 2 instances)
- [x] **P7** — k6 load tests + benchmark numbers
- [x] **P8** — CI + README polish

## Distributed correctness

The headline claim — *limits hold exactly across instances* — is proven, not asserted.
`DistributedRateLimitTest` boots **two independent gateway application contexts** in one JVM on
random ports, both sharing **one Redis** and one backend. It then fires **200 concurrent clients**
split evenly across the two instances at a single API key whose limit is **50**. The test asserts:

- `allowed == 50` globally (e.g. instance A served 22, instance B served 28 — never 50 + 50),
- `allowed + blocked == 200` (every request accounted for),
- zero fail-open errors.

This is only possible because the check-and-decrement is a single atomic Lua script — Redis runs Lua
single-threaded, so two instances can never both read a stale count and over-admit.

## Benchmarks

Load tested with [k6](infra/k6) against the full gateway → Lua → Redis path. A dedicated `bench`
tier (Token Bucket, 1,000,000/s) ensures nothing is throttled, so the run measures **gateway +
limiter overhead**, not 429 rejection.

Sample run — 50 VUs for 85s, local Docker Desktop / WSL2:

| Metric | Result |
|---|---|
| Throughput | **~8,070 req/s** (685,715 requests) |
| Failed requests | **0** |
| Non-200 responses | **0** |
| Latency p95 | **9.47 ms** |
| Latency max | **44 ms** |
| Checks passed | **100%** |

Reproduce: `docker compose -f infra/docker-compose.yml --profile bench up k6` (see
[`infra/k6/README.md`](infra/k6/README.md) for tunables).

## Algorithms (summary)

| Algorithm | Accuracy | Memory | Burst handling | Notes |
|---|---|---|---|---|
| Token Bucket | High | Low | Allows controlled bursts | Smooth, refill-based |
| Sliding Window Log | Highest | Higher | Exact | Stores every timestamp |
| Sliding Window Counter | High | Low | Good | Weighted prev+curr window — the practical default |
| Fixed Window | Low | Lowest | Burst at boundary | Baseline for comparison |

Each algorithm is implemented as a Redis Lua script on a shared `AbstractLuaRateLimiter` base, unit
tested with a deterministic clock, and selectable purely via config.

## License

[MIT](LICENSE)
