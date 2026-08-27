package com.skyhhjmk.codexcreator.api;

import com.fasterxml.jackson.databind.JsonNode;

public record RuntimeInferenceRequest(String operation, String profileId, JsonNode input,
                                      String idempotencyKey, String traceId,
                                      String promptVersion) {
}
