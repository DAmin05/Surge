package dev.surge.inventory.web;

import java.net.URI;
import java.util.List;
import java.util.Map;

import dev.surge.inventory.hold.HoldResult;
import dev.surge.inventory.hold.HoldService;
import dev.surge.inventory.hold.Snapshot;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal API; the gateway authenticates the caller and sets {@code X-User-Id}.
 */
@RestController
public class HoldController {

    public record HoldRequest(long eventId, String section, List<Long> seatIds) {}

    public record HoldResponse(String holdId, long expiresAtMs) {}

    private final HoldService holds;

    public HoldController(HoldService holds) {
        this.holds = holds;
    }

    @PostMapping("/holds")
    public ResponseEntity<?> hold(@RequestHeader("X-User-Id") String userId, @RequestBody HoldRequest req) {
        return switch (holds.hold(userId, req.eventId(), req.section(), req.seatIds())) {
            case HoldResult.Held h -> ResponseEntity.created(URI.create("/holds/" + h.holdId()))
                    .body(new HoldResponse(h.holdId().value(), h.expiresAtMs()));
            case HoldResult.Rejected r when r.reason() == HoldResult.Reason.REBUILDING ->
                    ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "1")
                            .body(Map.of("error", r.reason()));
            case HoldResult.Rejected r -> ResponseEntity.status(HttpStatus.CONFLICT).body(
                    r.seatId() == null ? Map.of("error", r.reason()) : Map.of("error", r.reason(), "seatId", r.seatId()));
        };
    }

    @DeleteMapping("/holds/{holdId}")
    public ResponseEntity<Void> release(@RequestHeader("X-User-Id") String userId, @PathVariable String holdId) {
        return holds.release(holdId, userId) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @GetMapping("/sections/{eventId}/{section}/snapshot")
    public Snapshot snapshot(@PathVariable long eventId, @PathVariable String section) {
        return holds.snapshot(eventId, section);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", "BAD_REQUEST", "message", e.getMessage()));
    }
}
