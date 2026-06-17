-- Sliding Window Log rate limiter (atomic).
--
-- State is a Redis sorted set per key: each admitted request is one member scored by its
-- timestamp (epoch ms). The window is the exact trailing interval (now - windowMs, now], so this is
-- the most accurate algorithm — no boundary burst — at the cost of storing one entry per request.
-- The whole evict + count + add runs in one Lua call, so Redis's single-threaded execution makes it
-- race-free across all gateway instances. Time is passed in (ARGV[3]), never read from Redis.
--
-- KEYS[1] = sorted-set key
-- ARGV[1] = limit      (max requests per window)
-- ARGV[2] = windowMs   (window length in ms)
-- ARGV[3] = nowMs      (current time, epoch ms)
-- ARGV[4] = ttlMs      (key expiry so idle logs are reclaimed)
-- ARGV[5] = member     (unique id for this request, so equal-timestamp requests don't collapse)
--
-- returns { allowed(0|1), remaining, retryAfterMs, resetAfterMs }

local key      = KEYS[1]
local limit    = tonumber(ARGV[1])
local windowMs = tonumber(ARGV[2])
local now      = tonumber(ARGV[3])
local ttlMs    = tonumber(ARGV[4])
local member   = ARGV[5]

local windowStart = now - windowMs

-- drop entries that have aged out of the trailing window
redis.call('ZREMRANGEBYSCORE', key, 0, windowStart)

local count = redis.call('ZCARD', key)

local allowed = 0
if count < limit then
  allowed = 1
  redis.call('ZADD', key, now, member)
  count = count + 1
end

redis.call('PEXPIRE', key, ttlMs)

local remaining = limit - count
if remaining < 0 then remaining = 0 end

-- a slot frees up when the oldest in-window entry ages out
local resetAfterMs = 0
local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
if oldest[2] ~= nil then
  resetAfterMs = (tonumber(oldest[2]) + windowMs) - now
  if resetAfterMs < 0 then resetAfterMs = 0 end
end

local retryAfterMs = 0
if allowed == 0 then
  retryAfterMs = resetAfterMs
end

return { allowed, remaining, retryAfterMs, resetAfterMs }
