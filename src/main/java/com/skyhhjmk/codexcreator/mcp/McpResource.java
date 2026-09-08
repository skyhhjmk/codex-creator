package com.skyhhjmk.codexcreator.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skyhhjmk.codexcreator.domain.ModelProfile;
import com.skyhhjmk.codexcreator.integration.WindBlogContentClient;
import com.skyhhjmk.codexcreator.service.TopicAutomationService;
import com.skyhhjmk.codexcreator.service.TestServerService;
import com.skyhhjmk.codexcreator.service.ArticleEvidenceService;
import com.skyhhjmk.codexcreator.service.WikimediaImageService;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Private MCP surface exposed to the Codex app-server process. */
@Path("/mcp")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Blocking
public class McpResource {
    private static final long IMAGE_REVIEW_TTL_MILLIS = 30 * 60 * 1000L;
    private final ConcurrentHashMap<String, ImageReview> reviewedCommonsImages = new ConcurrentHashMap<>();
    private static final List<Tool> TOOLS = List.of(
            new Tool("windblog.read_topic", "Read an AI topic suggestion", "topic", "topic", false),
            new Tool("windblog.list_categories", "List WindBlog categories", "category.read", "category.read", false),
            new Tool("windblog.create_category", "Create a WindBlog category", "category.create", "category.write", true),
            new Tool("windblog.update_category", "Update a WindBlog category", "category.update", "category.write", true),
            new Tool("windblog.list_tags", "List WindBlog tags", "tag.read", "tag.read", false),
            new Tool("windblog.create_tag", "Create a WindBlog tag", "tag.create", "tag.write", true),
            new Tool("windblog.update_tag", "Update a WindBlog tag", "tag.update", "tag.write", true),
            new Tool("windblog.search_wikimedia_images", "Search reusable Wikimedia Commons images. Use inspect_wikimedia_image before deciding to import one.", "media.search", "media.search", false),
            new Tool("windblog.inspect_wikimedia_image", "Return a Wikimedia Commons candidate image and its attribution for visual review before import.", "media.inspect", "media.inspect", false),
            new Tool("windblog.import_wikimedia_image", "Import one visually reviewed Wikimedia Commons image for the current article job and preserve its attribution.", "media.import", "media.import", true),
            new Tool("windblog.upload_image", "Upload generated image bytes for the current article job to WindBlog", "media.upload", "media.upload", true),
            new Tool("windblog.run_test_server_command", "Run one tutorial verification command on a server explicitly assigned to the article job", "test-server.execute", "test-server.execute", false)
    );

    @Inject
    ObjectMapper mapper;

    @ConfigProperty(name = "codex.creator.mcp-bearer-token", defaultValue = "")
    java.util.Optional<String> bearerToken;

    @Inject
    TopicAutomationService topicAutomation;

    @Inject
    WindBlogContentClient windBlogContentClient;

    @Inject TestServerService testServers;
    @Inject ArticleEvidenceService articleEvidence;
    @Inject WikimediaImageService wikimediaImages;

    @ConfigProperty(name = "codex.creator.mcp.write-approval-required", defaultValue = "false")
    boolean writeApprovalRequired;

    @ConfigProperty(name = "codex.creator.default-profile-id", defaultValue = "codex-default")
    String defaultProfileId;

    @POST
    public Response post(String body, @HeaderParam("Authorization") String authorization) {
        if (!authorized(authorization)) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .header("WWW-Authenticate", "Bearer").build();
        }
        try {
            JsonNode request = mapper.readTree(body == null ? "{}" : body);
            String method = request.path("method").asText("");
            JsonNode id = request.get("id");
            return switch (method) {
                case "initialize" -> ok(id, initializeResult());
                case "notifications/initialized" -> Response.noContent().build();
                case "tools/list" -> ok(id, toolsList());
                case "tools/call" -> call(id, request.path("params"));
                default -> error(id, -32601, "method not found");
            };
        } catch (Exception exception) {
            return error(null, -32700, "invalid JSON-RPC request");
        }
    }

    @GET
    public Response get() {
        return Response.status(Response.Status.METHOD_NOT_ALLOWED).header("Allow", "POST").build();
    }

    private Response call(JsonNode id, JsonNode params) {
        String name = params.path("name").asText("");
        Tool tool = TOOLS.stream().filter(candidate -> candidate.name().equals(name)).findFirst().orElse(null);
        if (tool == null) return error(id, -32602, "tool is not allowlisted");

        JsonNode rawArguments = params.get("arguments");
        JsonNode arguments = rawArguments == null || rawArguments.isNull()
                ? mapper.createObjectNode() : rawArguments;
        if (!arguments.isObject()) return error(id, -32602, "tool arguments must be an object");
        if (!profileAllows(tool.permission())) {
            return error(id, -32003, "tool is disabled for the codex-default profile");
        }
        if (tool.write() && writeApprovalRequired && !arguments.path("approved").asBoolean(false)) {
            return error(id, -32002, "write tool requires explicit approved=true");
        }

        if ("windblog.read_topic".equals(name)) {
            return readTopic(id, arguments);
        }
        if ("windblog.run_test_server_command".equals(name)) return runTestServerCommand(id, arguments);
        if ("windblog.upload_image".equals(name)) return uploadImage(id, arguments);
        if ("windblog.search_wikimedia_images".equals(name)) return searchWikimediaImages(id, arguments);
        if ("windblog.inspect_wikimedia_image".equals(name)) return inspectWikimediaImage(id, arguments);
        if ("windblog.import_wikimedia_image".equals(name)) return importWikimediaImage(id, arguments);

        ObjectNode contentArguments = ((ObjectNode) arguments).deepCopy();
        contentArguments.remove("approved");
        String idempotencyKey = tool.write()
                ? "codex-mcp-" + sha256(tool.operation() + "\n" + contentArguments)
                : "codex-mcp-read-" + UUID.randomUUID();
        String traceId = "codex-mcp-" + sha256(tool.operation() + "\n" + contentArguments);
        try {
            JsonNode response = windBlogContentClient.execute(
                    tool.operation(), contentArguments, idempotencyKey, traceId);
            JsonNode data = response.path("data");
            if (data.isMissingNode() || data.isNull()) data = mapper.createObjectNode();
            return ok(id, toolResult(data));
        } catch (RuntimeException exception) {
            return error(id, -32001, safeMessage(exception.getMessage()));
        }
    }

    private Response readTopic(JsonNode id, JsonNode arguments) {
        JsonNode topicId = arguments.get("topicId");
        if (topicId == null || !topicId.canConvertToLong() || topicId.asLong() <= 0) {
            return error(id, -32602, "topicId is required");
        }
        try {
            ObjectNode data = mapper.createObjectNode();
            data.put("status", "OK");
            data.set("topic", mapper.valueToTree(topicAutomation.topicView(topicId.asLong())));
            return ok(id, toolResult(data));
        } catch (NotFoundException exception) {
            return error(id, -32004, "topic not found");
        } catch (RuntimeException exception) {
            return error(id, -32001, safeMessage(exception.getMessage()));
        }
    }

    private ObjectNode toolsList() {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode tools = result.putArray("tools");
        for (Tool tool : TOOLS) {
            ObjectNode item = tools.addObject()
                    .put("name", tool.name())
                    .put("description", tool.description());
            ObjectNode annotations = item.putObject("annotations");
            annotations.put("readOnlyHint", !tool.write());
            annotations.put("destructiveHint", false);
            annotations.put("idempotentHint", tool.write());
            item.set("inputSchema", inputSchema(tool.name()));
        }
        return result;
    }

    private ObjectNode inputSchema(String name) {
        ObjectNode schema = mapper.createObjectNode().put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        switch (name) {
            case "windblog.read_topic" -> properties.putObject("topicId").put("type", "integer");
            case "windblog.list_categories", "windblog.list_tags" -> {
                // No arguments.
            }
            case "windblog.create_category" -> {
                localizedProperty(properties, "name");
                localizedProperty(properties, "description");
                properties.putObject("slug").put("type", "string");
                properties.putObject("parentId").put("type", "integer");
                properties.putObject("approved").put("type", "boolean");
                schema.putArray("required").add("name");
            }
            case "windblog.update_category" -> {
                properties.putObject("id").put("type", "integer");
                localizedProperty(properties, "name");
                localizedProperty(properties, "description");
                properties.putObject("slug").put("type", "string");
                properties.putObject("parentId").put("type", "integer");
                properties.putObject("approved").put("type", "boolean");
                schema.putArray("required").add("id");
            }
            case "windblog.create_tag" -> {
                localizedProperty(properties, "name");
                localizedProperty(properties, "description");
                properties.putObject("slug").put("type", "string");
                properties.putObject("approved").put("type", "boolean");
                schema.putArray("required").add("name");
            }
            case "windblog.update_tag" -> {
                properties.putObject("id").put("type", "integer");
                localizedProperty(properties, "name");
                localizedProperty(properties, "description");
                properties.putObject("slug").put("type", "string");
                properties.putObject("approved").put("type", "boolean");
                schema.putArray("required").add("id");
            }
            case "windblog.upload_image" -> {
                properties.putObject("articleJobId").put("type", "integer").put("minimum", 1);
                properties.putObject("fileName").put("type", "string");
                properties.putObject("mimeType").put("type", "string")
                        .putArray("enum").add("image/png").add("image/jpeg")
                        .add("image/gif").add("image/webp");
                properties.putObject("dataBase64").put("type", "string");
                properties.putObject("approved").put("type", "boolean");
                schema.putArray("required").add("articleJobId").add("fileName").add("mimeType").add("dataBase64");
            }
            case "windblog.search_wikimedia_images" -> {
                properties.putObject("query").put("type", "string").put("maxLength", 240);
                properties.putObject("limit").put("type", "integer").put("minimum", 1).put("maximum", 8);
                schema.putArray("required").add("query");
            }
            case "windblog.inspect_wikimedia_image" -> {
                properties.putObject("articleJobId").put("type", "integer").put("minimum", 1);
                properties.putObject("title").put("type", "string").put("pattern", "^File:");
                schema.putArray("required").add("articleJobId").add("title");
            }
            case "windblog.import_wikimedia_image" -> {
                properties.putObject("articleJobId").put("type", "integer").put("minimum", 1);
                properties.putObject("title").put("type", "string").put("pattern", "^File:");
                properties.putObject("approved").put("type", "boolean");
                schema.putArray("required").add("articleJobId").add("title");
            }
            case "windblog.run_test_server_command" -> {
                properties.putObject("articleJobId").put("type", "integer");
                properties.putObject("serverId").put("type", "integer");
                properties.putObject("command").put("type", "string").put("maxLength", 8000);
                schema.putArray("required").add("articleJobId").add("serverId").add("command");
            }
            default -> {
                // The name is selected from the fixed catalog above.
            }
        }
        schema.put("additionalProperties", false);
        return schema;
    }

    private void localizedProperty(ObjectNode properties, String name) {
        ObjectNode property = properties.putObject(name);
        ArrayNode alternatives = mapper.createArrayNode();
        alternatives.addObject().put("type", "string");
        alternatives.addObject().put("type", "object");
        property.set("oneOf", alternatives);
    }

    private ObjectNode toolResult(JsonNode data) {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode content = result.putArray("content");
        content.addObject().put("type", "text").put("text", data == null ? "{}" : data.toString());
        result.set("structuredContent", data == null ? mapper.createObjectNode() : data);
        return result;
    }

    private Response runTestServerCommand(JsonNode id, JsonNode arguments) {
        if (!arguments.path("articleJobId").canConvertToLong() || !arguments.path("serverId").canConvertToLong()
                || arguments.path("command").asText().isBlank()) return error(id, -32602, "articleJobId, serverId and command are required");
        try { return ok(id, toolResult(mapper.valueToTree(testServers.executeForArticleJob(arguments.path("articleJobId").asLong(), arguments.path("serverId").asLong(), arguments.path("command").asText())))); }
        catch (RuntimeException exception) { return error(id, -32001, safeMessage(exception.getMessage())); }
    }

    private Response uploadImage(JsonNode id, JsonNode arguments) {
        if (!arguments.path("articleJobId").canConvertToLong() || arguments.path("articleJobId").asLong() <= 0) {
            return error(id, -32602, "articleJobId is required");
        }
        ObjectNode contentArguments = ((ObjectNode) arguments).deepCopy();
        contentArguments.remove("approved");
        contentArguments.remove("articleJobId");
        String idempotencyKey = "codex-mcp-" + sha256("media.upload\n" + contentArguments);
        String traceId = "codex-mcp-" + sha256("media.upload\n" + contentArguments);
        try {
            JsonNode response = windBlogContentClient.execute("media.upload", contentArguments, idempotencyKey, traceId);
            JsonNode data = response.path("data");
            String url = data.path("url").asText("").trim();
            if (url.isBlank()) return error(id, -32001, "WindBlog image upload returned no URL");
            articleEvidence.recordImage(arguments.path("articleJobId").asLong(), url);
            return ok(id, toolResult(data));
        } catch (RuntimeException exception) {
            return error(id, -32001, safeMessage(exception.getMessage()));
        }
    }

    private Response searchWikimediaImages(JsonNode id, JsonNode arguments) {
        try {
            ArrayNode candidates = mapper.createArrayNode();
            for (WikimediaImageService.Candidate image : wikimediaImages.search(arguments.path("query").asText(), arguments.path("limit").asInt(5))) {
                candidates.add(candidateNode(image));
            }
            ObjectNode data = mapper.createObjectNode();
            data.set("candidates", candidates);
            data.put("instructions", "Candidates are Wikimedia Commons files only. Inspect a candidate image before deciding whether it explains the article; do not import decorative images.");
            return ok(id, toolResult(data));
        } catch (RuntimeException exception) { return error(id, -32001, safeMessage(exception.getMessage())); }
    }

    private Response inspectWikimediaImage(JsonNode id, JsonNode arguments) {
        long jobId = arguments.path("articleJobId").asLong();
        if (jobId <= 0) return error(id, -32602, "articleJobId is required");
        try {
            WikimediaImageService.ImageBytes image = wikimediaImages.download(arguments.path("title").asText());
            long now = System.currentTimeMillis();
            reviewedCommonsImages.entrySet().removeIf(entry -> entry.getValue().reviewedAt() < now - IMAGE_REVIEW_TTL_MILLIS);
            if (reviewedCommonsImages.size() >= 1000) return error(id, -32001, "too many pending image reviews");
            reviewedCommonsImages.put(reviewKey(jobId, image.candidate().title()),
                    new ImageReview(now, imageDigest(image), image.candidate()));
            ObjectNode data = candidateNode(image.candidate());
            data.put("reviewRequirement", "Decide whether this exact image is editorially useful for a specific paragraph before importing it.");
            ObjectNode result = toolResult(data);
            result.withArray("content").addObject().put("type", "image")
                    .put("data", Base64.getEncoder().encodeToString(image.bytes())).put("mimeType", image.mimeType());
            return ok(id, result);
        } catch (RuntimeException exception) { return error(id, -32001, safeMessage(exception.getMessage())); }
    }

    private Response importWikimediaImage(JsonNode id, JsonNode arguments) {
        long jobId = arguments.path("articleJobId").asLong();
        if (jobId <= 0) return error(id, -32602, "articleJobId is required");
        try {
            WikimediaImageService.ImageBytes image = wikimediaImages.download(arguments.path("title").asText());
            String reviewKey = reviewKey(jobId, image.candidate().title());
            ImageReview review = reviewedCommonsImages.get(reviewKey);
            if (review == null || review.reviewedAt() < System.currentTimeMillis() - IMAGE_REVIEW_TTL_MILLIS
                    || !review.digest().equals(imageDigest(image)) || !review.candidate().equals(image.candidate())) {
                reviewedCommonsImages.remove(reviewKey);
                return error(id, -32002, "inspect this exact Commons image for this article job before importing it");
            }
            ObjectNode upload = mapper.createObjectNode();
            upload.put("fileName", safeFilename(image.candidate().title(), image.mimeType()));
            upload.put("mimeType", image.mimeType());
            upload.put("dataBase64", Base64.getEncoder().encodeToString(image.bytes()));
            String idempotencyKey = "codex-mcp-" + sha256("media.import.wikimedia\n" + jobId + "\n" + image.candidate().title());
            JsonNode response = windBlogContentClient.execute("media.upload", upload, idempotencyKey, idempotencyKey);
            String url = response.path("data").path("url").asText("").trim();
            if (url.isBlank()) return error(id, -32001, "WindBlog image upload returned no URL");
            WikimediaImageService.Candidate source = image.candidate();
            articleEvidence.recordImage(jobId, url, source.sourcePage(), source.license(), source.licenseUrl(), source.artist());
            reviewedCommonsImages.remove(reviewKey);
            ObjectNode data = (ObjectNode) response.path("data").deepCopy();
            data.set("source", candidateNode(source));
            return ok(id, toolResult(data));
        } catch (RuntimeException exception) { return error(id, -32001, safeMessage(exception.getMessage())); }
    }

    private ObjectNode candidateNode(WikimediaImageService.Candidate image) {
        ObjectNode data = mapper.createObjectNode();
        data.put("title", image.title());
        data.put("sourcePage", image.sourcePage());
        data.put("license", image.license());
        data.put("licenseUrl", image.licenseUrl());
        data.put("artist", image.artist());
        return data;
    }

    static String safeFilename(String title, String mimeType) {
        String base = title.replaceFirst("^File:", "").replaceAll("[^A-Za-z0-9._-]", "-");
        if (base.length() > 160) base = base.substring(0, 160);
        int extension = base.lastIndexOf('.');
        if (extension > 0) base = base.substring(0, extension);
        base += switch (mimeType) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "image/gif" -> ".gif";
            default -> ".jpg";
        };
        return base;
    }

    private record ImageReview(long reviewedAt, String digest, WikimediaImageService.Candidate candidate) { }

    private static String imageDigest(WikimediaImageService.ImageBytes image) {
        return sha256(Base64.getEncoder().encodeToString(image.bytes()));
    }

    private static String reviewKey(long jobId, String title) { return jobId + "\\n" + title; }

    private ObjectNode initializeResult() {
        ObjectNode result = mapper.createObjectNode();
        result.put("protocolVersion", "2025-06-18");
        result.put("instructions", "WindBlog content tools are allowlisted. For public images, use Wikimedia Commons search then inspect the returned image before import; only import editorially useful images. Never use arbitrary URLs, shell, SQL, or local file paths.");
        result.putObject("capabilities").putObject("tools");
        result.putObject("serverInfo").put("name", "codex-creator").put("version", "0.1.0");
        return result;
    }

    private Response ok(JsonNode id, JsonNode result) {
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        if (id != null) response.set("id", id);
        response.set("result", result);
        return Response.ok(response).build();
    }

    private Response error(JsonNode id, int code, String message) {
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        if (id != null) response.set("id", id);
        response.putObject("error").put("code", code).put("message", message);
        return Response.ok(response).build();
    }

    private boolean authorized(String authorization) {
        if (bearerToken.isEmpty() || bearerToken.get().isBlank()
                || authorization == null || !authorization.startsWith("Bearer ")) return false;
        byte[] expected = bearerToken.get().getBytes(StandardCharsets.UTF_8);
        byte[] actual = authorization.substring("Bearer ".length()).trim().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }

    @Transactional
    boolean profileAllows(String operation) {
        ModelProfile profile = ModelProfile.findById(defaultProfileId);
        if (profile == null || !profile.enabled) return false;
        try {
            JsonNode allowed = mapper.readTree(profile.allowedOperations == null ? "[]" : profile.allowedOperations);
            if (!allowed.isArray()) return false;
            for (JsonNode value : allowed) {
                if (value.isTextual() && value.asText().equalsIgnoreCase(operation)) return true;
            }
            return false;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String sha256(String value) {
        return com.skyhhjmk.codexcreator.security.HmacSigner.bodyDigest(value);
    }

    private static String safeMessage(String message) {
        if (message == null || message.isBlank()) return "WindBlog 内容请求失败";
        String normalized = message.replaceAll("[\\r\\n\\t]+", " ").trim();
        return normalized.length() > 500 ? normalized.substring(0, 500) : normalized;
    }

    private record Tool(String name, String description, String operation, String permission, boolean write) {
    }
}
