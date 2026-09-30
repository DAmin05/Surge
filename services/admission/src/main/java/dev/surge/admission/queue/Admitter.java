package dev.surge.admission.queue;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.lettuce.core.cluster.api.sync.RedisClusterCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Ticks every admit interval; the Redis bucket decides how many actually get in. */
@Component
public class Admitter {

    private static final Logger log = LoggerFactory.getLogger(Admitter.class);

    private final WaitingRoom room;
    private final RedisClusterCommands<String, String> redis;
    private final MeterRegistry meters;
    private final Map<Long, AtomicLong> depths = new ConcurrentHashMap<>();

    public Admitter(WaitingRoom room, RedisClusterCommands<String, String> redis, MeterRegistry meters) {
        this.room = room;
        this.redis = redis;
        this.meters = meters;
    }

    @Scheduled(fixedDelayString = "${surge.admission.admit-interval}")
    public void tick() {
        try {
            for (String event : redis.smembers(WaitingRoom.EVENTS)) {
                long eventId = Long.parseLong(event);
                room.admitNext(eventId);
                depth(eventId).set(room.depth(eventId));
            }
        } catch (RuntimeException e) {
            log.warn("admit tick failed: {}", e.toString());
        }
    }

    private AtomicLong depth(long eventId) {
        return depths.computeIfAbsent(eventId, id -> {
            var value = new AtomicLong();
            Gauge.builder("queue_depth", value, AtomicLong::get).tag("event", Long.toString(id)).register(meters);
            return value;
        });
    }
}
