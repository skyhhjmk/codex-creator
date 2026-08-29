package com.skyhhjmk.codexcreator.security;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class InternalRequestVerifierTest {
    @Test
    void acceptsOnlyWhenPersistentNonceStoreClaimsTheRequest() {
        String body = "{\"eventType\":\"post.published\"}";
        String clientId = "windblog";
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String nonce = "nonce-1";
        String digest = HmacSigner.bodyDigest(body);
        String signature = HmacSigner.sign("secret", clientId, timestamp, nonce, digest);
        AtomicInteger claims = new AtomicInteger();

        InternalRequestVerifier verifier = new InternalRequestVerifier();
        verifier.sharedSecret = Optional.of("secret");
        verifier.clockSkewSeconds = 300;
        verifier.nonceStore = new IntegrationNonceStore() {
            @Override
            public boolean claim(String ignoredClientId, String ignoredNonce, long ignoredExpiry) {
                return claims.getAndIncrement() == 0;
            }
        };

        assertTrue(verifier.verify(clientId, timestamp, nonce, digest, signature, body));
        assertFalse(verifier.verify(clientId, timestamp, nonce, digest, signature, body));
        assertEquals(2, claims.get());
    }

    @Test
    void rejectsWhenNonceStoreIsUnavailable() {
        String body = "{}";
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String digest = HmacSigner.bodyDigest(body);
        String signature = HmacSigner.sign("secret", "windblog", timestamp, "nonce-2", digest);

        InternalRequestVerifier verifier = new InternalRequestVerifier();
        verifier.sharedSecret = Optional.of("secret");
        verifier.clockSkewSeconds = 300;
        verifier.nonceStore = new IntegrationNonceStore() {
            @Override
            public boolean claim(String clientId, String nonce, long expiry) {
                throw new IllegalStateException("database unavailable");
            }
        };

        assertFalse(verifier.verify("windblog", timestamp, "nonce-2", digest, signature, body));
    }
}
