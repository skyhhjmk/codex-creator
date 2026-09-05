package com.skyhhjmk.codexcreator.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskExecutionServiceTest {
    @Test
    void usageLimitSchedulesTheProviderAdvertisedFutureWindow() {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-04T17:00:00+08:00");
        Duration delay = TaskExecutionService.usageLimitDelay(
                "You've hit your usage limit. Please try again at 12:17 PM.", now,
                Duration.ofMinutes(30), "Asia/Shanghai");

        assertEquals(Duration.ofHours(19).plusMinutes(18), delay);
    }

    @Test
    void usageLimitFallsBackWhenNoProviderTimeIsPresent() {
        assertTrue(TaskExecutionService.isUsageLimit("You've hit your usage limit."));
        assertFalse(TaskExecutionService.isUsageLimit("connection reset by peer"));
        assertEquals(Duration.ofMinutes(30), TaskExecutionService.usageLimitDelay(
                "You've hit your usage limit.", OffsetDateTime.now(), Duration.ofMinutes(30), "Asia/Shanghai"));
    }
}
