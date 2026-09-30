package dev.surge.order.web;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import dev.surge.order.saga.PaymentOutcomes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Payment's callback: the authoritative driver of the saga after checkout. Signed with
 * HMAC-SHA256 over the raw body ({@code Surge-Signature: sha256=<hex>}), because a
 * forged "SUCCEEDED" would issue tickets for free. Schema:
 * {@code schemas/payment-webhook.schema.json}.
 *
 * <p>2xx tells Payment to stop retrying: returned for every outcome, including
 * duplicates and stale callbacks. 404 (unknown payment) is also final.
 */
@RestController
public class PaymentWebhookController {

    private final PaymentOutcomes outcomes;
    private final JsonMapper json;
    private final byte[] secret;

    public PaymentWebhookController(PaymentOutcomes outcomes, JsonMapper json,
            @Value("${surge.order.webhook-secret}") String secret) {
        this.outcomes = outcomes;
        this.json = json;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/payments/webhook")
    public ResponseEntity<Map<String, String>> webhook(
            @RequestHeader(value = "Surge-Signature", required = false) String signature,
            @RequestBody byte[] body) {
        if (!validSignature(signature, body)) {
            return ResponseEntity.status(401).body(Map.of("error", "BAD_SIGNATURE"));
        }
        UUID key;
        String status;
        try {
            JsonNode node = json.readTree(body);
            key = UUID.fromString(node.get("paymentKey").asString());
            status = node.get("status").asString();
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "BAD_REQUEST"));
        }
        PaymentOutcomes.Result result = switch (status) {
            case "SUCCEEDED" -> outcomes.succeeded(key, "webhook");
            case "FAILED" -> outcomes.failed(key, "webhook");
            default -> null;
        };
        if (result == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "BAD_STATUS"));
        }
        if (result == PaymentOutcomes.Result.UNKNOWN_PAYMENT) {
            return ResponseEntity.status(404).body(Map.of("result", result.name()));
        }
        return ResponseEntity.ok(Map.of("result", result.name()));
    }

    private boolean validSignature(String header, byte[] body) {
        if (header == null || !header.startsWith("sha256=")) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] expected = mac.doFinal(body);
            byte[] given = HexFormat.of().parseHex(header.substring("sha256=".length()));
            return MessageDigest.isEqual(expected, given);
        } catch (IllegalArgumentException e) {
            return false;
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }
}
