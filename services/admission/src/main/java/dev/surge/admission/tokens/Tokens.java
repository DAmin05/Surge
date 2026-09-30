package dev.surge.admission.tokens;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import dev.surge.admission.config.AdmissionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Issues and checks Ed25519-signed JWTs ({@code alg: EdDSA}). Only Admission holds the
 * private keys; the gateway verifies with public keys, no network call per request.
 * Keys are re-read periodically, so a rotation needs no restart.
 */
@Component
public class Tokens {

    private static final Logger log = LoggerFactory.getLogger(Tokens.class);
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    /** Verified claims. {@code eventId} is null for session tokens. */
    public record Claims(String sub, Long eventId, Instant exp, String jti) {}

    private final AdmissionProperties props;
    private final JsonMapper json;
    private volatile Map<TokenType, KeySet> keys;

    public Tokens(AdmissionProperties props, JsonMapper json) {
        this.props = props;
        this.json = json;
        this.keys = loadAll();
    }

    @Scheduled(fixedDelayString = "PT30S")
    void reload() {
        try {
            var fresh = loadAll();
            if (!fresh.equals(keys)) {
                keys = fresh;
                log.info("signing keys reloaded: session={} admission={}", fresh.get(TokenType.SESSION).signingKid(),
                        fresh.get(TokenType.ADMISSION).signingKid());
            }
        } catch (RuntimeException e) {
            log.warn("key reload failed; keeping current keys: {}", e.toString());
        }
    }

    private Map<TokenType, KeySet> loadAll() {
        var map = new EnumMap<TokenType, KeySet>(TokenType.class);
        for (TokenType t : TokenType.values()) {
            map.put(t, KeySet.load(props.keysDir(), t));
        }
        return map;
    }

    public String issue(TokenType type, String sub, Long eventId, Instant exp) {
        KeySet ks = keys.get(type);
        var header = new LinkedHashMap<String, Object>();
        header.put("alg", "EdDSA");
        header.put("typ", type.header);
        header.put("kid", ks.signingKid());
        var claims = new LinkedHashMap<String, Object>();
        claims.put("sub", sub);
        if (eventId != null) {
            claims.put("eventId", eventId);
        }
        claims.put("iat", Instant.now().getEpochSecond());
        claims.put("exp", exp.getEpochSecond());
        claims.put("jti", UUID.randomUUID().toString());
        String signingInput = b64json(header) + "." + b64json(claims);
        try {
            Signature sig = Signature.getInstance("Ed25519");
            sig.initSign(ks.signingKey());
            sig.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + B64.encodeToString(sig.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Verifies signature, type, and expiry. Empty for anything invalid. */
    public Optional<Claims> verify(TokenType type, String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                return Optional.empty();
            }
            JsonNode header = json.readTree(B64D.decode(parts[0]));
            if (!"EdDSA".equals(header.path("alg").asString()) || !type.header.equals(header.path("typ").asString())) {
                return Optional.empty();
            }
            var key = keys.get(type).verificationKey(header.path("kid").asString());
            if (key.isEmpty()) {
                return Optional.empty();
            }
            Signature sig = Signature.getInstance("Ed25519");
            sig.initVerify(key.get());
            sig.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!sig.verify(B64D.decode(parts[2]))) {
                return Optional.empty();
            }
            JsonNode claims = json.readTree(B64D.decode(parts[1]));
            Instant exp = Instant.ofEpochSecond(claims.path("exp").asLong());
            if (!exp.isAfter(Instant.now())) {
                return Optional.empty();
            }
            Long eventId = claims.has("eventId") ? claims.get("eventId").asLong() : null;
            return Optional.of(new Claims(claims.path("sub").asString(), eventId, exp, claims.path("jti").asString()));
        } catch (RuntimeException | GeneralSecurityException e) {
            return Optional.empty();
        }
    }

    private String b64json(Map<String, Object> value) {
        return B64.encodeToString(json.writeValueAsBytes(value));
    }
}
