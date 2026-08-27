package com.skyhhjmk.codexcreator.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skyhhjmk.codexcreator.domain.ModelProfile;
import com.skyhhjmk.codexcreator.runtime.CodexAppServerSupervisor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class CodexAppServerProviderAdapter implements ProviderAdapter {
    @Inject
    CodexAppServerSupervisor supervisor;

    @Inject
    ObjectMapper mapper;

    @Override
    public boolean supports(String providerType) {
        return "CODEX_APP_SERVER".equalsIgnoreCase(providerType);
    }

    @Override
    public CompletableFuture<ProviderResponse> infer(ProviderRequest request) {
        ModelProfile profile = request.profile();
        ObjectNode threadParams = mapper.createObjectNode();
        if (profile.modelId != null && !profile.modelId.isBlank() && !"auto".equalsIgnoreCase(profile.modelId)) {
            threadParams.put("model", profile.modelId);
        }
        return supervisor.request("thread/start", threadParams)
                .thenCompose(threadResult -> {
                    String threadId = threadResult.path("thread").path("id").asText("");
                    if (threadId.isBlank()) {
                        return CompletableFuture.failedFuture(new IllegalStateException("app-server did not return thread id"));
                    }
                    ObjectNode turnParams = mapper.createObjectNode();
                    turnParams.put("threadId", threadId);
                    ArrayNode input = turnParams.putArray("input");
                    ObjectNode item = input.addObject();
                    item.put("type", "text");
                    item.put("text", "Operation: " + request.operation() + "\nInput: " + request.input());
                    return supervisor.request("turn/start", turnParams)
                            .thenCompose(turnResult -> {
                                String turnId = turnResult.path("turn").path("id").asText("");
                                return supervisor.awaitTurnCompletion(threadId, turnId)
                                        .thenApply(completed -> response(completed, threadId, turnId));
                            });
                });
    }

    private ProviderResponse response(JsonNode completed, String threadId, String turnId) {
        JsonNode turn = completed.path("turn");
        JsonNode output = turn.has("output") ? turn.get("output") : completed;
        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("provider", "codex-app-server");
        provenance.put("threadId", threadId);
        provenance.put("turnId", turnId);
        provenance.put("experimentalApi", false);
        return new ProviderResponse(output, ProviderResponse.Usage.empty(), provenance, null, threadId, turnId);
    }
}
