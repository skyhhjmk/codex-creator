package com.skyhhjmk.codexcreator.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skyhhjmk.codexcreator.domain.ModelProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class GeminiProviderAdapter extends AbstractHttpProviderAdapter {
    protected GeminiProviderAdapter() {
        super();
    }

    @Inject
    public GeminiProviderAdapter(ObjectMapper mapper) {
        super(mapper);
    }

    @Override
    public boolean supports(String providerType) {
        return "GEMINI".equalsIgnoreCase(providerType);
    }

    @Override
    protected String pathSuffix() {
        return "/v1beta/models/generateContent";
    }

    @Override
    protected JsonNode requestBody(ProviderRequest request) {
        ObjectNode body = mapper.createObjectNode();
        ArrayNode contents = body.putArray("contents");
        contents.addObject().putArray("parts").addObject().put("text", input(request).toString());
        return body;
    }

    @Override
    protected ProviderResponse parse(String body, ModelProfile profile) throws Exception {
        JsonNode response = mapper.readTree(body);
        JsonNode output = response.path("candidates").path(0).path("content").path("parts").path(0).path("text");
        return response(output, profile, null, 0, 0);
    }
}
