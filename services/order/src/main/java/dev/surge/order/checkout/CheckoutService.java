package dev.surge.order.checkout;

import dev.surge.order.claim.ClaimResult;
import dev.surge.order.claim.ClaimService;
import dev.surge.order.inventory.InventoryClient;
import dev.surge.order.inventory.InventoryClient.PinOutcome;
import org.springframework.stereotype.Service;

/**
 * Checkout ordering (docs/design.md): pin the hold, then claim in Postgres, then
 * release the hold if the claim was lost. Pinning first means a crash at any point
 * leaves either a bounded, harmless lease or a claimed seat whose lease already
 * outlives the payment timeout.
 */
@Service
public class CheckoutService {

    public sealed interface Outcome {
        record Created(ClaimResult.Claimed order) implements Outcome {}

        record HoldRejected(PinOutcome.Reason reason) implements Outcome {}

        record ClaimRejected(ClaimResult.Reason reason) implements Outcome {}
    }

    private final InventoryClient inventory;
    private final ClaimService claims;

    public CheckoutService(InventoryClient inventory, ClaimService claims) {
        this.inventory = inventory;
        this.claims = claims;
    }

    public Outcome checkout(String userId, String holdId) {
        return switch (inventory.validateAndPin(holdId, userId)) {
            case PinOutcome.Rejected r -> new Outcome.HoldRejected(r.reason());
            case PinOutcome.Pinned pin -> switch (claims.claim(userId, pin.eventId(), pin.section(), pin.seatIds())) {
                case ClaimResult.Claimed c -> new Outcome.Created(c);
                case ClaimResult.Rejected r -> {
                    // Lost the claim: free the seats now rather than when the pinned lease ends.
                    inventory.release(holdId, userId);
                    yield new Outcome.ClaimRejected(r.reason());
                }
            };
        };
    }
}
