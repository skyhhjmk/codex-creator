package com.skyhhjmk.codexcreator.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexQuotaServiceTest {
    @Test
    void pausesOnlyBelowConfiguredRemainingThresholds() {
        assertFalse(CodexQuotaService.pausesForRemaining(20, 10, 20, 10));
        assertTrue(CodexQuotaService.pausesForRemaining(19, 50, 20, 10));
        assertTrue(CodexQuotaService.pausesForRemaining(50, 9, 20, 10));
    }

    @Test
    void acceleratesIntervalByOnePointFiveWithoutAllowingZero() {
        assertEquals(60, CodexQuotaService.acceleratedIntervalMinutes(60, false));
        assertEquals(40, CodexQuotaService.acceleratedIntervalMinutes(60, true));
        assertEquals(1, CodexQuotaService.acceleratedIntervalMinutes(1, true));
    }
}
