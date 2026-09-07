package com.skyhhjmk.codexcreator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skyhhjmk.codexcreator.domain.ModelProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class ModelCatalogService {
    private static final Set<String> REASONING_EFFORTS = Set.of("low", "medium", "high", "xhigh");
    @Inject
    ObjectMapper mapper;

    public List<Map<String, Object>> options(String operation) {
        return ModelProfile.<ModelProfile>find("enabled = true order by displayName asc").list().stream()
                .filter(profile -> supports(profile, operation))
                .map(this::view)
                .toList();
    }

    public String resolve(String requested, String fallback, String operation) {
        String profileId = requested == null || requested.isBlank() ? fallback : requested.trim();
        ModelProfile profile = ModelProfile.findById(profileId);
        if (profile == null || !profile.enabled || !supports(profile, operation)) {
            throw new IllegalArgumentException("model profile is unavailable for " + operation + ": " + profileId);
        }
        return profile.profileId;
    }

    public String resolveReasoningEffort(String requested, String fallback) {
        String effort = requested == null || requested.isBlank()
                ? (fallback == null || fallback.isBlank() ? "high" : fallback.trim().toLowerCase())
                : requested.trim().toLowerCase();
        if (!REASONING_EFFORTS.contains(effort)) {
            throw new IllegalArgumentException("reasoningEffort must be one of low, medium, high, xhigh");
        }
        return effort;
    }

    private boolean supports(ModelProfile profile, String operation) {
        try {
            JsonNode values = mapper.readTree(profile.allowedOperations == null ? "[]" : profile.allowedOperations);
            for (JsonNode value : values) {
                if (value.isTextual() && value.asText().equalsIgnoreCase(operation)) return true;
            }
        } catch (Exception ignored) {
            // Invalid profile JSON is treated as unavailable.
        }
        return false;
    }

    private Map<String, Object> view(ModelProfile profile) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("profileId", profile.profileId);
        value.put("modelId", profile.modelId);
        value.put("displayName", profile.displayName);
        value.put("reasoningEffort", profile.reasoningEffort == null ? "" : profile.reasoningEffort);
        value.put("isDefault", "codex-default".equals(profile.profileId));
        return value;
    }
}
