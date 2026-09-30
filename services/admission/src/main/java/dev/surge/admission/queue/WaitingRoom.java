package dev.surge.admission.queue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import dev.surge.admission.config.AdmissionProperties;
import dev.surge.admission.tokens.TokenType;
import dev.surge.admission.tokens.Tokens;
import io.lettuce.core.RedisNoScriptException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.cluster.api.sync.RedisClusterCommands;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * The waiting room: a sorted set per event, scored by arrival time. Turns the
 * thundering herd into a flow that checkout can absorb.
 *
 * <p>Keys share the hash tag {@code {adm:evt:<id>}}: the queue, the set of admitted
 * users (scored by admission time), and the rate limiter's bucket.
 */
@Service
public class WaitingRoom {

    public static final String EVENTS = "admission:events";

    public sealed interface Position {
        /** @param position 1-based; {@code ahead} = position - 1 */
        record Waiting(long position, long ahead, long estimatedWaitSeconds) implements Position {}

        record Admitted(String token, Instant expiresAt) implements Position {}

        record NotInQueue() implements Position {}
    }

    private final RedisClusterCommands<String, String> redis;
    private final Tokens tokens;
    private final AdmissionProperties props;
    private final MeterRegistry meters;
    private final String joinScript = read("lua/join.lua");
    private final String admitScript = read("lua/admit.lua");

    public WaitingRoom(RedisClusterCommands<String, String> redis, Tokens tokens, AdmissionProperties props,
            MeterRegistry meters) {
        this.redis = redis;
        this.tokens = tokens;
        this.props = props;
        this.meters = meters;
    }

    static String tag(long eventId) {
        return "{adm:evt:" + eventId + "}";
    }

    static String queue(long eventId) {
        return tag(eventId) + ":queue";
    }

    static String admitted(long eventId) {
        return tag(eventId) + ":admitted";
    }

    static String bucket(long eventId) {
        return tag(eventId) + ":bucket";
    }

    /** One position per user per event: joining again keeps the original place. */
    public Position join(String userId, long eventId) {
        redis.sadd(EVENTS, Long.toString(eventId));
        List<Object> r = eval(joinScript, List.of(queue(eventId), admitted(eventId)), List.of(userId));
        if ("ADMITTED".equals(r.get(0))) {
            return admittedPosition(userId, eventId, (Long) r.get(1));
        }
        meters.counter("queue_joins_total").increment();
        return waiting((Long) r.get(1));
    }

    public Position position(String userId, long eventId) {
        Double admittedAt = redis.zscore(admitted(eventId), userId);
        if (admittedAt != null) {
            return admittedPosition(userId, eventId, admittedAt.longValue());
        }
        Long rank = redis.zrank(queue(eventId), userId);
        return rank == null ? new Position.NotInQueue() : waiting(rank);
    }

    /** Lets the next users in, at the global rate. Safe to call from every instance. */
    public long admitNext(long eventId) {
        long lifetimeMs = props.tokenLifetime().toMillis();
        List<Object> r = eval(admitScript, List.of(queue(eventId), admitted(eventId), bucket(eventId)),
                List.of(Integer.toString(props.admitRatePerSec()), Integer.toString(props.admitRatePerSec()),
                        Long.toString(lifetimeMs)));
        long n = (Long) r.getFirst();
        if (n > 0) {
            meters.counter("queue_admitted_total").increment(n);
        }
        return n;
    }

    public long depth(long eventId) {
        return redis.zcard(queue(eventId));
    }

    /**
     * Admission tokens are minted on read and expire a fixed time after admission, so
     * the admitted set is the only state: nothing to lose between "admitted" and
     * "token handed out".
     */
    private Position admittedPosition(String userId, long eventId, long admittedAtMs) {
        Instant exp = Instant.ofEpochMilli(admittedAtMs).plus(props.tokenLifetime());
        return new Position.Admitted(tokens.issue(TokenType.ADMISSION, userId, eventId, exp), exp);
    }

    private Position waiting(long rank) {
        long rate = Math.max(1, props.admitRatePerSec());
        return new Position.Waiting(rank + 1, rank, (rank + rate - 1) / rate);
    }

    private List<Object> eval(String script, List<String> keys, List<String> args) {
        String[] k = keys.toArray(String[]::new);
        String[] a = args.toArray(String[]::new);
        try {
            return redis.evalsha(redis.digest(script), ScriptOutputType.MULTI, k, a);
        } catch (RedisNoScriptException e) {
            return redis.eval(script, ScriptOutputType.MULTI, k, a);
        }
    }

    private static String read(String path) {
        try (var in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
