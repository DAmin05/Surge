-- Hold every seat for one order, or none. ARGV[3] = lease in ms.
-- Returns {'OK', epoch, exp, seq...} | {'REBUILDING'} | {'SOLD', seat} | {'TAKEN', seat} | {'EXISTS'}
local epoch = redis.call('GET', KEYS[1])
if not epoch then return {'REBUILDING'} end
if redis.call('EXISTS', KEYS[6]) == 1 then return {'EXISTS'} end

local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
local holdId, userId = ARGV[1], ARGV[2]
local exp = now + tonumber(ARGV[3])
local n = #KEYS - 6

for i = 1, n do
  local seat = ARGV[3 + i]
  if redis.call('SISMEMBER', KEYS[4], seat) == 1 then return {'SOLD', seat} end
  local cur = redis.call('HGET', KEYS[6 + i], 'exp')
  -- A hold whose lease lapsed but wasn't swept yet no longer counts.
  if cur and tonumber(cur) > now then return {'TAKEN', seat} end
end

local seats = {}
local out = {'OK', epoch, exp}
for i = 1, n do
  local seat = ARGV[3 + i]
  seats[i] = seat
  redis.call('HSET', KEYS[6 + i], 'hold', holdId, 'user', userId, 'exp', exp)
  redis.call('SADD', KEYS[5], seat)
  out[#out + 1] = redis.call('INCR', KEYS[2])
end
redis.call('HSET', KEYS[6], 'user', userId, 'seats', table.concat(seats, ','), 'exp', exp)
redis.call('ZADD', KEYS[3], exp, holdId)
return out
