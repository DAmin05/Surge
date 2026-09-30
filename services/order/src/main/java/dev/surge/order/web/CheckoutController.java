package dev.surge.order.web;

import java.net.URI;
import java.util.List;
import java.util.Map;

import dev.surge.order.checkout.CheckoutService;
import dev.surge.order.checkout.CheckoutService.Outcome;
import dev.surge.order.inventory.InventoryClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Internal API; the gateway authenticates the caller and sets {@code X-User-Id}. */
@RestController
public class CheckoutController {

    public record CheckoutRequest(String holdId) {}

    public record CheckoutResponse(long orderId, String state, long amountCents, List<Long> seatIds) {}

    private final CheckoutService checkout;

    public CheckoutController(CheckoutService checkout) {
        this.checkout = checkout;
    }

    @PostMapping("/checkout")
    public ResponseEntity<?> checkout(@RequestHeader("X-User-Id") String userId, @RequestBody CheckoutRequest req) {
        if (req == null || req.holdId() == null || req.holdId().isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST");
        }
        return switch (checkout.checkout(userId, req.holdId())) {
            case Outcome.Created c -> ResponseEntity.created(URI.create("/orders/" + c.order().orderId()))
                    .body(new CheckoutResponse(c.order().orderId(), "SEAT_RESERVED", c.order().amountCents(),
                            c.order().seatIds()));
            case Outcome.HoldRejected r -> switch (r.reason()) {
                case NOT_FOUND -> error(HttpStatus.GONE, "HOLD_EXPIRED");
                case WRONG_OWNER -> error(HttpStatus.FORBIDDEN, "NOT_YOUR_HOLD");
                case REBUILDING -> retryLater();
            };
            case Outcome.ClaimRejected r -> switch (r.reason()) {
                case SEAT_TAKEN, USER_LIMIT -> error(HttpStatus.CONFLICT, r.reason().name());
                case INVALID_SEATS, UNKNOWN_SECTION -> error(HttpStatus.UNPROCESSABLE_CONTENT, r.reason().name());
            };
        };
    }

    @ExceptionHandler(InventoryClient.Unavailable.class)
    public ResponseEntity<Map<String, String>> inventoryDown() {
        return retryLater();
    }

    private static ResponseEntity<Map<String, String>> retryLater() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "1")
                .body(Map.of("error", "RETRY_LATER"));
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String code) {
        return ResponseEntity.status(status).body(Map.of("error", code));
    }
}
