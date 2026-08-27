package com.skyhhjmk.codexcreator.security;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class InternalRequestVerifier {
    private final Map<String, Long> nonces = new ConcurrentHashMap<>();

    @ConfigProperty(name = "codex.creator.internal-shared-secret", defaultValue = "")
    String sharedSecret;

    @ConfigProperty(name = "codex.creator.internal.clock-skew-seconds", defaultValue = "300")
    long clockSkewSeconds;

    public boolean verify(String clientId, String timestamp, String nonce,
                          String bodyDigest, String signature, String body) {
        if (isBlank(sharedSecret) || isBlank(clientId) || isBlank(timestamp)
                || isBlank(nonce) || isBlank(bodyDigest) || isBlank(signature)) {
            return false;
        }
        long timestampSeconds;
        try {
            timestampSeconds = Long.parseLong(timestamp);
        } catch (NumberFormatException ignored) {
            return false;
        }
        long now = Instant.now().getEpochSecond();
        if (Math.abs(now - timestampSeconds) > Math.max(1, clockSkewSeconds)) {
            return false;
        }
        if (!HmacSigner.constantTimeEquals(bodyDigest, HmacSigner.bodyDigest(body))) {
            return false;
        }
        String expected = HmacSigner.sign(sharedSecret, clientId, timestamp, nonce, bodyDigest);
        if (!HmacSigner.constantTimeEquals(expected, signature)) {
            return false;
        }
        long expiry = now + Math.max(1, clockSkewSeconds);
        purge(now);
        return nonces.putIfAbsent(clientId + ":" + nonce, expiry) == null;
    }

    private void purge(long now) {
        nonces.entrySet().removeIf(entry -> entry.getValue() < now);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
