package com.skyhhjmk.codexcreator.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skyhhjmk.codexcreator.domain.ModelProfile;
import com.skyhhjmk.codexcreator.runtime.AppServerTurnResult;
import com.skyhhjmk.codexcreator.runtime.CodexAppServerSupervisor;
import com.skyhhjmk.codexcreator.service.CodexRuntimePersistence;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@ApplicationScoped
public class CodexAppServerProviderAdapter implements ProviderAdapter {
    @Inject
    CodexAppServerSupervisor supervisor;

    @Inject
    ObjectMapper mapper;

    @Inject
    CodexRuntimePersistence persistence;

    @ConfigProperty(name = "codex.creator.article.reasoning-effort", defaultValue = "high")
    String articleReasoningEffort;

    @ConfigProperty(name = "codex.creator.topic.reasoning-effort", defaultValue = "medium")
    String topicReasoningEffort;

    @Override
    public boolean supports(String providerType) {
        return "CODEX_APP_SERVER".equalsIgnoreCase(providerType);
    }

    @Override
    public CompletableFuture<ProviderResponse> infer(ProviderRequest request) {
        ModelProfile profile = request.profile();
        String reasoningEffort = effectiveReasoningEffort(request.operation(), profile);
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
                    Long threadDbId = persistence.threadStarted(request.taskId(), profile, threadId);
                    ObjectNode turnParams = mapper.createObjectNode();
                    turnParams.put("threadId", threadId);
                    turnParams.put("approvalPolicy", "never");
                    turnParams.put("effort", reasoningEffort);
                    turnParams.put("personality", "pragmatic");
                    ObjectNode sandboxPolicy = turnParams.putObject("sandboxPolicy");
                    sandboxPolicy.put("type", "readOnly");
                    sandboxPolicy.put("networkAccess", true);
                    turnParams.set("outputSchema", outputSchema(request.operation()));
                    ArrayNode input = turnParams.putArray("input");
                    ObjectNode item = input.addObject();
                    item.put("type", "text");
                    item.put("text", "Operation: " + request.operation() + "\nInput: " + request.input());
                    return supervisor.request("turn/start", turnParams)
                            .thenCompose(turnResult -> {
                                String turnId = turnResult.path("turn").path("id").asText("");
                                if (turnId.isBlank()) {
                                    return CompletableFuture.failedFuture(new IllegalStateException("app-server did not return turn id"));
                                }
                                persistence.turnStarted(request.taskId(), threadDbId, turnId, request.input());
                                return supervisor.awaitTurnCompletion(threadId, turnId)
                                        .thenApply(completed -> {
                                            persistence.turnCompleted(turnId, completed.completed());
                                            return response(completed, request.operation(), reasoningEffort,
                                                    threadId, turnId);
                                        })
                                        .whenComplete((ignored, error) -> {
                                            if (error != null) persistence.turnFailed(turnId, error);
                                        });
                            });
                });
    }

    private ProviderResponse response(AppServerTurnResult turnResult, String operation, String reasoningEffort,
                                      String threadId, String turnId) {
        JsonNode completed = turnResult.completed();
        JsonNode turn = completed.path("turn");
        if (isFailedTurn(turn)) {
            String detail = turn.path("error").path("message").asText("");
            if (detail.isBlank()) detail = turn.path("error").asText("");
            if (detail.isBlank()) detail = "app-server turn failed";
            throw new IllegalStateException(detail);
        }
        JsonNode output = turn.has("output") ? turn.get("output") : extractAgentOutput(turnResult);
        if (output == null || output.isMissingNode() || output.isNull()) output = completed;
        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("provider", "codex-app-server");
        provenance.put("threadId", threadId);
        provenance.put("turnId", turnId);
        provenance.put("experimentalApi", false);
        provenance.put("operation", operation);
        provenance.put("reasoningEffort", reasoningEffort);
        provenance.put("webSearchItems", webSearchEvidence(turnResult, threadId, turnId));
        return new ProviderResponse(output, ProviderResponse.Usage.empty(), provenance, null, threadId, turnId);
    }

    private ArrayNode webSearchEvidence(AppServerTurnResult turnResult, String threadId, String turnId) {
        ArrayNode evidence = mapper.createArrayNode();
        for (JsonNode event : turnResult.completedItems()) {
            JsonNode item = event.path("params").path("item");
            if (!"webSearch".equals(item.path("type").asText("")) || evidence.size() >= 40) continue;
            ObjectNode entry = evidence.addObject();
            copyText(item, entry, "id");
            copyText(item, entry, "type");
            copyText(item, entry, "query");
            copyText(item, entry, "title");
            copyText(item, entry, "url");
            copyText(item, entry, "pageUrl");
            JsonNode action = item.path("action");
            if (action.isObject()) {
                ObjectNode actionCopy = entry.putObject("action");
                copyText(action, actionCopy, "type");
                copyText(action, actionCopy, "url");
                copyText(action, actionCopy, "query");
            }
            entry.put("threadId", event.path("params").path("threadId").asText(threadId));
            entry.put("turnId", event.path("params").path("turnId").asText(turnId));
            entry.put("capturedAt", event.path("_capturedAt").asText(Instant.now().toString()));
        }
        return evidence;
    }

    private static void copyText(JsonNode source, ObjectNode target, String field) {
        JsonNode value = source.get(field);
        if (value != null && value.isTextual() && !value.asText().isBlank()) {
            target.put(field, value.asText());
        }
    }

    private JsonNode extractAgentOutput(AppServerTurnResult turnResult) {
        for (int index = turnResult.completedItems().size() - 1; index >= 0; index--) {
            JsonNode item = turnResult.completedItems().get(index).path("params").path("item");
            if (!"agentMessage".equals(item.path("type").asText(""))) continue;
            JsonNode text = item.get("text");
            if (text == null || !text.isTextual()) continue;
            try {
                return mapper.readTree(text.asText());
            } catch (Exception ignored) {
                return text;
            }
        }
        return null;
    }

    private JsonNode outputSchema(String operation) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        if ("topic".equalsIgnoreCase(operation)) {
            ObjectNode topics = properties.putObject("topics");
            topics.put("type", "array");
            topics.put("maxItems", 50);
            ObjectNode topic = topics.putObject("items");
            topic.put("type", "object");
            ObjectNode topicProperties = topic.putObject("properties");
            topicProperties.putObject("seedId").put("type", "integer").put("minimum", 1);
            topicProperties.putObject("title").put("type", "string").put("maxLength", 160);
            topicProperties.putObject("rationale").put("type", "string").put("maxLength", 2_000);
            ObjectNode keywords = topicProperties.putObject("keywords");
            keywords.put("type", "array").put("maxItems", 12);
            keywords.putObject("items").put("type", "string").put("maxLength", 160);
            topicProperties.putObject("recommendation").put("type", "string");
            ObjectNode sources = topicProperties.putObject("sources");
            sources.put("type", "array").put("maxItems", 20);
            sourceSchema(sources.putObject("items"));
            topic.putArray("required").add("seedId").add("title").add("rationale").add("keywords")
                    .add("recommendation").add("sources");
            topic.put("additionalProperties", false);
            schema.putArray("required").add("topics");
        } else if ("article".equalsIgnoreCase(operation)) {
            properties.putObject("title").put("type", "string").put("maxLength", 160);
            properties.putObject("summary").put("type", "string").put("maxLength", 2_000);
            properties.putObject("editorialThesis").put("type", "string").put("maxLength", 1_000);
            ObjectNode categoryId = properties.putObject("categoryId");
            categoryId.putArray("type").add("integer").add("null");
            categoryId.put("minimum", 1);
            properties.putObject("contentMarkdown").put("type", "string").put("maxLength", 100_000);
            ObjectNode sources = properties.putObject("sources");
            sources.put("type", "array").put("maxItems", 20);
            sourceSchema(sources.putObject("items"));
            schema.putArray("required").add("title").add("summary").add("editorialThesis").add("categoryId")
                    .add("contentMarkdown").add("sources");
        } else {
            return mapper.createObjectNode();
        }
        schema.put("additionalProperties", false);
        return schema;
    }

    private static void sourceSchema(ObjectNode source) {
        source.put("type", "object");
        ObjectNode properties = source.putObject("properties");
        properties.putObject("url").put("type", "string").put("maxLength", 2_048);
        properties.putObject("title").put("type", "string").put("maxLength", 500);
        source.putArray("required").add("url").add("title");
        source.put("additionalProperties", false);
    }

    private String effectiveReasoningEffort(String operation, ModelProfile profile) {
        String configured = "article".equalsIgnoreCase(operation) ? articleReasoningEffort
                : "topic".equalsIgnoreCase(operation) ? topicReasoningEffort : profile.reasoningEffort;
        return configured == null || configured.isBlank() ? "medium" : configured.trim().toLowerCase();
    }

    private static boolean isFailedTurn(JsonNode turn) {
        if (turn == null || turn.isMissingNode() || turn.isNull()) return false;
        if (turn.has("error") && !turn.path("error").isNull()) return true;
        String status = turn.path("status").asText("").toLowerCase();
        return "failed".equals(status) || "error".equals(status);
    }
}
