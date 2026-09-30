package dev.surge.order.payment;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/** Asks Payment for a charge's status. Used before any timeout cancellation. */
@Component
public class PaymentClient {

    public enum Status { PENDING, CAPTURED, FAILED, REFUNDED }

    record StatusResponse(String status) {}

    private final RestClient http;

    public PaymentClient(@Value("${surge.order.payment-url}") String baseUrl) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(1));
        factory.setReadTimeout(Duration.ofSeconds(2));
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    /**
     * @return empty if Payment has never seen this key
     * @throws org.springframework.web.client.RestClientException if Payment can't answer
     */
    public Optional<Status> status(UUID paymentKey) {
        try {
            var res = http.get().uri("/payments/{key}", paymentKey).retrieve().body(StatusResponse.class);
            return Optional.of(Status.valueOf(res.status()));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }
}
