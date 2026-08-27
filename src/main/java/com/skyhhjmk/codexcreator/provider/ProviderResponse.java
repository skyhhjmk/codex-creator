package com.skyhhjmk.codexcreator.provider;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

public record ProviderResponse(JsonNode output, Usage usage, Map<String, Object> provenance,
                               String providerRequestId, String externalThreadId, String externalTurnId) {
    public record Usage(int inputTokens, int outputTokens, int totalTokens) {
        public static Usage empty() {
            return new Usage(0, 0, 0);
        }
    }
}
