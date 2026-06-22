# k6 — load benchmark (M6)

`throughput.js` measures the **gateway + atomic-Lua limiter overhead** under sustained load —
request throughput and latency quantiles — rather than 429-rejection behaviour.

It drives traffic through the dedicated **`bench` tier** (`X-API-Key: bench-key`), a Token Bucket
with an effectively-unlimited limit (`1_000_000 / 1s`). Every request still runs the full Redis
check-and-decrement, but none are blocked, so `http_req_duration` reflects the real cost of
proxying + limiting and `http_req_failed` / non-200s should stay at ~0.

## Run it

Containerised against the compose stack (no host install) — from `infra/`:

```bash
docker compose --profile bench up k6
```

Standalone (needs [k6](https://k6.io/docs/get-started/installation/) + a gateway on `:8080`):

```bash
k6 run infra/k6/throughput.js
```

## Tunables (env)

| Var | Default | Meaning |
|---|---|---|
| `BASE_URL` | `http://localhost:8080` | gateway base URL (compose sets `http://gateway:8080`) |
| `API_KEY` | `bench-key` | API key → tier (keep `bench-key` to avoid hitting limits) |
| `VUS` | `50` | peak virtual users (concurrency) |
| `DURATION` | `60s` | length of the sustained plateau (ramps are fixed: 15s up / 10s down) |

```bash
VUS=100 DURATION=2m k6 run infra/k6/throughput.js
```

## Pass/fail thresholds

The run exits non-zero (CI-gradeable) if any of these are breached:

| Threshold | Meaning |
|---|---|
| `http_req_failed < 1%` | transport/HTTP errors |
| `non_200_responses < 1%` | no unexpected 429s (limiter misconfig guard) |
| `http_req_duration p95 < 50ms`, `p99 < 150ms` | limiter overhead budget |

Reported metrics of interest: `http_reqs` (throughput, req/s), `http_req_duration`
(avg / p95 / p99), `iterations`, plus the custom `gateway_latency_ms` trend.

> Numbers are machine-dependent (especially on Docker Desktop for Windows/Mac, where the Linux
> VM adds overhead). Treat them as relative, not absolute — the point is the limiter adds little
> on top of the proxy, and throughput scales with VUs until a resource is saturated.

## Sample run

50 VUs, ~85s, full stack on Docker Desktop (Windows/WSL2). All thresholds passed:

```
http_reqs..............: 685715  8067.48/s        # throughput
http_req_failed........: 0.00%   ✓ 0  ✗ 685715    # zero errors
non_200_responses......: 0.00%   ✓ 0  ✗ 685715    # zero unexpected 429s
http_req_duration......: avg=5.15ms med=4.81ms p(95)=9.47ms max=44.32ms
checks.................: 100.00% ✓ 2057145 ✗ 0
```

~686k requests, each through the full atomic Lua check-and-decrement, **0 blocked** — so this is
the gateway + limiter overhead, not 429 rejection. p95 ≈ 9.5 ms against a 50 ms budget.
