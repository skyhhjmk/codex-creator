package com.skyhhjmk.codexcreator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TopicAutomationServiceTest {
    @Test
    void serializesSeedSnapshotWithoutRecordReflection() throws Exception {
        TopicAutomationService service = new TopicAutomationService();
        service.mapper = new ObjectMapper();

        String snapshot = service.seedSnapshotJson(List.of(
                new TopicAutomationService.SeedSnapshot(7L, "AI", "AI regulation", "zh-CN", null)));

        JsonNode seed = service.mapper.readTree(snapshot).get(0);
        assertEquals(7L, seed.path("id").asLong());
        assertEquals("AI regulation", seed.path("query").asText());
        assertFalse(seed.has("region"));
    }
}
