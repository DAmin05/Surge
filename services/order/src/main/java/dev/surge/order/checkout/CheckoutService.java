package dev.surge.order.checkout;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.surge.order.claim.ClaimResult;
import dev.surge.order.claim.ClaimService;
import dev.surge.order.idempotency.IdempotencyStore;
import dev.surge.order.idempotency.IdempotencyStore.Begin;
import dev.surge.order.inventory.InventoryClient;
import dev.surge.order.inventory.InventoryClient.PinOutcome;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /checkout}, idempotent per {@code (user, Idempotency-Key)}.
 *
 * <p>Ordering (docs/design.md): pin the hold, then claim in Postgres, then release the
 * hold if the claim was lost. Pinning first means a crash at any point leaves either a
 * bounded, harmless lease or a claimed seat whose lease already outlives the payment
 * timeout. A successful response is committed in the claim transaction itself, so
 * "order exists" and "response stored" can't disagree.
 */
@Service
public class CheckoutService {

    /** What to send back. {@code replayed}: served from the idempotency store. */
    public record Reply(int status, String body, boolean replayed) {
        public boolean retryLater() {
            return status == 503;
        }
    }

    private final InventoryClient inventory;
    private final ClaimService claims;
    private final IdempotencyStore idempotency;
    private final JsonMapper json;

    public CheckoutService(InventoryClient inventory, ClaimService claims, IdempotencyStore idempotency,
            JsonMapper json) {
        this.inventory = inventory;
        this.claims = claims;
        this.idempotency = idempotency;
        this.json = json;
    }

    public Reply checkout(String userId, String idempotencyKey, String holdId) {
        return switch (idempotency.begin(userId, idempotencyKey, requestHash(holdId))) {
            case Begin.Replay r -> new Reply(r.status(), r.body(), true);
            case Begin.InProgress p -> error(409, "REQUEST_IN_PROGRESS");
            case Begin.Mismatch m -> error(422, "IDEMPOTENCY_KEY_REUSED");
            case Begin.Started s -> run(userId, idempotencyKey, s.lease(), holdId);
        };
    }

    private Reply run(String userId, String key, UUID lease, String holdId) {
        try {
            PinOutcome pin = inventory.validateAndPin(holdId, userId);
            if (pin instanceof PinOutcome.Rejected r) {
                if (r.reason() == PinOutcome.Reason.REBUILDING) {
                    idempotency.abandon(userId, key, lease);
                    return error(503, "RETRY_LATER");
                }
                return completed(userId, key, lease, r.reason() == PinOutcome.Reason.WRONG_OWNER
                        ? error(403, "NOT_YOUR_HOLD") : error(410, "HOLD_EXPIRED"));
            }
            var pinned = (PinOutcome.Pinned) pin;

            ClaimResult result = claims.claim(userId, pinned.eventId(), pinned.section(), pinned.seatIds(), holdId,
                    claimed -> idempotency.complete(userId, key, lease, 201, created(claimed)));
            return switch (result) {
                case ClaimResult.Claimed c -> new Reply(201, created(c), false);
                case ClaimResult.Rejected r -> {
                    // Lost the claim: free the seats now rather than when the pinned lease ends.
                    inventory.release(holdId, userId);
                    yield completed(userId, key, lease, switch (r.reason()) {
                        case SEAT_TAKEN, USER_LIMIT -> error(409, r.reason().name());
                        case INVALID_SEATS, UNKNOWN_SECTION -> error(422, r.reason().name());
                    });
                }
            };
        } catch (IdempotencyStore.LeaseLost e) {
            // A retry took this key over; it owns the outcome now.
            return error(409, "REQUEST_IN_PROGRESS");
        } catch (InventoryClient.Unavailable e) {
            idempotency.abandon(userId, key, lease);
            return error(503, "RETRY_LATER");
        } catch (RuntimeException e) {
            try {
                idempotency.abandon(userId, key, lease);
            } catch (RuntimeException ignored) {
                // Database unreachable: the key frees itself once stale.
            }
            throw e;
        }
    }

    private Reply completed(String userId, String key, UUID lease, Reply reply) {
        idempotency.complete(userId, key, lease, reply.status(), reply.body());
        return reply;
    }

    private String created(ClaimResult.Claimed c) {
        var body = new LinkedHashMap<String, Object>();
        body.put("orderId", c.orderId());
        body.put("state", "PAYMENT_PENDING");
        body.put("amountCents", c.amountCents());
        body.put("seatIds", List.copyOf(c.seatIds()));
        return json.writeValueAsString(body);
    }

    private Reply error(int status, String code) {
        return new Reply(status, json.writeValueAsString(Map.of("error", code)), false);
    }

    /** The key may only be reused for the identical request. */
    static String requestHash(String holdId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(("POST /checkout\n" + holdId).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
