package com.skyhhjmk.codexcreator.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArticleEvidenceServiceTest {
    @Test
    void redactsSecretsAndTruncatesCommandOutputBeforeItCanReachAnArticle() {
        String output = "token=super-secret-token-value\nAuthorization: Bearer abcdefghijklmnop\n"
                + "-----BEGIN PRIVATE KEY-----\nprivate\n-----END PRIVATE KEY-----\n" + "x".repeat(50);

        String sanitized = ArticleEvidenceService.sanitize(output, 80);

        assertFalse(sanitized.contains("super-secret-token-value"));
        assertFalse(sanitized.contains("abcdefghijklmnop"));
        assertFalse(sanitized.contains("BEGIN PRIVATE KEY"));
        assertTrue(sanitized.contains("[REDACTED]"));
        assertTrue(sanitized.contains("[output truncated]"));
    }
}
