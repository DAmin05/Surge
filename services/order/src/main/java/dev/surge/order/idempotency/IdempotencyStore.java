package dev.surge.order.idempotency;

import java.time.Duration;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Idempotency keys, scoped per user: {@code (user_id, key)}.
 *
 * <ul>
 *   <li>first use: a row with a null response ("in progress") and a lease token;</li>
 *   <li>same key while in progress: {@code 409};</li>
 *   <li>same key, different request: {@code 422};</li>
 *   <li>same key after completion: the stored response, replayed.</li>
 * </ul>
 *
 * A request that dies mid-flight would leave its key in progress forever, so a retry
 * may take the key over once it's older than {@code staleAfter}. Taking over issues a
 * new lease token, and only the token holder can complete the key: if the old request
 * wakes up and tries to commit, its completion matches no row and it rolls back.
 */
@Component
public class IdempotencyStore {

    public sealed interface Begin {
        record Started(UUID lease) implements Begin {}

        record Replay(int status, String body) implements Begin {}

        record InProgress() implements Begin {}

        record Mismatch() implements Begin {}
    }

    /** The lease was taken over; this request must not commit. */
    public static final class LeaseLost extends RuntimeException {
        public LeaseLost() {
            super("idempotency lease lost", null, false, false);
        }
    }

    private final JdbcClient jdbc;
    private final Duration staleAfter;

    public IdempotencyStore(JdbcClient jdbc,
            @org.springframework.beans.factory.annotation.Value("${surge.order.idempotency-stale-after:PT60S}")
            Duration staleAfter) {
        this.jdbc = jdbc;
        this.staleAfter = staleAfter;
    }

    public Begin begin(String userId, String key, String requestHash) {
        UUID lease = UUID.randomUUID();
        int inserted = jdbc.sql("""
                INSERT INTO idempotency_keys (user_id, key, request_hash, lease_token)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (user_id, key) DO NOTHING""")
                .params(userId, key, requestHash, lease)
                .update();
        if (inserted == 1) {
            return new Begin.Started(lease);
        }

        record Row(String hash, Integer status, String body) {}
        Row row = jdbc.sql("""
                SELECT request_hash, response_code, response_body::text AS body
                  FROM idempotency_keys WHERE user_id = ? AND key = ?""")
                .params(userId, key)
                .query((rs, i) -> new Row(rs.getString(1), (Integer) rs.getObject(2), rs.getString(3)))
                .optional().orElse(null);
        if (row == null) {
            // Deleted between our insert and select (abandoned or cleaned up): retry once.
            return begin(userId, key, requestHash);
        }
        if (!row.hash().equals(requestHash)) {
            return new Begin.Mismatch();
        }
        if (row.status() != null) {
            return new Begin.Replay(row.status(), row.body());
        }
        int takenOver = jdbc.sql("""
                UPDATE idempotency_keys SET lease_token = ?, created_at = now()
                 WHERE user_id = ? AND key = ? AND response_code IS NULL
                   AND created_at < now() - make_interval(secs => ?)""")
                .params(lease, userId, key, staleAfter.toSeconds())
                .update();
        return takenOver == 1 ? new Begin.Started(lease) : new Begin.InProgress();
    }

    /**
     * Stores the response. Call inside the transaction that made the change, so the
     * response and the change commit together.
     *
     * @throws LeaseLost if another request has taken the key over
     */
    public void complete(String userId, String key, UUID lease, int status, String body) {
        int n = jdbc.sql("""
                UPDATE idempotency_keys SET response_code = ?, response_body = ?::jsonb
                 WHERE user_id = ? AND key = ? AND lease_token = ? AND response_code IS NULL""")
                .params(status, body, userId, key, lease)
                .update();
        if (n != 1) {
            throw new LeaseLost();
        }
    }

    /** For responses that must not be replayed (5xx): frees the key for a retry. */
    public void abandon(String userId, String key, UUID lease) {
        jdbc.sql("DELETE FROM idempotency_keys WHERE user_id = ? AND key = ? AND lease_token = ? AND response_code IS NULL")
                .params(userId, key, lease)
                .update();
    }

    /** @return rows deleted */
    public int deleteOlderThan(Duration age) {
        return jdbc.sql("DELETE FROM idempotency_keys WHERE created_at < now() - make_interval(secs => ?)")
                .param(age.toSeconds())
                .update();
    }
}
