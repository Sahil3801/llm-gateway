-- Sliding-window rate limiter (sliding window LOG), executed atomically inside Redis.
--
-- Data: one sorted set per client. Each member = one accepted request, score = its time in ms.
--
-- Why a Lua script: the steps below (clean up, count, add) must happen as ONE operation.
-- Done as separate commands from Java, two parallel requests could both read "19 of 20 used"
-- and both get through. Redis runs a script without interleaving other commands, so that race
-- is impossible, even with several gateway instances sharing one Redis.
--
-- KEYS[1] = sorted-set key for this client
-- ARGV[1] = max requests allowed in the window
-- ARGV[2] = window length in milliseconds
-- ARGV[3] = unique member id for this request (so two requests in the same ms don't collide)
--
-- Returns {allowed (1|0), remaining, retry_after_ms}

local key = KEYS[1]
local limit = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local member = ARGV[3]

-- Use Redis's clock, not each gateway's: with several gateway instances, their clocks can
-- differ slightly, but they all share this one Redis.
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)

-- 1. Forget requests that have slid out of the window.
redis.call('ZREMRANGEBYSCORE', key, '-inf', now - window)

-- 2. How many requests are still inside the window?
local count = redis.call('ZCARD', key)

if count < limit then
  -- 3a. Room left: record this request and accept it.
  redis.call('ZADD', key, now, member)
  -- The whole key disappears once the client has been quiet for a full window.
  redis.call('PEXPIRE', key, window)
  return {1, limit - count - 1, 0}
end

-- 3b. Full: reject. Rejected requests are NOT recorded, so a client that keeps hammering
-- doesn't push its own unblock time further out. The client can retry when the oldest
-- request in the window expires.
local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
local retry_after = tonumber(oldest[2]) + window - now
if retry_after < 1 then retry_after = 1 end
return {0, 0, retry_after}
