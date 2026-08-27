package com.skyhhjmk.codexcreator.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PolicyAndCacheKeyTest {
    @Test
    void automaticPublishingRequiresEveryGate() {
        AutoPublishPolicy allowed = new AutoPublishPolicy(true, true, true, true, true, true, false);
        assertTrue(allowed.eligible());
        assertFalse(new AutoPublishPolicy(true, true, true, true, true, true, true).eligible());
        assertFalse(new AutoPublishPolicy(true, false, true, true, true, true, false).eligible());
    }

    @Test
    void cacheKeyIncludesOperationProfileAndPromptVersion() {
        String left = CacheKey.forTask("summarize", "same-input", "codex-default", "1");
        String right = CacheKey.forTask("summarize", "same-input", "codex-default", "2");
        assertNotEquals(left, right);
        assertTrue(left.startsWith("codex:task:"));
    }
}
