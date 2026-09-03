package com.skyhhjmk.codexcreator.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skyhhjmk.codexcreator.security.InternalRequestVerifier;
import com.skyhhjmk.codexcreator.service.ArticleJobService;
import com.skyhhjmk.codexcreator.service.TopicAutomationService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/** HMAC-only command boundary used by WindBlog's administrator proxy. */
@Path("/api/internal/integrations/windblog/topic-automation")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class InternalTopicAutomationResource {
    @Inject
    InternalRequestVerifier verifier;

    @Inject
    ObjectMapper mapper;

    @Inject
    TopicAutomationService topics;

    @Inject
    ArticleJobService articles;

    @POST
    public Response command(String body,
                            @jakarta.ws.rs.HeaderParam("X-Codex-Client-Id") String clientId,
                            @jakarta.ws.rs.HeaderParam("X-Codex-Timestamp") String timestamp,
                            @jakarta.ws.rs.HeaderParam("X-Codex-Nonce") String nonce,
                            @jakarta.ws.rs.HeaderParam("X-Codex-Body-SHA256") String bodyDigest,
                            @jakarta.ws.rs.HeaderParam("X-Codex-Signature") String signature) {
        if (!verifier.verify(clientId, timestamp, nonce, bodyDigest, signature, body)) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("error", "invalid signature")).build();
        }
        try {
            JsonNode request = mapper.readTree(body == null ? "{}" : body);
            String command = request.path("command").asText("");
            JsonNode payload = request.path("payload");
            String actorId = request.path("actorId").asText("WIND_BLOG");
            String traceId = request.path("traceId").asText(nonce);
            return dispatch(command, payload, actorId, traceId);
        } catch (Exception exception) {
            return error(Response.Status.BAD_REQUEST, message(exception));
        }
    }

    private Response dispatch(String command, JsonNode payload, String actorId, String traceId) {
        return switch (command) {
            case "settings.read" -> ok(topics.settingsView());
            case "settings.update" -> ok(topics.updateSettings(payload, actorId, traceId));
            case "seeds.list" -> ok(Map.of("items", topics.seedsView()));
            case "seeds.upsert" -> ok(topics.upsertSeed(optionalId(payload, "id"), payload, actorId, traceId));
            case "seeds.delete" -> {
                topics.deleteSeed(requiredId(payload, "id"), actorId, traceId);
                yield ok(Map.of("success", true));
            }
            case "runs.start" -> ok(topics.startManual(text(payload, "idempotencyKey", ""), traceId, actorId));
            case "runs.read" -> ok(topics.runView(requiredId(payload, "id")));
            case "runs.list" -> ok(topics.listRuns(integer(payload, "page", 1), integer(payload, "pageSize", 20)));
            case "topics.list" -> ok(topics.listTopics(text(payload, "status", ""),
                    integer(payload, "page", 1), integer(payload, "pageSize", 20)));
            case "topics.read" -> ok(topics.topicView(requiredId(payload, "id")));
            case "topics.review" -> ok(topics.reviewTopic(requiredId(payload, "id"),
                    text(payload, "decision", ""), text(payload, "note", ""), actorId, traceId));
            case "article.start" -> ok(articles.start(payload, actorId, traceId));
            case "article.read" -> ok(articles.read(requiredId(payload, "id")));
            case "article.acknowledge" -> ok(articles.acknowledgeDraft(requiredId(payload, "id"),
                    requiredId(payload, "postId"), actorId, traceId));
            default -> error(Response.Status.BAD_REQUEST, "unsupported topic automation command");
        };
    }

    private Response ok(Object value) {
        return Response.ok(Map.of("success", true, "data", value)).build();
    }

    private Response error(Response.Status status, String message) {
        return Response.status(status).entity(Map.of("success", false,
                "message", message == null || message.isBlank() ? "invalid request" : message)).build();
    }

    private static Long optionalId(JsonNode payload, String field) {
        JsonNode value = payload == null ? null : payload.get(field);
        if (value == null || value.isNull()) return null;
        return requiredId(payload, field);
    }

    private static Long requiredId(JsonNode payload, String field) {
        JsonNode value = payload == null ? null : payload.get(field);
        if (value == null || !value.canConvertToLong() || value.asLong() <= 0) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.asLong();
    }

    private static int integer(JsonNode payload, String field, int fallback) {
        JsonNode value = payload == null ? null : payload.get(field);
        if (value == null || value.isNull()) return fallback;
        if (!value.canConvertToInt()) throw new IllegalArgumentException(field + " must be an integer");
        return value.asInt();
    }

    private static String text(JsonNode payload, String field, String fallback) {
        JsonNode value = payload == null ? null : payload.get(field);
        if (value == null || value.isNull()) return fallback;
        if (!value.isTextual()) throw new IllegalArgumentException(field + " must be text");
        return value.asText();
    }

    private static String message(Exception exception) {
        Throwable current = exception;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
