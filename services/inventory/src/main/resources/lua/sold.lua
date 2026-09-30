-- Mark an order's seats sold (no TTL) and delete its hold. Idempotent: seats already
-- sold are skipped, so a redelivered OrderConfirmed changes nothing.
-- Returns {'OK', epoch or '', lapsed, seat, seq, seat, seq...}
--   lapsed: seats whose hold was gone or re-held by someone else when the sale landed
--           (the lease didn't outlive the saga: holds_expired_while_reserved).
local epoch = redis.call('GET', KEYS[1])
local out = {'OK', epoch or '', 0}
local lapsed = 0
for i = 1, #KEYS - 6 do
  local seat = ARGV[3 + i]
  if redis.call('SISMEMBER', KEYS[4], seat) == 0 then
    local holder = redis.call('HGET', KEYS[6 + i], 'hold')
    if holder == ARGV[1] then
      redis.call('DEL', KEYS[6 + i])
      redis.call('SREM', KEYS[5], seat)
    else
      -- A stale hold by someone else stays until it lapses; that buyer loses at the
      -- Postgres claim, and the hold script refuses new holds on a sold seat.
      lapsed = lapsed + 1
    end
    redis.call('SADD', KEYS[4], seat)
    if epoch then
      out[#out + 1] = seat
      out[#out + 1] = redis.call('INCR', KEYS[2])
    end
  end
end
redis.call('DEL', KEYS[6])
redis.call('ZREM', KEYS[3], ARGV[1])
out[3] = lapsed
return out
