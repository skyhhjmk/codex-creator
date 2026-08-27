package com.skyhhjmk.codexcreator.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skyhhjmk.codexcreator.domain.ModelProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class AnthropicProviderAdapter extends AbstractHttpProviderAdapter {
    @Inject
    public AnthropicProviderAdapter(ObjectMapper mapper) {
        super(mapper);
    }

    @Override
    public boolean supports(String providerType) {
        return "ANTHROPIC".equalsIgnoreCase(providerType);
    }

    @Override
    protected String pathSuffix() {
        return "/v1/messages";
    }

    @Override
    protected JsonNode requestBody(ProviderRequest request) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", request.profile().modelId);
        body.put("max_tokens", 4096);
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "user").put("content", input(request).toString());
        return body;
    }

    @Override
    protected ProviderResponse parse(String body, ModelProfile profile) throws Exception {
        JsonNode response = mapper.readTree(body);
        JsonNode output = response.path("content").path(0).path("text");
        JsonNode usage = response.path("usage");
        return response(output, profile, response.path("id").asText(null),
                usage.path("input_tokens").asInt(0), usage.path("output_tokens").asInt(0));
    }

    @Override
    protected String apiKey(ModelProfile profile) {
        return super.apiKey(profile);
    }
}
