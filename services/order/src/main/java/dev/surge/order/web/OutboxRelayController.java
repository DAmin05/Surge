package dev.surge.order.web;

import dev.surge.order.outbox.OutboxRelay;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Chaos control for the outbox relay, on the internal network only: the gateway never
 * routes {@code /internal}. Pausing it past the pin grace is how the
 * holds-expired-while-reserved scenario is produced.
 */
@RestController
public class OutboxRelayController {

    public record RelayState(boolean paused) {}

    private final OutboxRelay relay;

    public OutboxRelayController(OutboxRelay relay) {
        this.relay = relay;
    }

    @GetMapping("/internal/outbox-relay")
    public RelayState get() {
        return new RelayState(relay.paused());
    }

    @PutMapping("/internal/outbox-relay")
    public RelayState set(@RequestBody RelayState state) {
        relay.setPaused(state.paused());
        return new RelayState(relay.paused());
    }
}
