package com.skyhhjmk.codexcreator.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skyhhjmk.codexcreator.security.InternalRequestVerifier;
import com.skyhhjmk.codexcreator.service.TaskExecutionService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Set;
import java.util.concurrent.CompletionStage;

@Path("/api/internal/integrations/windblog")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class InternalIntegrationResource {
    private static final Set<String> ALLOWED_EVENTS = Set.of(
            "comment.created", "link.application.created", "link.monitor.completed",
            "post.revision.updated", "post.published");

    @Inject
    InternalRequestVerifier verifier;

    @Inject
    ObjectMapper mapper;

    @Inject
    TaskExecutionService tasks;

    @POST
    @Path("/events")
    public CompletionStage<Response> event(String body,
                                            @HeaderParam("X-Codex-Client-Id") String clientId,
                                            @HeaderParam("X-Codex-Timestamp") String timestamp,
                                            @HeaderParam("X-Codex-Nonce") String nonce,
                                            @HeaderParam("X-Codex-Body-SHA256") String bodyDigest,
                                            @HeaderParam("X-Codex-Signature") String signature) {
        if (!verifier.verify(clientId, timestamp, nonce, bodyDigest, signature, body)) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                    Response.status(Response.Status.UNAUTHORIZED).entity(java.util.Map.of("error", "invalid signature")).build());
        }
        try {
            JsonNode event = mapper.readTree(body);
            String eventType = event.path("eventType").asText("");
            if (!ALLOWED_EVENTS.contains(eventType)) {
                return java.util.concurrent.CompletableFuture.completedFuture(
                        Response.status(Response.Status.BAD_REQUEST).entity(java.util.Map.of("error", "event type is not allowlisted")).build());
            }
            String profileId = event.path("profileId").asText("codex-default");
            RuntimeInferenceRequest request = new RuntimeInferenceRequest(
                    event.path("operation").asText(operationFor(eventType)), profileId,
                    event.get("input"), event.path("idempotencyKey").asText(nonce),
                    event.path("traceId").asText(nonce), event.path("promptVersion").asText("1"), false);
            return tasks.infer(request).thenApply(result -> Response.accepted(result).build());
        } catch (Exception exception) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                    Response.status(Response.Status.BAD_REQUEST).entity(java.util.Map.of("error", "invalid event payload")).build());
        }
    }

    private static String operationFor(String eventType) {
        return switch (eventType) {
            case "comment.created" -> "moderate";
            case "link.application.created", "link.monitor.completed" -> "moderate";
            case "post.revision.updated", "post.published" -> "summarize";
            default -> "assistant";
        };
    }
}
