-- Fixed Window rate limiter (atomic).
--
-- State is a Redis hash per key: { count = <int>, start = <aligned window start, epoch ms> }.
-- Windows are aligned to multiples of windowMs, so every caller agrees on the same boundaries.
-- The whole read + roll + increment runs in one Lua call, so Redis's single-threaded execution
-- makes it race-free across all gateway instances. Time is passed in (ARGV[3]), never read from
-- Redis, so behaviour is deterministic and testable.
--
-- KEYS[1] = counter key
-- ARGV[1] = limit      (max requests per window)
-- ARGV[2] = windowMs   (window length in ms)
-- ARGV[3] = nowMs      (current time, epoch ms)
-- ARGV[4] = ttlMs      (key expiry so idle counters are reclaimed)
--
-- returns { allowed(0|1), remaining, retryAfterMs, resetAfterMs }
--
-- Note the deliberate flaw this documents: up to `limit` requests may land just before a boundary
-- and another `limit` just after it, admitting 2*limit in a tiny span. Sliding-window algorithms fix this.

local key      = KEYS[1]
local limit    = tonumber(ARGV[1])
local windowMs = tonumber(ARGV[2])
local now      = tonumber(ARGV[3])
local ttlMs    = tonumber(ARGV[4])

local windowStart = math.floor(now / windowMs) * windowMs
local windowEnd   = windowStart + windowMs

local state = redis.call('HMGET', key, 'count', 'start')
local count = tonumber(state[1])
local start = tonumber(state[2])

-- fresh key, or the stored window has rolled over -> start a new window
if count == nil or start ~= windowStart then
  count = 0
  start = windowStart
end

local allowed = 0
if count < limit then
  allowed = 1
  count = count + 1
end

redis.call('HSET', key, 'count', count, 'start', start)
redis.call('PEXPIRE', key, ttlMs)

local remaining = limit - count
if remaining < 0 then remaining = 0 end

-- the window resets (and a blocked caller may retry) when the current window ends
local resetAfterMs = windowEnd - now
local retryAfterMs = 0
if allowed == 0 then
  retryAfterMs = resetAfterMs
end

return { allowed, remaining, retryAfterMs, resetAfterMs }
