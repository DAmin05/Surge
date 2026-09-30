package dev.surge.order.web;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** A buyer's view of their order. Other users' orders don't exist, as far as they know. */
@RestController
public class OrderController {

    public record Ticket(long seatId, String section, String row, int number, long unitPriceCents,
            boolean issued) {}

    public record OrderView(long orderId, long eventId, String section, String state, long amountCents,
            OffsetDateTime createdAt, List<Ticket> seats) {}

    private final JdbcClient jdbc;

    public OrderController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/orders/{orderId}")
    public ResponseEntity<OrderView> order(@RequestHeader("X-User-Id") String userId, @PathVariable long orderId) {
        record Head(long eventId, String section, String state, long amount, OffsetDateTime createdAt) {}
        var head = jdbc.sql("""
                SELECT event_id, section, state, amount_cents, created_at FROM orders
                 WHERE id = ? AND user_id = ?""")
                .params(orderId, userId)
                .query((rs, i) -> new Head(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                        rs.getObject(5, OffsetDateTime.class)))
                .optional();
        if (head.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        var seats = jdbc.sql("""
                SELECT s.id, s.section, s.row_label, s.seat_number, i.unit_price_cents, t.id IS NOT NULL
                  FROM order_items i
                  JOIN seats s ON s.id = i.seat_id
                  LEFT JOIN tickets t ON t.seat_id = s.id AND t.order_id = i.order_id
                 WHERE i.order_id = ?
                 ORDER BY s.row_label, s.seat_number""")
                .param(orderId)
                .query((rs, i) -> new Ticket(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                        rs.getLong(5), rs.getBoolean(6)))
                .list();
        var h = head.get();
        return ResponseEntity.ok(new OrderView(orderId, h.eventId(), h.section(), h.state(), h.amount(),
                h.createdAt(), seats));
    }
}
