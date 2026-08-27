package com.skyhhjmk.codexcreator.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HmacSignerTest {
    @Test
    void signsBodyAndRejectsTampering() {
        String body = "{\"eventType\":\"comment.created\"}";
        String digest = HmacSigner.bodyDigest(body);
        String signature = HmacSigner.sign("secret", "windblog", "1700000000", "n-1", digest);

        assertEquals(64, digest.length());
        assertEquals(signature, HmacSigner.sign("secret", "windblog", "1700000000", "n-1", digest));
        assertFalse(HmacSigner.constantTimeEquals(signature,
                HmacSigner.sign("secret", "windblog", "1700000000", "n-1", HmacSigner.bodyDigest("tampered"))));
    }
}
