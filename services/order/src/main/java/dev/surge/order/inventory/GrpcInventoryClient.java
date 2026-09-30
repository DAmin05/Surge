package dev.surge.order.inventory;

import java.util.concurrent.TimeUnit;

import dev.surge.contracts.inventory.v1.InventoryServiceGrpc;
import dev.surge.contracts.inventory.v1.ReleaseHoldRequest;
import dev.surge.contracts.inventory.v1.ValidateAndPinHoldRequest;
import dev.surge.order.config.OrderProperties;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class GrpcInventoryClient implements InventoryClient, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(GrpcInventoryClient.class);

    private final ManagedChannel channel;
    private final InventoryServiceGrpc.InventoryServiceBlockingStub stub;
    private final long deadlineMs;

    public GrpcInventoryClient(OrderProperties props) {
        this.channel = NettyChannelBuilder.forTarget(props.inventoryGrpc()).usePlaintext().build();
        this.stub = InventoryServiceGrpc.newBlockingStub(channel);
        this.deadlineMs = props.inventoryDeadline().toMillis();
    }

    @Override
    public PinOutcome validateAndPin(String holdId, String userId) {
        try {
            var res = stub.withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS).validateAndPinHold(
                    ValidateAndPinHoldRequest.newBuilder().setHoldId(holdId).setUserId(userId).build());
            return switch (res.getStatus()) {
                case OK -> new PinOutcome.Pinned(res.getEventId(), res.getSection(), res.getSeatIdsList(),
                        res.getLeaseExpiresAtEpochMs());
                case WRONG_OWNER -> new PinOutcome.Rejected(PinOutcome.Reason.WRONG_OWNER);
                case REBUILDING -> new PinOutcome.Rejected(PinOutcome.Reason.REBUILDING);
                case NOT_FOUND, STATUS_UNSPECIFIED, UNRECOGNIZED -> new PinOutcome.Rejected(PinOutcome.Reason.NOT_FOUND);
            };
        } catch (StatusRuntimeException e) {
            throw new Unavailable("inventory pin failed: " + e.getStatus(), e);
        }
    }

    @Override
    public void release(String holdId, String userId) {
        try {
            stub.withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS).releaseHold(
                    ReleaseHoldRequest.newBuilder().setHoldId(holdId).setUserId(userId).build());
        } catch (StatusRuntimeException e) {
            log.warn("release of {} failed; its lease will expire instead: {}", holdId, e.getStatus());
        }
    }

    @Override
    public void close() {
        channel.shutdown();
    }
}
