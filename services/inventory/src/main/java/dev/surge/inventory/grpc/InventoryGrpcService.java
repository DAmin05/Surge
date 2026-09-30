package dev.surge.inventory.grpc;

import dev.surge.contracts.inventory.v1.InventoryServiceGrpc;
import dev.surge.contracts.inventory.v1.ReleaseHoldRequest;
import dev.surge.contracts.inventory.v1.ReleaseHoldResponse;
import dev.surge.contracts.inventory.v1.ValidateAndPinHoldRequest;
import dev.surge.contracts.inventory.v1.ValidateAndPinHoldResponse;
import dev.surge.contracts.inventory.v1.ValidateAndPinHoldResponse.Status;
import dev.surge.inventory.hold.HoldService;
import dev.surge.inventory.hold.PinResult;
import io.grpc.Status.Code;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Component;

@Component
public class InventoryGrpcService extends InventoryServiceGrpc.InventoryServiceImplBase {

    private final HoldService holds;

    public InventoryGrpcService(HoldService holds) {
        this.holds = holds;
    }

    @Override
    public void validateAndPinHold(ValidateAndPinHoldRequest req, StreamObserver<ValidateAndPinHoldResponse> out) {
        try {
            var res = switch (holds.validateAndPin(req.getHoldId(), req.getUserId())) {
                case PinResult.Pinned p -> ValidateAndPinHoldResponse.newBuilder()
                        .setStatus(Status.OK)
                        .setEventId(p.eventId())
                        .setSection(p.section())
                        .addAllSeatIds(p.seatIds())
                        .setLeaseExpiresAtEpochMs(p.leaseExpiresAtMs())
                        .build();
                case PinResult.Rejected r -> ValidateAndPinHoldResponse.newBuilder()
                        .setStatus(switch (r.reason()) {
                            case NOT_FOUND -> Status.NOT_FOUND;
                            case WRONG_OWNER -> Status.WRONG_OWNER;
                            case REBUILDING -> Status.REBUILDING;
                        })
                        .build();
            };
            out.onNext(res);
            out.onCompleted();
        } catch (IllegalArgumentException e) {
            // A malformed hold id can't name an existing hold.
            out.onNext(ValidateAndPinHoldResponse.newBuilder().setStatus(Status.NOT_FOUND).build());
            out.onCompleted();
        } catch (RuntimeException e) {
            out.onError(io.grpc.Status.fromCode(Code.UNAVAILABLE).withDescription(e.toString()).asRuntimeException());
        }
    }

    @Override
    public void releaseHold(ReleaseHoldRequest req, StreamObserver<ReleaseHoldResponse> out) {
        try {
            boolean released = holds.release(req.getHoldId(), req.getUserId().isEmpty() ? null : req.getUserId());
            out.onNext(ReleaseHoldResponse.newBuilder().setReleased(released).build());
            out.onCompleted();
        } catch (IllegalArgumentException e) {
            out.onNext(ReleaseHoldResponse.newBuilder().setReleased(false).build());
            out.onCompleted();
        } catch (RuntimeException e) {
            out.onError(io.grpc.Status.fromCode(Code.UNAVAILABLE).withDescription(e.toString()).asRuntimeException());
        }
    }
}
