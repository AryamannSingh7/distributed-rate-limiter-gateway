# Distributed Rate Limiter + API Gateway

A production-grade, **distributed** rate limiting service built on **Spring Cloud Gateway** and
**Redis**. Rate limits hold *correctly and atomically* across N gateway instances sharing one
Redis — enforced via server-side **Lua scripts** so there are no race conditions or double-counting.

> Status: **work in progress** — built milestone by milestone. See the roadmap below.

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
- **Benchmarked**: reproducible k6 load tests with an algorithm comparison.

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
# Build everything + run tests
mvn verify

# Bring up the full stack (gateway, Redis, demo backend, Prometheus, Grafana)
docker compose -f infra/docker-compose.yml up
```

## Roadmap

- [x] **M0** — Maven multi-module skeleton
- [ ] **M1** — Core abstractions + Token Bucket Lua, proven atomic
- [ ] **M2** — End-to-end gateway (filter + demo backend, 429 + headers)
- [ ] **M3** — All 4 algorithms + config-driven switching + tiers
- [ ] **M4** — Observability (Micrometer + Prometheus + Grafana)
- [ ] **M5** — Distributed correctness test (Testcontainers, 2 instances)
- [ ] **M6** — k6 load tests + benchmark numbers
- [ ] **M7** — CI + README polish

## Algorithms (summary)

| Algorithm | Accuracy | Memory | Burst handling | Notes |
|---|---|---|---|---|
| Token Bucket | High | Low | Allows controlled bursts | Smooth, refill-based |
| Sliding Window Log | Highest | Higher | Exact | Stores every timestamp |
| Sliding Window Counter | High | Low | Good | Weighted prev+curr window — the practical default |
| Fixed Window | Low | Lowest | Burst at boundary | Baseline for comparison |

_Detailed explanations, tradeoffs, and benchmark results are added as milestones land._
