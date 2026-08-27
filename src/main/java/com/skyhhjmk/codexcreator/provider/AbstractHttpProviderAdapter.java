package com.skyhhjmk.codexcreator.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skyhhjmk.codexcreator.domain.ModelProfile;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

abstract class AbstractHttpProviderAdapter implements ProviderAdapter {
    protected final ObjectMapper mapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    protected AbstractHttpProviderAdapter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    protected AbstractHttpProviderAdapter() {
        this.mapper = null;
    }

    @Override
    public CompletableFuture<ProviderResponse> infer(ProviderRequest request) {
        ModelProfile profile = request.profile();
        String endpoint = profile.providerConfig == null ? "" : profile.providerConfig.endpoint;
        if (endpoint == null || endpoint.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalStateException("provider endpoint is not configured"));
        }
        String apiKey = apiKey(profile);
        try {
            JsonNode body = requestBody(request);
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri(endpoint, pathSuffix()))
                    .timeout(Duration.ofSeconds(120))
                    .header("Content-Type", "application/json")
                    .header("X-Trace-Id", request.traceId())
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            if (!apiKey.isBlank()) builder.header("Authorization", "Bearer " + apiKey);
            return httpClient.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
                    .thenCompose(response -> {
                        if (response.statusCode() >= 400) {
                            return CompletableFuture.failedFuture(new IllegalStateException(
                                    "provider returned HTTP " + response.statusCode()));
                        }
                        try {
                            return CompletableFuture.completedFuture(parse(response.body(), profile));
                        } catch (Exception exception) {
                            return CompletableFuture.failedFuture(new IllegalStateException("invalid provider response", exception));
                        }
                    });
        } catch (Exception exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    protected abstract String pathSuffix();

    protected abstract JsonNode requestBody(ProviderRequest request);

    protected abstract ProviderResponse parse(String body, ModelProfile profile) throws Exception;

    protected String apiKey(ModelProfile profile) {
        try {
            JsonNode config = mapper.readTree(profile.providerConfig == null ? "{}" : profile.providerConfig.config);
            String env = config.path("apiKeyEnv").asText("");
            return env.isBlank() ? "" : System.getenv().getOrDefault(env, "");
        } catch (Exception ignored) {
            return "";
        }
    }

    protected JsonNode input(ProviderRequest request) {
        return request.input() == null ? mapper.createObjectNode() : request.input();
    }

    protected ProviderResponse response(JsonNode output, ModelProfile profile, String id,
                                        int inputTokens, int outputTokens) {
        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("provider", profile.vendor);
        provenance.put("model", profile.modelId);
        return new ProviderResponse(output,
                new ProviderResponse.Usage(inputTokens, outputTokens, inputTokens + outputTokens),
                provenance, id, null, null);
    }

    private URI uri(String endpoint, String suffix) {
        String normalized = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        return URI.create(normalized + suffix);
    }
}
