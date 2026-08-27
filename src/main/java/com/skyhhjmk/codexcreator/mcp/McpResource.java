package com.skyhhjmk.codexcreator.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Path("/mcp")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class McpResource {
    private static final List<Tool> TOOLS = List.of(
            new Tool("windblog.search_posts", "Search public WindBlog posts", false),
            new Tool("windblog.moderate_comment", "Return a moderation recommendation", true),
            new Tool("windblog.review_link", "Review a link application", true),
            new Tool("windblog.retrieve_knowledge", "Retrieve approved knowledge chunks", false),
            new Tool("windblog.read_topic", "Read an AI topic suggestion", false),
            new Tool("windblog.create_draft", "Create a draft article", true),
            new Tool("windblog.crawl_link_articles", "Manually crawl approved public link pages", true)
    );

    @Inject
    ObjectMapper mapper;

    @ConfigProperty(name = "codex.creator.mcp-bearer-token", defaultValue = "")
    Optional<String> bearerToken;

    @ConfigProperty(name = "codex.creator.mcp.write-approval-required", defaultValue = "true")
    boolean writeApprovalRequired;

    @POST
    public Response post(String body, @HeaderParam("Authorization") String authorization) {
        if (!authorized(authorization)) return Response.status(Response.Status.UNAUTHORIZED)
                .header("WWW-Authenticate", "Bearer").build();
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
        JsonNode arguments = params.path("arguments");
        if (tool.write() && writeApprovalRequired && !arguments.path("approved").asBoolean(false)) {
            return error(id, -32002, "write tool requires explicit approved=true");
        }
        // The integration SPI is deliberately explicit: no generic SQL, shell, or HTTP proxy exists here.
        ObjectNode result = mapper.createObjectNode();
        result.put("status", tool.write() ? "PENDING_INTEGRATION" : "READ_ONLY_STUB");
        result.put("tool", tool.name());
        result.put("message", "Tool is allowlisted; WindBlog integration executor must be configured");
        return ok(id, result);
    }

    private ObjectNode toolsList() {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode tools = result.putArray("tools");
        for (Tool tool : TOOLS) {
            tools.addObject().put("name", tool.name()).put("description", tool.description())
                    .putObject("inputSchema").put("type", "object");
        }
        return result;
    }

    private ObjectNode initializeResult() {
        ObjectNode result = mapper.createObjectNode();
        result.put("protocolVersion", "2025-06-18");
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
        if (bearerToken.isEmpty() || bearerToken.get().isBlank() || authorization == null
                || !authorization.startsWith("Bearer ")) return false;
        byte[] expected = bearerToken.get().getBytes(StandardCharsets.UTF_8);
        byte[] actual = authorization.substring("Bearer ".length()).trim().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }

    private record Tool(String name, String description, boolean write) {}
}
