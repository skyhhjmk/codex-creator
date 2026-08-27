package com.skyhhjmk.codexcreator.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skyhhjmk.codexcreator.domain.ModelProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class OpenAiResponsesProviderAdapter extends AbstractHttpProviderAdapter {
    protected OpenAiResponsesProviderAdapter() {
        super();
    }

    @Inject
    public OpenAiResponsesProviderAdapter(ObjectMapper mapper) {
        super(mapper);
    }

    @Override
    public boolean supports(String providerType) {
        return "OPENAI_RESPONSES".equalsIgnoreCase(providerType);
    }

    @Override
    protected String pathSuffix() {
        return "/responses";
    }

    @Override
    protected JsonNode requestBody(ProviderRequest request) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", request.profile().modelId);
        body.put("store", false);
        body.put("input", input(request).toString());
        return body;
    }

    @Override
    protected ProviderResponse parse(String body, ModelProfile profile) throws Exception {
        JsonNode response = mapper.readTree(body);
        JsonNode output = response.has("output_text") ? response.get("output_text") : response.path("output");
        JsonNode usage = response.path("usage");
        return response(output, profile, response.path("id").asText(null),
                usage.path("input_tokens").asInt(0), usage.path("output_tokens").asInt(0));
    }
}
