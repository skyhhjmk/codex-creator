package com.skyhhjmk.codexcreator.security;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.util.Optional;

@ApplicationScoped
public class InternalRequestVerifier {
    @ConfigProperty(name = "codex.creator.internal-shared-secret", defaultValue = "")
    Optional<String> sharedSecret;

    @ConfigProperty(name = "codex.creator.internal.clock-skew-seconds", defaultValue = "300")
    long clockSkewSeconds;

    @jakarta.inject.Inject
    IntegrationNonceStore nonceStore;

    public boolean verify(String clientId, String timestamp, String nonce,
                          String bodyDigest, String signature, String body) {
        if (sharedSecret.isEmpty() || isBlank(sharedSecret.get()) || isBlank(clientId) || isBlank(timestamp)
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
        String expected = HmacSigner.sign(sharedSecret.get(), clientId, timestamp, nonce, bodyDigest);
        if (!HmacSigner.constantTimeEquals(expected, signature)) {
            return false;
        }
        long expiry = now + Math.max(1, clockSkewSeconds);
        try {
            // The database primary key makes this claim atomic across threads,
            // processes, replicas, and restarts. Any database failure is fail-closed.
            return nonceStore.claim(clientId, nonce, expiry);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
