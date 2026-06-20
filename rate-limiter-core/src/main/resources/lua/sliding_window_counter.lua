-- Sliding Window Counter rate limiter (atomic).
--
-- Approximates a true sliding window with just two fixed-window counters: the current window's count
-- plus a time-weighted fraction of the previous window's count. As the clock advances through the
-- current window the previous window's weight decays linearly from 1 to 0, so the estimate slides
-- smoothly instead of stepping down at the boundary (the Fixed Window burst flaw). State is O(1) per
-- key (one small hash) — far cheaper than Sliding Window Log's per-request entries — which is why this
-- is the usual production default. The whole read + roll + estimate + increment runs in one Lua call,
-- so Redis's single-threaded execution makes it race-free across all gateway instances. Time is passed
-- in (ARGV[3]), never read from Redis, so behaviour is deterministic and testable.
--
--   estimated = cur + prev * (1 - elapsedInCurrentWindow / windowMs)
--
-- KEYS[1] = state key (hash: cwStart, cur, prev)
-- ARGV[1] = limit      (max requests per window)
-- ARGV[2] = windowMs   (window length in ms)
-- ARGV[3] = nowMs      (current time, epoch ms)
-- ARGV[4] = ttlMs      (key expiry so idle counters are reclaimed)
--
-- returns { allowed(0|1), remaining, retryAfterMs, resetAfterMs }

local key      = KEYS[1]
local limit    = tonumber(ARGV[1])
local windowMs = tonumber(ARGV[2])
local now      = tonumber(ARGV[3])
local ttlMs    = tonumber(ARGV[4])

local cwStart    = math.floor(now / windowMs) * windowMs
local windowEnd  = cwStart + windowMs
local elapsed    = now - cwStart

local state       = redis.call('HMGET', key, 'cwStart', 'cur', 'prev')
local storedStart = tonumber(state[1])
local cur         = tonumber(state[2])
local prev        = tonumber(state[3])

if storedStart == nil then
  -- fresh key
  cur = 0
  prev = 0
elseif storedStart == cwStart then
  -- still the same window: keep cur/prev as stored
elseif storedStart == cwStart - windowMs then
  -- advanced exactly one window: last window's count becomes the decaying "previous"
  prev = cur
  cur = 0
else
  -- gap of two or more windows: nothing carries over
  prev = 0
  cur = 0
end

local weight    = (windowMs - elapsed) / windowMs
local estimated = cur + prev * weight

local allowed = 0
if estimated < limit then
  allowed = 1
  cur = cur + 1
  estimated = estimated + 1
end

redis.call('HSET', key, 'cwStart', cwStart, 'cur', cur, 'prev', prev)
redis.call('PEXPIRE', key, ttlMs)

local remaining = math.floor(limit - estimated)
if remaining < 0 then remaining = 0 end

-- when the current window ends, this window's count becomes the decaying "previous"
local resetAfterMs = windowEnd - now

local retryAfterMs = 0
if allowed == 0 then
  if cur < limit and prev > 0 then
    -- the current window alone is under the limit; a slot frees as the previous window's weight
    -- decays. Solve estimated(elapsed') = limit for the target elapsed within this window.
    local targetElapsed = windowMs * (1 - (limit - cur) / prev)
    retryAfterMs = math.ceil(targetElapsed - elapsed)
    if retryAfterMs < 0 then retryAfterMs = 0 end
  else
    -- the current window itself is at/over the limit: nothing frees until the boundary
    retryAfterMs = resetAfterMs
  end
end

return { allowed, remaining, retryAfterMs, resetAfterMs }
