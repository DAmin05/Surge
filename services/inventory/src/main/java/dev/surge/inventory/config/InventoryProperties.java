package dev.surge.inventory.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param holdLease      lease of a fresh hold
 * @param paymentTimeout T: how long an order may sit in PAYMENT_PENDING
 * @param pinGrace       covers sweeper + payment lookup + relay + consumer lag
 * @param redisNodes     host:port list; more than one node means Redis Cluster
 */
@ConfigurationProperties("surge.inventory")
public record InventoryProperties(
        Duration holdLease,
        Duration paymentTimeout,
        Duration pinGrace,
        int maxSeatsPerOrder,
        int maxSeatsPerUser,
        Duration sweepInterval,
        int sweepBatch,
        List<String> redisNodes,
        String kafkaBootstrap,
        int grpcPort) {

    /** A pinned lease must outlive T + grace, measured from the pin. */
    public Duration pinExtension() {
        return paymentTimeout.plus(pinGrace);
    }
}
