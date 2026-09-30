-- Validate a hold and extend its lease before the Postgres claim. All or nothing.
-- ARGV[3] = extension in ms (T + grace). Never shortens a lease.
-- Returns {'OK', epoch, exp, seq...} | {'REBUILDING'} | {'NOT_FOUND'} | {'WRONG_OWNER'} | {'CHANGED'}
local epoch = redis.call('GET', KEYS[1])
if not epoch then return {'REBUILDING'} end

local hold = redis.call('HMGET', KEYS[6], 'user', 'seats', 'exp')
if not hold[1] then return {'NOT_FOUND'} end
if hold[1] ~= ARGV[2] then return {'WRONG_OWNER'} end

local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
-- A lapsed lease can't be revived: its seats may already belong to someone else.
if tonumber(hold[3]) <= now then return {'NOT_FOUND'} end

local n = #KEYS - 6
local seats = {}
for i = 1, n do seats[i] = ARGV[3 + i] end
if hold[2] ~= table.concat(seats, ',') then return {'CHANGED'} end
for i = 1, n do
  if redis.call('HGET', KEYS[6 + i], 'hold') ~= ARGV[1] then return {'NOT_FOUND'} end
end

local exp = math.max(tonumber(hold[3]), now + tonumber(ARGV[3]))
local out = {'OK', epoch, exp}
for i = 1, n do
  redis.call('HSET', KEYS[6 + i], 'exp', exp)
  out[#out + 1] = redis.call('INCR', KEYS[2])
end
redis.call('HSET', KEYS[6], 'exp', exp)
redis.call('ZADD', KEYS[3], exp, ARGV[1])
return out
