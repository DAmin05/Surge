-- Consistent view of a section for a new WebSocket client.
-- KEYS: 1 epoch, 2 seq, 3 sold, 4 held. Returns {epoch or '', seq, sold[], held[]}.
return {
  redis.call('GET', KEYS[1]) or '',
  tonumber(redis.call('GET', KEYS[2]) or '0'),
  redis.call('SMEMBERS', KEYS[3]),
  redis.call('SMEMBERS', KEYS[4]),
}
