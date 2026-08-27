package com.skyhhjmk.codexcreator.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skyhhjmk.codexcreator.domain.ModelProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class OllamaProviderAdapter extends AbstractHttpProviderAdapter {
    @Inject
    public OllamaProviderAdapter(ObjectMapper mapper) {
        super(mapper);
    }

    @Override
    public boolean supports(String providerType) {
        return "OLLAMA".equalsIgnoreCase(providerType);
    }

    @Override
    protected String pathSuffix() {
        return "/api/chat";
    }

    @Override
    protected JsonNode requestBody(ProviderRequest request) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", request.profile().modelId);
        body.put("stream", false);
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "user").put("content", input(request).toString());
        return body;
    }

    @Override
    protected ProviderResponse parse(String body, ModelProfile profile) throws Exception {
        JsonNode response = mapper.readTree(body);
        JsonNode output = response.path("message").path("content");
        return response(output, profile, null,
                response.path("prompt_eval_count").asInt(0), response.path("eval_count").asInt(0));
    }
}
