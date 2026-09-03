package com.skyhhjmk.codexcreator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AutomationPayloadValidatorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private AutomationPayloadValidator validator;

    @BeforeEach
    void setUp() {
        validator = new AutomationPayloadValidator();
        validator.mapper = mapper;
    }

    @Test
    void acceptsTopicOnlyWithWebSearchEvidenceAndPublicSources() throws Exception {
        JsonNode output = mapper.readTree("""
                {"topics":[{"title":"AI 工具更新","rationale":"有足够公开资料","keywords":["AI"],
                "recommendation":"WRITE","sources":[{"url":"https://example.com/news","title":"News"}]}]}
                """);
        JsonNode provenance = mapper.valueToTree(Map.of(
                "webSearchItems", List.of(Map.of(
                        "type", "webSearch", "query", "AI 工具", "action", Map.of("type", "search")))));

        List<AutomationPayloadValidator.TopicCandidate> result = validator.topics(output, provenance);

        assertEquals(1, result.size());
        assertEquals("https://example.com/news", result.getFirst().sources().getFirst().url());
    }

    @Test
    void rejectsTopicWithoutSearchEvidence() throws Exception {
        JsonNode output = mapper.readTree("""
                {"topics":[{"title":"没有证据的主题","sources":["https://example.com"]}]}
                """);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.topics(output, mapper.createObjectNode()));
        assertTrue(error.getMessage().contains("web search"));
    }

    @Test
    void rejectsPrivateInvalidAndOverlongSources() throws Exception {
        JsonNode provenance = mapper.valueToTree(Map.of(
                "webSearchItems", List.of(Map.of("type", "webSearch"))));
        JsonNode privateUrl = mapper.readTree("""
                {"topics":[{"title":"内部地址","sources":["http://localhost/admin"]}]}
                """);
        JsonNode invalidScheme = mapper.readTree("""
                {"topics":[{"title":"错误协议","sources":["file:///tmp/a"]}]}
                """);
        JsonNode overlongKeyword = mapper.readTree("""
                {"topics":[{"title":"关键词过长","keywords":["aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"],"sources":["https://example.com"]}]}
                """);

        assertThrows(IllegalArgumentException.class, () -> validator.topics(privateUrl, provenance));
        assertThrows(IllegalArgumentException.class, () -> validator.topics(invalidScheme, provenance));
        assertThrows(IllegalArgumentException.class, () -> validator.topics(overlongKeyword, provenance));
    }

    @Test
    void parsesFencedArticleJsonAndRequiresSources() throws Exception {
        JsonNode fenced = mapper.getNodeFactory().textNode("""
                ```json
                {"title":"草稿标题","summary":"摘要","contentMarkdown":"# 正文","sources":["https://example.com"]}
                ```
                """);

        AutomationPayloadValidator.ArticleDraft draft = validator.article(fenced, mapper.createObjectNode());

        assertEquals("草稿标题", draft.title());
        assertEquals("# 正文", draft.contentMarkdown());
        assertNull(draft.categoryId());
        AutomationPayloadValidator.ArticleDraft categorized = validator.article(mapper.readTree(
                "{\"title\":\"带分类草稿\",\"contentMarkdown\":\"# 正文\","
                        + "\"categoryId\":7,\"sources\":[\"https://example.com\"]}"),
                mapper.createObjectNode());
        assertEquals(7L, categorized.categoryId());
        assertThrows(IllegalArgumentException.class, () -> validator.article(mapper.readTree(
                        "{\"title\":\"错误分类\",\"contentMarkdown\":\"# 正文\","
                                + "\"categoryId\":0,\"sources\":[\"https://example.com\"]}"),
                mapper.createObjectNode()));
        assertThrows(IllegalArgumentException.class,
                () -> validator.article(mapper.readTree("{\"title\":\"无来源\",\"contentMarkdown\":\"x\"}"),
                        mapper.createObjectNode()));
        assertThrows(IllegalArgumentException.class,
                () -> validator.article(mapper.readTree(
                                "{\"title\":\"整页复制\",\"contentMarkdown\":\"<!doctype html><html><body>page</body></html>\",\"sources\":[\"https://example.com\"]}"),
                        mapper.createObjectNode()));
    }
}
