// M6 — k6 throughput / latency benchmark for the rate-limiter gateway.
//
// Goal: measure the gateway + atomic-Lua limiter overhead under load, NOT 429 rejection.
// It drives traffic through the `bench` tier (X-API-Key: bench-key), whose limit is high
// enough that every request passes the limiter — so http_req_duration reflects the real cost
// of proxying + the Redis check-and-decrement, and http_req_failed should stay at ~0.
//
// Run standalone (needs k6 + a gateway on localhost:8080):
//   k6 run infra/k6/throughput.js
// Run fully containerised against the compose stack (no host install):
//   docker compose --profile bench up k6           (from infra/)
//
// Tunables via env:  BASE_URL, API_KEY, VUS, DURATION
import http from "k6/http";
import { check } from "k6";
import { Rate, Trend } from "k6/metrics";

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const API_KEY = __ENV.API_KEY || "bench-key";

// Custom metrics: throughput is k6's built-in `http_reqs` rate; we track the share of
// non-200s separately so an accidental 429 (limiter misconfig) fails the run loudly.
const non200 = new Rate("non_200_responses");
const gatewayLatency = new Trend("gateway_latency_ms", true);

export const options = {
  scenarios: {
    // Ramping VUs: warm up, sustain a steady load, then ramp down. The sustained plateau
    // is where the p95/p99 thresholds below are judged.
    throughput: {
      executor: "ramping-vus",
      startVUs: 0,
      stages: [
        { duration: "15s", target: Number(__ENV.VUS) || 50 }, // ramp up
        { duration: __ENV.DURATION || "60s", target: Number(__ENV.VUS) || 50 }, // sustain
        { duration: "10s", target: 0 }, // ramp down
      ],
      gracefulStop: "5s",
    },
  },
  thresholds: {
    // The benchmark FAILS (non-zero exit) if these are breached — makes it CI-gradeable.
    http_req_failed: ["rate<0.01"], // <1% transport/HTTP errors
    non_200_responses: ["rate<0.01"], // <1% non-200 (i.e. no unexpected 429s)
    http_req_duration: ["p(95)<50", "p(99)<150"], // limiter overhead budget
  },
};

export default function () {
  const res = http.get(`${BASE_URL}/api/echo`, {
    headers: { "X-API-Key": API_KEY },
    tags: { name: "echo" },
  });

  non200.add(res.status !== 200);
  gatewayLatency.add(res.timings.duration);

  check(res, {
    "status is 200": (r) => r.status === 200,
    "not rate-limited": (r) => r.status !== 429,
    // k6 canonicalises header keys, so X-RateLimit-Remaining -> X-Ratelimit-Remaining.
    "has X-RateLimit-Remaining header": (r) =>
      r.headers["X-Ratelimit-Remaining"] !== undefined,
  });
}
