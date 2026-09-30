-- Admit the next users at a global rate, however many Admission instances call this.
-- A token bucket lives next to the queue, in the same hash slot; popping from the
-- queue and recording the admission happen in one script, so a crash can't lose anyone.
-- KEYS: 1 queue zset, 2 admitted zset, 3 bucket hash.
-- ARGV: 1 rate per second, 2 burst, 3 admission lifetime ms.
-- Returns the number admitted.
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
local rate, burst = tonumber(ARGV[1]), tonumber(ARGV[2])

-- Admissions older than their lifetime are over; the user may queue again.
redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', now - tonumber(ARGV[3]))

local b = redis.call('HMGET', KEYS[3], 'tokens', 'at')
local tokens = tonumber(b[1]) or burst
local at = tonumber(b[2]) or now
tokens = math.min(burst, tokens + (now - at) * rate / 1000)

local n = math.floor(tokens)
local admitted = 0
if n > 0 then
  local popped = redis.call('ZPOPMIN', KEYS[1], n)
  for i = 1, #popped, 2 do
    redis.call('ZADD', KEYS[2], now, popped[i])
    admitted = admitted + 1
  end
end
redis.call('HSET', KEYS[3], 'tokens', tokens - admitted, 'at', now)
return admitted
