-- Join the waiting room: one position per user per event (ZADD NX keeps the first
-- arrival time). KEYS: 1 queue zset, 2 admitted zset. ARGV: 1 user.
-- Returns {'ADMITTED', admittedAtMs} | {'WAITING', rank (0-based)}
local admitted = redis.call('ZSCORE', KEYS[2], ARGV[1])
if admitted then return {'ADMITTED', tonumber(admitted)} end
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
redis.call('ZADD', KEYS[1], 'NX', now, ARGV[1])
return {'WAITING', redis.call('ZRANK', KEYS[1], ARGV[1])}
