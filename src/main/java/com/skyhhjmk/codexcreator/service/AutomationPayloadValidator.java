package com.skyhhjmk.codexcreator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Strict boundary between model output and durable topic/article data. */
@ApplicationScoped
public class AutomationPayloadValidator {
    public static final int MAX_TITLE_LENGTH = 160;
    public static final int MAX_RATIONALE_LENGTH = 2_000;
    public static final int MAX_KEYWORDS = 12;
    public static final int MAX_SOURCES = 20;
    public static final int MAX_ARTICLE_LENGTH = 100_000;

    @Inject
    ObjectMapper mapper;

    public List<TopicCandidate> topics(JsonNode output, JsonNode provenance) {
        JsonNode root = object(output, "topic output must be a JSON object");
        JsonNode values = root.get("topics");
        if (values == null || !values.isArray() || values.isEmpty() || values.size() > 50) {
            throw new IllegalArgumentException("topic output must contain 1 to 50 topics");
        }
        if (!hasSearchEvidence(provenance)) {
            throw new IllegalArgumentException("topic output has no app-server web search evidence");
        }
        validateSearchEvidence(provenance);

        List<TopicCandidate> result = new ArrayList<>();
        for (JsonNode value : values) {
            String title = requiredText(value, "title", MAX_TITLE_LENGTH);
            String rationale = optionalText(value, "rationale", MAX_RATIONALE_LENGTH);
            String recommendation = optionalText(value, "recommendation", 32).toUpperCase(Locale.ROOT);
            if (!Set.of("WRITE", "MONITOR", "IGNORE", "MANUAL").contains(recommendation)) {
                throw new IllegalArgumentException("unknown topic recommendation: " + recommendation);
            }
            List<String> keywords = strings(value.path("keywords"), MAX_KEYWORDS, 80, "keywords");
            List<Source> sources = sources(value.has("sources") ? value.get("sources") : value.get("sourceUrls"));
            if (sources.isEmpty()) {
                throw new IllegalArgumentException("each topic must contain at least one source");
            }
            result.add(new TopicCandidate(title, rationale, recommendation, keywords, sources));
        }
        return result;
    }

    public ArticleDraft article(JsonNode output, JsonNode provenance) {
        JsonNode root = object(output, "article output must be a JSON object");
        String title = requiredText(root, "title", MAX_TITLE_LENGTH);
        String summary = optionalText(root, "summary", MAX_RATIONALE_LENGTH);
        String contentMarkdown = requiredText(root, "contentMarkdown", MAX_ARTICLE_LENGTH);
        if (looksLikeCopiedWebPage(contentMarkdown)) {
            throw new IllegalArgumentException("article content appears to contain a copied source web page");
        }
        List<Source> sources = sources(root.get("sources"));
        if (sources.isEmpty()) {
            throw new IllegalArgumentException("article output must contain at least one source");
        }
        Long categoryId = optionalPositiveLong(root, "categoryId");
        return new ArticleDraft(title, summary, contentMarkdown, sources, categoryId);
    }

    public JsonNode parseTextualJson(JsonNode output) {
        if (output == null || output.isNull() || output.isMissingNode()) {
            throw new IllegalArgumentException("model output is empty");
        }
        if (!output.isTextual()) return output;
        String text = output.asText().trim();
        if (text.startsWith("```") && text.endsWith("```")) {
            int firstNewline = text.indexOf('\n');
            text = firstNewline > 0 ? text.substring(firstNewline + 1, text.length() - 3).trim()
                    : text.substring(3, text.length() - 3).trim();
        }
        try {
            return mapper.readTree(text);
        } catch (Exception exception) {
            throw new IllegalArgumentException("model output is not valid JSON", exception);
        }
    }

    public boolean hasSearchEvidence(JsonNode provenance) {
        JsonNode items = provenance == null ? null : provenance.get("webSearchItems");
        if (items == null || !items.isArray()) return false;
        for (JsonNode item : items) {
            if (!"webSearch".equals(item.path("type").asText())) continue;
            JsonNode action = item.path("action");
            String actionType = action.path("type").asText("");
            if (actionType.isBlank() || Set.of("search", "openPage", "findInPage").contains(actionType)) {
                return true;
            }
        }
        return false;
    }

    private void validateSearchEvidence(JsonNode provenance) {
        JsonNode items = provenance == null ? null : provenance.get("webSearchItems");
        if (items == null || !items.isArray()) return;
        for (JsonNode item : items) {
            if (!"webSearch".equals(item.path("type").asText())) continue;
            validateOptionalEvidenceUrl(item, "url");
            validateOptionalEvidenceUrl(item, "pageUrl");
            JsonNode action = item.path("action");
            if (action.isObject()) validateOptionalEvidenceUrl(action, "url");
        }
    }

    private void validateOptionalEvidenceUrl(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) return;
        if (!value.isTextual()) throw new IllegalArgumentException("web search evidence URL is invalid");
        validateUrl(value.asText().trim());
    }

    private JsonNode object(JsonNode output, String message) {
        JsonNode value = parseTextualJson(output);
        if (!value.isObject()) throw new IllegalArgumentException(message);
        return value;
    }

    private String requiredText(JsonNode object, String field, int maxLength) {
        String value = optionalText(object, field, maxLength);
        if (value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }

    private String optionalText(JsonNode object, String field, int maxLength) {
        JsonNode value = object == null ? null : object.get(field);
        if (value == null || value.isNull()) return "";
        if (!value.isTextual()) throw new IllegalArgumentException(field + " must be a string");
        String text = value.asText().trim();
        if (text.length() > maxLength) throw new IllegalArgumentException(field + " exceeds its maximum length");
        return text;
    }

    private Long optionalPositiveLong(JsonNode object, String field) {
        JsonNode value = object == null ? null : object.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.canConvertToLong() || value.asLong() <= 0) {
            throw new IllegalArgumentException(field + " must be a positive integer");
        }
        return value.asLong();
    }

    private List<String> strings(JsonNode values, int maxCount, int maxLength, String field) {
        if (values == null || values.isMissingNode() || values.isNull()) return List.of();
        if (!values.isArray() || values.size() > maxCount) throw new IllegalArgumentException(field + " is invalid");
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (JsonNode value : values) {
            if (!value.isTextual()) throw new IllegalArgumentException(field + " must contain strings");
            String text = value.asText().trim();
            if (text.length() > maxLength) throw new IllegalArgumentException(field + " contains an overlong value");
            if (!text.isBlank()) result.add(text);
        }
        return List.copyOf(result);
    }

    private List<Source> sources(JsonNode values) {
        if (values == null || values.isMissingNode() || values.isNull()) return List.of();
        if (!values.isArray() || values.size() > MAX_SOURCES) throw new IllegalArgumentException("sources is invalid");
        List<Source> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode value : values) {
            String url;
            String title = "";
            if (value.isTextual()) {
                url = value.asText().trim();
            } else if (value.isObject()) {
                url = optionalText(value, "url", 2_048);
                title = optionalText(value, "title", 300);
            } else {
                throw new IllegalArgumentException("source must be a URL or object");
            }
            validateUrl(url);
            if (seen.add(url)) result.add(new Source(url, title));
        }
        return List.copyOf(result);
    }

    private void validateUrl(String value) {
        if (value == null || value.isBlank() || value.length() > 2_048) {
            throw new IllegalArgumentException("source URL is invalid");
        }
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost();
            if (!("http".equals(scheme) || "https".equals(scheme)) || host == null || host.isBlank()
                    || uri.getUserInfo() != null || uri.getPort() > 65535 || isPrivateHost(host)) {
                throw new IllegalArgumentException("source URL is not a public HTTP(S) URL");
            }
        } catch (IllegalArgumentException exception) {
            if (exception.getMessage() != null && exception.getMessage().contains("public HTTP")) throw exception;
            throw new IllegalArgumentException("source URL is invalid", exception);
        }
    }

    private boolean isPrivateHost(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        if (normalized.equals("localhost") || normalized.endsWith(".localhost")
                || normalized.endsWith(".local") || normalized.endsWith(".internal")) return true;
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                        || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                        || address.isMulticastAddress()) return true;
            }
            return false;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean looksLikeCopiedWebPage(String content) {
        String normalized = content.toLowerCase(Locale.ROOT);
        return normalized.contains("<!doctype html") || normalized.contains("<html")
                || normalized.contains("<head") || normalized.contains("<body")
                || normalized.contains("<script") || normalized.contains("<style");
    }

    public record TopicCandidate(String title, String rationale, String recommendation,
                                 List<String> keywords, List<Source> sources) {
    }

    public record ArticleDraft(String title, String summary, String contentMarkdown,
                               List<Source> sources, Long categoryId) {
    }

    public record Source(String url, String title) {
    }
}
