package dev.surge.order.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param maxSeatsPerUser authoritative per-user, per-event limit (checked in the claim)
 * @param inventoryGrpc   host:port of Inventory's gRPC server
 */
@ConfigurationProperties("surge.order")
public record OrderProperties(int maxSeatsPerUser, String inventoryGrpc, Duration inventoryDeadline) {}
