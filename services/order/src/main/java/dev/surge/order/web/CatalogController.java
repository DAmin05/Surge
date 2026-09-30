package dev.surge.order.web;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The seat layout of an event: sections with prices and seats. Static during a sale;
 * live seat state comes from Inventory's snapshots and the seat-events stream.
 */
@RestController
public class CatalogController {

    public record Seat(long id, String row, int number) {}

    public record Section(String section, long priceCents, List<Seat> seats) {}

    public record Catalog(long eventId, String name, OffsetDateTime startsAt, int capacity, List<Section> sections) {}

    private final JdbcClient jdbc;

    public CatalogController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record EventSummary(long eventId, String name, OffsetDateTime startsAt, int capacity) {}

    /** Events on sale, newest first. */
    @GetMapping("/events")
    public List<EventSummary> events() {
        return jdbc.sql("SELECT id, name, starts_at, capacity FROM events ORDER BY id DESC LIMIT 50")
                .query((rs, i) -> new EventSummary(rs.getLong(1), rs.getString(2),
                        rs.getObject(3, OffsetDateTime.class), rs.getInt(4)))
                .list();
    }

    @GetMapping("/events/{eventId}")
    public ResponseEntity<Catalog> catalog(@PathVariable long eventId) {
        record Event(String name, OffsetDateTime startsAt, int capacity) {}
        var event = jdbc.sql("SELECT name, starts_at, capacity FROM events WHERE id = ?")
                .param(eventId)
                .query((rs, i) -> new Event(rs.getString(1), rs.getObject(2, OffsetDateTime.class), rs.getInt(3)))
                .optional();
        if (event.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Long> prices = new LinkedHashMap<>();
        jdbc.sql("SELECT section, price_cents FROM section_prices WHERE event_id = ? ORDER BY section")
                .param(eventId)
                .query(rs -> {
                    prices.put(rs.getString(1), rs.getLong(2));
                });
        Map<String, List<Seat>> seats = new LinkedHashMap<>();
        prices.keySet().forEach(s -> seats.put(s, new ArrayList<>()));
        jdbc.sql("""
                SELECT section, id, row_label, seat_number FROM seats
                 WHERE event_id = ? ORDER BY section, row_label, seat_number""")
                .param(eventId)
                .query(rs -> {
                    seats.computeIfAbsent(rs.getString(1), k -> new ArrayList<>())
                            .add(new Seat(rs.getLong(2), rs.getString(3), rs.getInt(4)));
                });
        var sections = prices.entrySet().stream()
                .map(e -> new Section(e.getKey(), e.getValue(), seats.getOrDefault(e.getKey(), List.of())))
                .toList();
        var e = event.get();
        return ResponseEntity.ok()
                .header("Cache-Control", "public, max-age=30")
                .body(new Catalog(eventId, e.name(), e.startsAt(), e.capacity(), sections));
    }
}
