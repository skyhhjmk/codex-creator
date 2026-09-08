package com.skyhhjmk.codexcreator.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skyhhjmk.codexcreator.domain.*;
import com.skyhhjmk.codexcreator.runtime.CodexAppServerSupervisor;
import com.skyhhjmk.codexcreator.service.ArticleJobService;
import com.skyhhjmk.codexcreator.service.TopicAutomationService;
import com.skyhhjmk.codexcreator.service.TestServerService;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.CompletionStage;

@Path("/api/admin")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Codex Creator Admin")
public class AdminResource {
    @Inject
    ObjectMapper mapper;

    @Inject
    CodexAppServerSupervisor supervisor;

    @Inject
    TopicAutomationService topicAutomation;

    @Inject
    ArticleJobService articleJobs;
    @Inject TestServerService testServers;

    @ConfigProperty(name = "codex.creator.admin-token", defaultValue = "")
    Optional<String> adminToken;

    @ConfigProperty(name = "codex.creator.mcp-bearer-token", defaultValue = "")
    Optional<String> mcpToken;

    @ConfigProperty(name = "codex.creator.mcp.write-approval-required", defaultValue = "false")
    boolean mcpWriteApprovalRequired;

    @GET
    @Path("/auth/status")
    @Operation(summary = "Report whether the internal admin credential is configured")
    public Map<String, Object> authStatus() {
        return Map.of("service", "codex-creator", "adminTokenConfigured", adminToken.isPresent() && !adminToken.get().isBlank(),
                "mcpTokenConfigured", mcpToken.isPresent() && !mcpToken.get().isBlank());
    }

    @GET
    @Path("/providers")
    public List<Map<String, Object>> providers() {
        return ProviderConfig.<ProviderConfig>listAll().stream().map(this::providerView).toList();
    }

    @POST
    @Path("/providers")
    @Transactional
    public Response createProvider(Map<String, Object> payload) {
        if (payload == null || blank(payload.get("name")) || blank(payload.get("providerType"))) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "name and providerType are required")).build();
        }
        ProviderConfig provider = new ProviderConfig();
        provider.name = String.valueOf(payload.get("name"));
        provider.providerType = String.valueOf(payload.get("providerType"));
        provider.endpoint = value(payload.get("endpoint"));
        provider.secretRef = value(payload.get("secretRef"));
        provider.config = json(payload.getOrDefault("config", Map.of()));
        provider.enabled = Boolean.parseBoolean(String.valueOf(payload.getOrDefault("enabled", false)));
        provider.createdAt = OffsetDateTime.now();
        provider.updatedAt = provider.createdAt;
        provider.persist();
        return Response.status(Response.Status.CREATED).entity(providerView(provider)).build();
    }

    @PUT
    @Path("/providers/{id}")
    @Transactional
    public Response updateProvider(@PathParam("id") Long id, Map<String, Object> payload) {
        ProviderConfig provider = ProviderConfig.findById(id);
        if (provider == null) return Response.status(Response.Status.NOT_FOUND).build();
        if (payload != null) {
            if (payload.containsKey("name")) provider.name = value(payload.get("name"));
            if (payload.containsKey("providerType")) provider.providerType = value(payload.get("providerType"));
            if (payload.containsKey("endpoint")) provider.endpoint = value(payload.get("endpoint"));
            if (payload.containsKey("secretRef")) provider.secretRef = value(payload.get("secretRef"));
            if (payload.containsKey("config")) provider.config = json(payload.get("config"));
            if (payload.containsKey("enabled")) provider.enabled = Boolean.parseBoolean(String.valueOf(payload.get("enabled")));
            provider.updatedAt = OffsetDateTime.now();
        }
        return Response.ok(providerView(provider)).build();
    }

    @DELETE
    @Path("/providers/{id}")
    @Transactional
    public Response deleteProvider(@PathParam("id") Long id) {
        ProviderConfig provider = ProviderConfig.findById(id);
        if (provider == null) return Response.status(Response.Status.NOT_FOUND).build();
        provider.delete();
        return Response.noContent().build();
    }

    @GET
    @Path("/models")
    public List<Map<String, Object>> models() {
        return ModelProfile.<ModelProfile>listAll().stream().map(this::modelView).toList();
    }

    @POST
    @Path("/models/discover")
    public CompletionStage<Response> discoverModels() {
        return supervisor.listModels()
                .thenApply(models -> Response.ok(models).build())
                .exceptionally(error -> Response.status(Response.Status.SERVICE_UNAVAILABLE)
                        .entity(Map.of("error", "Codex app-server model discovery is unavailable")).build());
    }

    @GET
    @Path("/workflows")
    public Map<String, Object> workflows() {
        return Map.of("operations", List.of("summarize", "moderate", "translate", "slug",
                "assistant", "topic", "article", "embedding", "category.read", "category.write",
                "tag.read", "tag.write", "media.search", "media.upload"), "experimentalApi", false);
    }

    @GET
    @Path("/tasks")
    public List<Map<String, Object>> tasks(@QueryParam("status") String status) {
        String query = status == null || status.isBlank() ? "order by createdAt desc" : "status = ?1 order by createdAt desc";
        List<AutomationTask> tasks = status == null || status.isBlank()
                ? AutomationTask.find(query).page(0, 100).list()
                : AutomationTask.find(query, status).page(0, 100).list();
        return tasks.stream().map(this::taskView).toList();
    }

    @GET
    @Path("/knowledge")
    public List<Map<String, Object>> knowledge() {
        return KnowledgeDocument.<KnowledgeDocument>listAll().stream().map(document -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", document.id);
            view.put("title", document.title);
            view.put("sourceUri", document.sourceUri == null ? "" : document.sourceUri);
            view.put("checksum", document.checksum);
            view.put("status", document.status);
            return view;
        }).toList();
    }

    @GET
    @Path("/topics")
    public List<Map<String, Object>> topics() {
        return AiTopic.<AiTopic>listAll().stream().map(topic -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", topic.id);
            view.put("title", topic.title);
            view.put("rationale", topic.rationale == null ? "" : topic.rationale);
            view.put("recommendation", topic.recommendation);
            view.put("status", topic.status);
            return view;
        }).toList();
    }

    @GET @Path("/test-servers") public List<Map<String,Object>> testServers() { return testServers.list(); }
    @GET @Path("/test-servers/setup-guide") public Map<String,Object> testServerSetupGuide() { return testServers.setupGuide(); }
    @POST @Path("/test-servers") @Transactional public Response createTestServer(Map<String,Object> payload) { return Response.status(Response.Status.CREATED).entity(testServers.save(null,payload)).build(); }
    @PUT @Path("/test-servers/{id}") @Transactional public Map<String,Object> updateTestServer(@PathParam("id") Long id, Map<String,Object> payload) { return testServers.save(id,payload); }
    @DELETE @Path("/test-servers/{id}") @Transactional public Response deleteTestServer(@PathParam("id") Long id) { testServers.delete(id); return Response.noContent().build(); }

    @GET
    @Path("/topic-automation")
    public Map<String, Object> topicAutomation() {
        return Map.of("settings", topicAutomation.settingsView(), "seeds", topicAutomation.seedsView());
    }

    @GET
    @Path("/topic-seeds")
    public List<Map<String, Object>> topicSeeds() {
        return topicAutomation.seedsView();
    }

    @POST
    @Path("/topic-seeds")
    @Transactional
    public Map<String, Object> createTopicSeed(JsonNode payload) {
        return topicAutomation.upsertSeed(null, payload, "CODEX_ADMIN", java.util.UUID.randomUUID().toString());
    }

    @PUT
    @Path("/topic-seeds/{id}")
    @Transactional
    public Map<String, Object> updateTopicSeed(@PathParam("id") Long id, JsonNode payload) {
        return topicAutomation.upsertSeed(id, payload, "CODEX_ADMIN", java.util.UUID.randomUUID().toString());
    }

    @DELETE
    @Path("/topic-seeds/{id}")
    @Transactional
    public Response deleteTopicSeed(@PathParam("id") Long id) {
        topicAutomation.deleteSeed(id, "CODEX_ADMIN", java.util.UUID.randomUUID().toString());
        return Response.noContent().build();
    }

    @POST
    @Path("/topic-runs")
    public Map<String, Object> startTopicRun(Map<String, Object> payload) {
        String key = payload == null ? "" : value(payload.get("idempotencyKey"));
        String profileId = payload == null ? "" : value(payload.get("profileId"));
        return topicAutomation.startManual(key, java.util.UUID.randomUUID().toString(), "CODEX_ADMIN", profileId);
    }

    @GET
    @Path("/topic-runs/{id}")
    public Map<String, Object> topicRun(@PathParam("id") Long id) {
        return topicAutomation.runView(id);
    }

    @GET
    @Path("/topic-runs")
    public Map<String, Object> topicRuns(@QueryParam("page") @DefaultValue("1") int page,
                                         @QueryParam("pageSize") @DefaultValue("20") int pageSize) {
        return topicAutomation.listRuns(page, pageSize);
    }

    @GET
    @Path("/topics/{id}")
    public Map<String, Object> topic(@PathParam("id") Long id) {
        return topicAutomation.topicView(id);
    }

    @POST
    @Path("/topics/{id}/review")
    public Map<String, Object> reviewTopic(@PathParam("id") Long id, Map<String, Object> payload) {
        String decision = payload == null ? "" : value(payload.get("decision"));
        String note = payload == null ? "" : value(payload.get("note"));
        return topicAutomation.reviewTopic(id, decision, note, "CODEX_ADMIN", java.util.UUID.randomUUID().toString());
    }

    @POST
    @Path("/article-jobs")
    public Map<String, Object> startArticleJob(JsonNode payload) {
        return articleJobs.start(payload, "CODEX_ADMIN", java.util.UUID.randomUUID().toString());
    }

    @GET
    @Path("/article-jobs/{id}")
    public Map<String, Object> articleJob(@PathParam("id") Long id) {
        return articleJobs.read(id);
    }

    @GET
    @Path("/integrations")
    public Map<String, Object> integrations() {
        return Map.of("windblog", Map.of("eventEndpoint", "/api/internal/integrations/windblog/events",
                        "signature", "HMAC-SHA256", "replayProtection", "timestamp+nonce"),
                "mcp", Map.of("endpoint", "/mcp", "writeApprovalRequired", mcpWriteApprovalRequired));
    }

    @GET
    @Path("/runtime/status")
    public Map<String, Object> runtimeStatus() {
        return supervisor.status();
    }

    private Map<String, Object> providerView(ProviderConfig provider) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", provider.id);
        view.put("name", provider.name);
        view.put("providerType", provider.providerType);
        view.put("endpoint", provider.endpoint == null ? "" : provider.endpoint);
        view.put("secretRef", provider.secretRef == null ? "" : provider.secretRef);
        view.put("enabled", provider.enabled);
        view.put("config", parse(provider.config));
        return view;
    }

    private Map<String, Object> modelView(ModelProfile model) {
        return Map.of("profileId", model.profileId, "vendor", model.vendor, "modelId", model.modelId,
                "displayName", model.displayName, "reasoningEffort", model.reasoningEffort == null ? "" : model.reasoningEffort,
                "capabilities", parse(model.capabilities), "allowedOperations", parse(model.allowedOperations),
                "enabled", model.enabled);
    }

    private Map<String, Object> taskView(AutomationTask task) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", task.id); view.put("operation", task.operation); view.put("status", task.status);
        view.put("profileId", task.profile == null ? null : task.profile.profileId);
        view.put("idempotencyKey", task.idempotencyKey); view.put("traceId", task.traceId);
        view.put("attemptCount", task.attemptCount); view.put("errorCode", task.errorCode);
        view.put("createdAt", task.createdAt); view.put("completedAt", task.completedAt);
        return view;
    }

    private JsonNode parse(String value) {
        try { return mapper.readTree(value == null ? "{}" : value); }
        catch (Exception ignored) { return mapper.createObjectNode(); }
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value == null ? Map.of() : value); }
        catch (Exception exception) { throw new BadRequestException("invalid JSON configuration", exception); }
    }

    private static String value(Object value) { return value == null ? null : String.valueOf(value); }
    private static boolean blank(Object value) { return value == null || String.valueOf(value).isBlank(); }
}
