package com.skyhhjmk.codexcreator.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;

public record RuntimeInferenceResponse(Long taskId, String status, String operation,
                                       String profileId, JsonNode output, JsonNode usage,
                                       JsonNode provenance, String errorCode,
                                       String errorMessage, String traceId,
                                       OffsetDateTime createdAt, OffsetDateTime completedAt) {
}
