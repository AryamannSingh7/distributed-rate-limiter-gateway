-- Token Bucket rate limiter (atomic).
--
-- State is a Redis hash per key: { tokens = <float>, ts = <last refill epoch ms> }.
-- The whole refill + check + decrement runs in one Lua call, so Redis's single-threaded
-- execution makes it race-free across all gateway instances. Time is passed in (ARGV[4]),
-- never read from Redis, so behaviour is deterministic and testable.
--
-- KEYS[1] = bucket key
-- ARGV[1] = capacity        (max tokens / max burst)
-- ARGV[2] = refillTokens    (tokens added per refill period; == capacity for "limit per window")
-- ARGV[3] = refillPeriodMs  (length of the refill period in ms)
-- ARGV[4] = nowMs           (current time, epoch ms)
-- ARGV[5] = requested       (tokens this request costs, normally 1)
-- ARGV[6] = ttlMs           (key expiry so idle buckets are reclaimed)
--
-- returns { allowed(0|1), remaining, retryAfterMs, resetAfterMs }

local key            = KEYS[1]
local capacity       = tonumber(ARGV[1])
local refillTokens   = tonumber(ARGV[2])
local refillPeriodMs = tonumber(ARGV[3])
local now            = tonumber(ARGV[4])
local requested      = tonumber(ARGV[5])
local ttlMs          = tonumber(ARGV[6])

local refillRate = refillTokens / refillPeriodMs  -- tokens per ms

local state  = redis.call('HMGET', key, 'tokens', 'ts')
local tokens = tonumber(state[1])
local ts     = tonumber(state[2])

if tokens == nil then
  -- fresh bucket starts full
  tokens = capacity
  ts = now
end

-- refill based on elapsed time since last update
local elapsed = now - ts
if elapsed < 0 then elapsed = 0 end
tokens = math.min(capacity, tokens + (elapsed * refillRate))
ts = now

local allowed = 0
if tokens >= requested then
  allowed = 1
  tokens = tokens - requested
end

redis.call('HSET', key, 'tokens', tokens, 'ts', ts)
redis.call('PEXPIRE', key, ttlMs)

local remaining = math.floor(tokens)

-- ms until enough tokens exist to satisfy this request again (0 when allowed)
local retryAfterMs = 0
if allowed == 0 then
  local deficit = requested - tokens
  retryAfterMs = math.ceil(deficit / refillRate)
end

-- ms until the bucket is full again
local resetAfterMs = math.ceil((capacity - tokens) / refillRate)

return { allowed, remaining, retryAfterMs, resetAfterMs }
