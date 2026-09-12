package com.skyhhjmk.codexcreator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skyhhjmk.codexcreator.domain.ArticleJobEvidence;
import io.quarkus.runtime.annotations.RegisterForReflection;
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
        validator.minCjkCharacters = 120;
        validator.minWords = 80;
        validator.minArticleSources = 2;
        validator.requireGeneratedImages = false;
    }

    @Test
    void qualityReportIsRegisteredForNativeJsonSerialization() {
        assertNotNull(AutomationPayloadValidator.QualityReport.class
                .getAnnotation(RegisterForReflection.class));
    }

    @Test
    void acceptsTopicOnlyWithWebSearchEvidenceAndPublicSources() throws Exception {
        JsonNode output = mapper.readTree("""
                {"topics":[{"seedId":7,"title":"AI 工具更新","rationale":"有足够公开资料","keywords":["AI"],
                "recommendation":"WRITE","sources":[{"url":"https://example.com/news","title":"News"},
                {"url":"https://second.example/report","title":"Report"}]}]}
                """);
        JsonNode provenance = mapper.valueToTree(Map.of(
                "webSearchItems", List.of(Map.of(
                        "type", "webSearch", "query", "AI 工具", "action", Map.of("type", "search")))));

        List<AutomationPayloadValidator.TopicCandidate> result = validator.topics(output, provenance);

        assertEquals(1, result.size());
        assertEquals(7L, result.getFirst().seedId());
        assertEquals("https://example.com/news", result.getFirst().sources().getFirst().url());
    }

    @Test
    void rejectsTopicWithoutSearchEvidence() throws Exception {
        JsonNode output = mapper.readTree("""
                {"topics":[{"seedId":1,"title":"没有证据的主题","sources":["https://example.com"]}]}
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
                {"topics":[{"seedId":1,"title":"内部地址","sources":["http://localhost/admin"]}]}
                """);
        JsonNode invalidScheme = mapper.readTree("""
                {"topics":[{"seedId":1,"title":"错误协议","sources":["file:///tmp/a"]}]}
                """);
        JsonNode overlongKeyword = mapper.readTree("""
                {"topics":[{"seedId":1,"title":"关键词过长","keywords":["aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"],"sources":["https://example.com"]}]}
                """);

        assertThrows(IllegalArgumentException.class, () -> validator.topics(privateUrl, provenance));
        assertThrows(IllegalArgumentException.class, () -> validator.topics(invalidScheme, provenance));
        assertThrows(IllegalArgumentException.class, () -> validator.topics(overlongKeyword, provenance));
    }

    @Test
    void acceptsContinuousArticleWithoutManufacturedSections() {
        String markdown = goodMarkdown().replaceAll("(?m)^## (?!参考资料).+\\n", "");
        AutomationPayloadValidator.ArticleDraft draft = validator.article(
                goodArticle(markdown), searchProvenance(), "zh-CN");
        assertTrue(draft.qualityReport().passed());
    }

    @Test
    void parsesFencedHighQualityArticleJson() throws Exception {
        ObjectNode article = goodArticle(goodMarkdown());
        JsonNode fenced = mapper.getNodeFactory().textNode(
                "```json\n" + mapper.writeValueAsString(article) + "\n```");

        AutomationPayloadValidator.ArticleDraft draft = validator.article(fenced, searchProvenance(), "zh-CN");

        assertEquals("草稿标题", draft.title());
        assertTrue(draft.contentMarkdown().contains("## 事实边界"));
        assertNull(draft.categoryId());
        assertTrue(draft.qualityReport().passed());
        assertEquals(2, draft.qualityReport().metrics().get("citedSources"));

        article.put("categoryId", 7);
        AutomationPayloadValidator.ArticleDraft categorized = validator.article(article, searchProvenance(), "zh-CN");
        assertEquals(7L, categorized.categoryId());
        article.put("categoryId", 0);
        assertThrows(IllegalArgumentException.class,
                () -> validator.article(article, searchProvenance(), "zh-CN"));
    }

    @Test
    void rejectsThinUncitedAndTemplateLikeArticle() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> validator.article(mapper.readTree("{\"title\":\"无来源\",\"contentMarkdown\":\"x\"}"),
                        mapper.createObjectNode()));
        ObjectNode article = goodArticle(goodMarkdown()
                .replace("这不是功能清单，而是一次基于证据并且明确取舍的编辑判断。", "在当今快速发展的时代，结论显而易见而且无需讨论。")
                .replace("https://second.example/report", "https://missing.example/report"));
        article.put("editorialThesis", "在当今快速发展的时代，结论显而易见而且无需讨论。");

        AutomationPayloadValidator.ArticleQualityException error = assertThrows(
                AutomationPayloadValidator.ArticleQualityException.class,
                () -> validator.article(article, searchProvenance(), "zh-CN"));

        assertTrue(error.report().issues().stream().anyMatch(issue -> issue.contains("AI 套话")));
        assertTrue(error.report().issues().stream().anyMatch(issue -> issue.contains("Markdown 链接")));
    }

    @Test
    void rejectsMalformedMarkdownTableBeforeDraftPersistence() {
        String malformed = goodMarkdown().replace(
                "| 方案 | 收益 | 代价 |\n| --- | --- | --- |\n| 严格质检 | 稳定输出 | 增加一次重写 |",
                "| 方案 | 收益 | 代价 |\n| --- | --- |\n| 严格质检 | 稳定输出 | 增加一次重写 | 多余列 |");
        ObjectNode article = goodArticle(malformed);

        AutomationPayloadValidator.ArticleQualityException error = assertThrows(
                AutomationPayloadValidator.ArticleQualityException.class,
                () -> validator.article(article, searchProvenance(), "zh-CN"));

        assertTrue(error.report().issues().stream().anyMatch(issue -> issue.contains("表格")));
    }

    @Test
    void rejectsWallOfTextParagraphs() {
        ObjectNode article = goodArticle(goodMarkdown().replace(
                "开头先给出证据边界与核心判断，避免用空洞背景铺垫正文。这里继续补充足够具体的上下文、对象和限制条件，让段落真正承担论证作用。",
                "这".repeat(230)));

        AutomationPayloadValidator.ArticleQualityException error = assertThrows(
                AutomationPayloadValidator.ArticleQualityException.class,
                () -> validator.article(article, searchProvenance(), "zh-CN"));

        assertTrue(error.report().issues().stream().anyMatch(issue -> issue.contains("过长段落")));
    }

    @Test
    void rejectsCopiedWholeWebPage() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> validator.article(mapper.readTree(
                                "{\"title\":\"整页复制\",\"editorialThesis\":\"这是一个足够长的明确判断句，用来满足字段解析要求。\","
                                        + "\"contentMarkdown\":\"<!doctype html><html><body>page</body></html>\","
                                        + "\"sources\":[\"https://example.com\",\"https://second.example\"]}"),
                        mapper.createObjectNode()));
    }

    @Test
    void requiresTwoBoundImagesForArticleJobs() {
        validator.requireGeneratedImages = true;
        String first = "https://windblog.example/uploads/lead.png";
        String second = "https://windblog.example/uploads/diagram.png";
        String markdown = withImages(goodMarkdown(), first, second);

        AutomationPayloadValidator.ArticleDraft draft = validator.article(goodArticle(markdown), searchProvenance(),
                "zh-CN", List.of(image(first), image(second)), false);

        assertTrue(draft.qualityReport().passed());
        assertEquals(2, draft.qualityReport().metrics().get("images"));

        AutomationPayloadValidator.ArticleQualityException error = assertThrows(
                AutomationPayloadValidator.ArticleQualityException.class,
                () -> validator.article(goodArticle(markdown), searchProvenance(), "zh-CN", List.of(image(first)), false));
        assertTrue(error.report().issues().stream().anyMatch(issue -> issue.contains("本次任务上传")));
    }

    @Test
    void acceptsImageFreeDraftWhenImageGenerationIsUnavailable() {
        AutomationPayloadValidator.ArticleDraft draft = validator.article(
                goodArticle(goodMarkdown()), searchProvenance(), "zh-CN", List.of(), false);

        assertTrue(draft.qualityReport().passed());
        assertEquals(0, draft.qualityReport().metrics().get("images"));
    }

    @Test
    void optionalImagesStillRequireTaskBoundUploadEvidence() {
        var error = assertThrows(AutomationPayloadValidator.ArticleQualityException.class, () ->
                validator.article(goodArticle(withImages(goodMarkdown(),
                        "https://external.example/one.png", "https://external.example/two.png")),
                        searchProvenance(), "zh-CN", List.of(), false));
        assertTrue(error.report().issues().stream().anyMatch(issue -> issue.contains("本次任务上传")));
    }

    @Test
    void requiresNaturalEmbeddedPracticalEvidence() {
        String first = "https://windblog.example/uploads/lead.png";
        String second = "https://windblog.example/uploads/diagram.png";
        String markdown = withImages(goodMarkdown(), first, second).replace("## 可执行取舍", """
                执行 `nginx -v` 后，出现如下信息即为正常：

                ```text
                nginx version: nginx/1.24.0
                ```

                ## 可执行取舍""");
        List<ArticleJobEvidence> evidence = List.of(image(first), image(second), verification(
                "nginx -v", 0, "nginx version: nginx/1.24.0\nbuilt with OpenSSL"));

        assertTrue(validator.article(goodArticle(markdown), searchProvenance(), "zh-CN", evidence, true)
                .qualityReport().passed());

        AutomationPayloadValidator.ArticleQualityException error = assertThrows(
                AutomationPayloadValidator.ArticleQualityException.class,
                () -> validator.article(goodArticle(withImages(goodMarkdown(), first, second)), searchProvenance(),
                        "zh-CN", evidence, true));
        assertTrue(error.report().issues().stream().anyMatch(issue -> issue.contains("实操证据")));
    }

    private ArticleJobEvidence image(String url) {
        ArticleJobEvidence evidence = new ArticleJobEvidence();
        evidence.kind = "IMAGE";
        evidence.mediaUrl = url;
        return evidence;
    }

    private ArticleJobEvidence verification(String command, int exitCode, String output) {
        ArticleJobEvidence evidence = new ArticleJobEvidence();
        evidence.kind = "VERIFICATION";
        evidence.command = command;
        evidence.exitCode = exitCode;
        evidence.output = output;
        return evidence;
    }

    private String withImages(String markdown, String first, String second) {
        return markdown.replace(
                "开头先给出证据边界与核心判断，避免用空洞背景铺垫正文。这里继续补充足够具体的上下文、对象和限制条件，让段落真正承担论证作用。",
                """
                开头先给出证据边界与核心判断，避免用空洞背景铺垫正文。这里继续补充足够具体的上下文、对象和限制条件，让段落真正承担论证作用。

                ![展示核心结论的流程关系图](%s)
                *图：开篇结论与关键约束的关系。*
                """.formatted(first)).replace(
                "第二份资料从另一个角度给出限制条件，作者据此区分事实、推断和判断。[第二份报告](https://second.example/report) 用于交叉核对关键结论。",
                """
                第二份资料从另一个角度给出限制条件，作者据此区分事实、推断和判断。[第二份报告](https://second.example/report) 用于交叉核对关键结论。

                ![比较不同验证步骤的结构示意图](%s)
                *图：关键验证步骤的先后关系。*
                """.formatted(second));
    }

    private ObjectNode goodArticle(String markdown) {
        ObjectNode article = mapper.createObjectNode();
        article.put("title", "草稿标题");
        article.put("summary", "这篇文章给出明确结论、事实范围与实施代价，并说明读者应当如何判断方案是否真的有效。");
        article.put("editorialThesis", "这不是功能清单，而是一次基于证据并且明确取舍的编辑判断。");
        article.put("contentMarkdown", markdown);
        article.putNull("categoryId");
        var sources = article.putArray("sources");
        sources.addObject().put("url", "https://example.com/report").put("title", "第一份报告");
        sources.addObject().put("url", "https://second.example/report").put("title", "第二份报告");
        return article;
    }

    private String goodMarkdown() {
        return """
                这不是功能清单，而是一次基于证据并且明确取舍的编辑判断。

                开头先给出证据边界与核心判断，避免用空洞背景铺垫正文。这里继续补充足够具体的上下文、对象和限制条件，让段落真正承担论证作用。

                ## 事实边界

                第一份资料说明了可验证的事实，正文只陈述来源能够支持的范围，并把链接放在论据附近。[第一份报告](https://example.com/report) 提供了直接证据。

                第二份资料从另一个角度给出限制条件，作者据此区分事实、推断和判断。[第二份报告](https://second.example/report) 用于交叉核对关键结论。

                ## 我的判断

                真正重要的不是堆叠功能名称，而是明确哪些读者问题被解决、哪些代价仍然存在。判断必须能够被反驳，也必须说明适用范围。

                反方观点认为更宽松的流程速度更快，这个意见有现实基础；但当内容直接进入公开草稿时，返工与信誉成本更值得优先控制。

                ## 可执行取舍

                实施时先校验结构、引用和表格，再决定是否接受草稿。失败原因应当反馈给下一次写作，而不是只显示一个笼统错误。

                | 方案 | 收益 | 代价 |
                | --- | --- | --- |
                | 严格质检 | 稳定输出 | 增加一次重写 |

                最终选择应以真实样本衡量：若重写能显著减少人工修改，就保留严格门槛；若只增加延迟，则调整阈值而不是取消质检。

                ## 参考资料

                - [第一份报告](https://example.com/report)
                - [第二份报告](https://second.example/report)
                """;
    }

    private JsonNode searchProvenance() {
        return mapper.valueToTree(Map.of("webSearchItems", List.of(
                Map.of("type", "webSearch", "action", Map.of("type", "search"), "query", "topic"))));
    }
}
