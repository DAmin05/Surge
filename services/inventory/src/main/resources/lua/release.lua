-- Release a hold. ARGV[2] = owner to check, or '' for the system.
-- ARGV[3] = 'expired' (sweeper: only if the lease has lapsed) or 'any'.
-- Returns {'OK', epoch, seat, seq, seat, seq...} | {'NOT_FOUND'} | {'WRONG_OWNER'} | {'ALIVE'}
-- epoch is '' when the section is rebuilding; then no seq is issued (the rebuild
-- starts a new epoch and consumers re-snapshot).
local hold = redis.call('HMGET', KEYS[6], 'user', 'exp')
if not hold[1] then
  redis.call('ZREM', KEYS[3], ARGV[1])
  return {'NOT_FOUND'}
end
if ARGV[2] ~= '' and hold[1] ~= ARGV[2] then return {'WRONG_OWNER'} end

if ARGV[3] == 'expired' then
  local t = redis.call('TIME')
  local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
  -- Pinned since the sweeper listed it: leave it alone.
  if tonumber(hold[2]) > now then return {'ALIVE'} end
end

local epoch = redis.call('GET', KEYS[1])
local out = {'OK', epoch or ''}
for i = 1, #KEYS - 6 do
  -- Only seats this hold still owns; a lapsed seat may have been re-held already.
  if redis.call('HGET', KEYS[6 + i], 'hold') == ARGV[1] then
    local seat = ARGV[3 + i]
    redis.call('DEL', KEYS[6 + i])
    redis.call('SREM', KEYS[5], seat)
    if epoch then
      out[#out + 1] = seat
      out[#out + 1] = redis.call('INCR', KEYS[2])
    end
  end
end
redis.call('DEL', KEYS[6])
redis.call('ZREM', KEYS[3], ARGV[1])
return out
