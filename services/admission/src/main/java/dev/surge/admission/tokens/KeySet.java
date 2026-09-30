package dev.surge.admission.tokens;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Keys for one token type, as laid out by {@code scripts/keys.sh}: sign with
 * {@code current}, accept {@code current} and {@code previous}. Immutable; reload by
 * building a new one.
 */
public record KeySet(String signingKid, PrivateKey signingKey, Map<String, PublicKey> verificationKeys) {

    public static KeySet load(Path root, TokenType type) {
        Path dir = root.resolve(type.dir);
        try {
            String current = Files.readString(dir.resolve("current")).strip();
            var verify = new HashMap<String, PublicKey>();
            verify.put(current, publicKey(dir.resolve(current + ".pub")));
            Path previousFile = dir.resolve("previous");
            if (Files.exists(previousFile)) {
                String previous = Files.readString(previousFile).strip();
                Path pub = dir.resolve(previous + ".pub");
                if (Files.exists(pub)) {
                    verify.put(previous, publicKey(pub));
                }
            }
            return new KeySet(current, privateKey(dir.resolve(current + ".key")), Map.copyOf(verify));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + type.dir + " keys under " + root, e);
        }
    }

    public Optional<PublicKey> verificationKey(String kid) {
        return Optional.ofNullable(verificationKeys.get(kid));
    }

    private static PrivateKey privateKey(Path pem) throws IOException {
        try {
            return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(der(pem)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("bad private key " + pem, e);
        }
    }

    private static PublicKey publicKey(Path pem) throws IOException {
        try {
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der(pem)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("bad public key " + pem, e);
        }
    }

    private static byte[] der(Path pem) throws IOException {
        String body = Files.readString(pem).replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }
}
