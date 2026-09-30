# Hold scripts

Every key of a section shares the hash tag `{evt:<eventId>:sec:<section>}` (written
`T` below), so one script can touch all of an order's seats atomically on one shard.

| Key | Type | Meaning |
|---|---|---|
| `T:epoch` | string | random id; missing means the section is rebuilding |
| `T:seq` | counter | incremented once per seat change, by the script that made it |
| `T:expiry` | zset | holdId → lease end (ms); drives the sweeper |
| `T:sold` | set | sold seat ids (no TTL) |
| `T:held` | set | seat ids with a hold record (for snapshots) |
| `T:hold:<holdId>` | hash | `user`, `seats` (comma-separated ids), `exp` |
| `T:seat:<seatId>` | hash | `hold`, `user`, `exp` |

All scripts take the same key layout, so every key they touch is declared (required
for Redis Cluster routing):

```
KEYS: 1 epoch, 2 seq, 3 expiry, 4 sold, 5 held, 6 hold, 7.. seat keys
ARGV: 1 holdId, 2 userId, 3 script-specific, 4.. seat ids (same order as seat keys)
```

Scripts: `hold`, `pin`, `release` (owner or system; `expired` mode for the sweeper),
`sold` (order confirmed), `expired` (sweeper listing), `snapshot`.

Time always comes from the shard's own clock (`TIME`), never from the caller.
