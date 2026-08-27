package com.skyhhjmk.codexcreator.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.skyhhjmk.codexcreator.domain.ModelProfile;

public record ProviderRequest(String operation, ModelProfile profile, JsonNode input, String traceId, Long taskId) {
}
