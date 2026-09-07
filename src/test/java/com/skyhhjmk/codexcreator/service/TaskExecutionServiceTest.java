package com.skyhhjmk.codexcreator.service;

import org.junit.jupiter.api.Test;

import com.skyhhjmk.codexcreator.domain.TaskAttempt;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.Map;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskExecutionServiceTest {

    @Test
    void executionAttemptViewAllowsAnIncompleteRunningAttempt() throws Exception {
        TaskExecutionService service = new TaskExecutionService();
        TaskAttempt attempt = new TaskAttempt();
        attempt.attemptNumber = 1;
        attempt.status = "RUNNING";
        attempt.startedAt = OffsetDateTime.now();
        attempt.completedAt = null;

        Method method = TaskExecutionService.class.getDeclaredMethod("attemptView", TaskAttempt.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> view = (Map<String, Object>) method.invoke(service, attempt);

        assertEquals("RUNNING", view.get("status"));
        assertNull(view.get("completedAt"));
    }

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
