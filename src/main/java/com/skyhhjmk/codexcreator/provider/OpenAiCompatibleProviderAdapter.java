package com.skyhhjmk.codexcreator.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skyhhjmk.codexcreator.domain.ModelProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class OpenAiCompatibleProviderAdapter extends AbstractHttpProviderAdapter {
    protected OpenAiCompatibleProviderAdapter() {
        super();
    }

    @Inject
    public OpenAiCompatibleProviderAdapter(ObjectMapper mapper) {
        super(mapper);
    }

    @Override
    public boolean supports(String providerType) {
        return "OPENAI_COMPATIBLE".equalsIgnoreCase(providerType);
    }

    @Override
    protected String pathSuffix() {
        return "/chat/completions";
    }

    @Override
    protected JsonNode requestBody(ProviderRequest request) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", request.profile().modelId);
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "user").put("content", input(request).toString());
        return body;
    }

    @Override
    protected ProviderResponse parse(String body, ModelProfile profile) throws Exception {
        JsonNode response = mapper.readTree(body);
        JsonNode output = response.path("choices").path(0).path("message").path("content");
        JsonNode usage = response.path("usage");
        return response(output, profile, response.path("id").asText(null),
                usage.path("prompt_tokens").asInt(0), usage.path("completion_tokens").asInt(0));
    }
}
