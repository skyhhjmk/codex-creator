package com.skyhhjmk.codexcreator.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Shared, versioned signing contract used by WindBlog and Codex Creator. */
public final class HmacSigner {
    public static final String ALGORITHM = "HmacSHA256";
    private HmacSigner() {
    }

    public static String bodyDigest(String body) {
        return HexFormat.of().formatHex(digest(body == null ? "" : body));
    }

    public static String sign(String secret, String clientId, String timestamp,
                              String nonce, String bodyDigest) {
        String canonical = timestamp + "\n" + nonce + "\n" + bodyDigest + "\n" + clientId;
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("cannot create integration signature", exception);
        }
    }

    public static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] digest(String body) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(body.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("cannot digest integration body", exception);
        }
    }
}
