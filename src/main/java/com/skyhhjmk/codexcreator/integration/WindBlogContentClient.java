package com.skyhhjmk.codexcreator.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skyhhjmk.codexcreator.security.HmacSigner;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Signed child-to-parent client used only by the allowlisted MCP tools. */
@ApplicationScoped
public class WindBlogContentClient {
    private static final String CLIENT_ID = "codex-creator";
    private static final String CONTENT_PATH = "/api/internal/integrations/codex-creator/content";

    @Inject
    ObjectMapper mapper;

    @ConfigProperty(name = "codex.creator.windblog.endpoint", defaultValue = "http://windblog:8080")
    String endpoint;

    @ConfigProperty(name = "codex.creator.windblog.shared-secret", defaultValue = "")
    Optional<String> sharedSecret;

    @ConfigProperty(name = "codex.creator.windblog.timeout", defaultValue = "30S")
    Duration timeout;

    private HttpClient httpClient;

    @PostConstruct
    void initialize() {
        Duration connectTimeout = timeout == null || timeout.isNegative() || timeout.isZero()
                ? Duration.ofSeconds(30) : timeout;
        httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
    }

    public JsonNode execute(String operation, JsonNode input, String idempotencyKey, String traceId) {
        String secret = sharedSecret.orElse("").trim();
        if (secret.isBlank()) {
            throw new IllegalStateException("WindBlog Codex 内容签名密钥未配置");
        }
        if (operation == null || operation.isBlank() || operation.length() > 64
                || idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 256
                || traceId == null || traceId.isBlank() || traceId.length() > 160) {
            throw new IllegalArgumentException("Codex 内容请求参数无效");
        }

        ObjectNode request = mapper.createObjectNode();
        request.put("operation", operation);
        request.set("input", input == null || input.isNull() ? mapper.createObjectNode() : input);
        request.put("idempotencyKey", idempotencyKey);
        request.put("traceId", traceId);
        String body;
        try {
            body = mapper.writeValueAsString(request);
        } catch (Exception exception) {
            throw new IllegalStateException("无法序列化 WindBlog 内容请求", exception);
        }

        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String nonce = UUID.randomUUID().toString();
        String digest = HmacSigner.bodyDigest(body);
        String signature = HmacSigner.sign(secret, CLIENT_ID, timestamp, nonce, digest);
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(contentEndpoint()))
                .timeout(timeout == null || timeout.isNegative() || timeout.isZero()
                        ? Duration.ofSeconds(30) : timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("X-Codex-Client-Id", CLIENT_ID)
                .header("X-Codex-Timestamp", timestamp)
                .header("X-Codex-Nonce", nonce)
                .header("X-Codex-Body-SHA256", digest)
                .header("X-Codex-Signature", signature)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            JsonNode responseJson = mapper.readTree(response.body() == null || response.body().isBlank()
                    ? "{}" : response.body());
            if (response.statusCode() < 200 || response.statusCode() >= 300
                    || !responseJson.path("success").asBoolean(false)) {
                String message = responseJson.path("message").asText("WindBlog 内容请求失败");
                throw new IllegalStateException("WindBlog 内容请求失败: " + message);
            }
            return responseJson;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("WindBlog 内容请求被中断", exception);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("WindBlog 内容请求失败", exception);
        }
    }

    private String contentEndpoint() {
        String base = endpoint == null || endpoint.isBlank() ? "http://windblog:8080" : endpoint.trim();
        while (base.endsWith("/") && base.length() > 1) {
            base = base.substring(0, base.length() - 1);
        }
        return base + CONTENT_PATH;
    }
}
