package com.skyhhjmk.codexcreator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strict boundary between model output and durable topic/article data. */
@ApplicationScoped
public class AutomationPayloadValidator {
    public static final int MAX_TITLE_LENGTH = 160;
    public static final int MAX_RATIONALE_LENGTH = 2_000;
    public static final int MAX_KEYWORDS = 12;
    public static final int MAX_SOURCES = 20;
    public static final int MAX_ARTICLE_LENGTH = 100_000;

    private static final Pattern CJK = Pattern.compile("[\\p{IsHan}]");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+(?:['’-][\\p{L}\\p{N}]+)*");
    private static final Pattern H1 = Pattern.compile("(?m)^#\\s+\\S");
    private static final Pattern H2 = Pattern.compile("(?m)^##\\s+\\S");
    private static final Pattern REFERENCE_HEADING = Pattern.compile(
            "(?im)^#{2,6}\\s+(参考(?:资料|来源|文献)?|资料来源|引用来源|references|sources)\\s*$");
    private static final List<String> GENERIC_AI_PHRASES = List.of(
            "在当今快速发展的时代", "在这个日新月异的时代", "随着科技的不断发展", "随着技术的不断发展",
            "在数字化浪潮中", "让我们一起", "本文将深入探讨", "本文旨在", "不难发现",
            "毋庸置疑", "值得注意的是", "综上所述", "总而言之", "in today's rapidly evolving",
            "in the ever-evolving", "this article delves into", "it is worth noting that", "in conclusion");

    @Inject
    ObjectMapper mapper;

    @ConfigProperty(name = "codex.creator.article.min-cjk-characters", defaultValue = "1200")
    int minCjkCharacters = 1200;

    @ConfigProperty(name = "codex.creator.article.min-words", defaultValue = "900")
    int minWords = 900;

    @ConfigProperty(name = "codex.creator.article.min-sources", defaultValue = "2")
    int minArticleSources = 2;

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
            Long seedId = optionalPositiveLong(value, "seedId");
            if (seedId == null) throw new IllegalArgumentException("seedId must be a positive integer");
            String title = requiredText(value, "title", MAX_TITLE_LENGTH);
            String rationale = optionalText(value, "rationale", MAX_RATIONALE_LENGTH);
            String recommendation = optionalText(value, "recommendation", 32).toUpperCase(Locale.ROOT);
            if (!Set.of("WRITE", "MONITOR", "IGNORE", "MANUAL").contains(recommendation)) {
                throw new IllegalArgumentException("unknown topic recommendation: " + recommendation);
            }
            List<String> keywords = strings(value.path("keywords"), MAX_KEYWORDS, 80, "keywords");
            List<Source> sources = sources(value.has("sources") ? value.get("sources") : value.get("sourceUrls"));
            if (sources.size() < 2) {
                throw new IllegalArgumentException("each topic must contain at least two independent sources");
            }
            result.add(new TopicCandidate(seedId, title, rationale, recommendation, keywords, sources));
        }
        return result;
    }

    public ArticleDraft article(JsonNode output, JsonNode provenance) {
        return article(output, provenance, "");
    }

    public ArticleDraft article(JsonNode output, JsonNode provenance, String language) {
        JsonNode root = object(output, "article output must be a JSON object");
        String title = requiredText(root, "title", MAX_TITLE_LENGTH);
        String summary = optionalText(root, "summary", MAX_RATIONALE_LENGTH);
        String contentMarkdown = requiredText(root, "contentMarkdown", MAX_ARTICLE_LENGTH);
        String editorialThesis = requiredText(root, "editorialThesis", 1_000);
        if (looksLikeCopiedWebPage(contentMarkdown)) {
            throw new IllegalArgumentException("article content appears to contain a copied source web page");
        }
        List<Source> sources = sources(root.get("sources"));
        Long categoryId = optionalPositiveLong(root, "categoryId");
        QualityReport quality = inspectArticle(
                title, summary, contentMarkdown, editorialThesis, sources, provenance, language);
        if (!quality.passed()) throw new ArticleQualityException(quality);
        return new ArticleDraft(title, summary, contentMarkdown, editorialThesis, sources, categoryId, quality);
    }

    private QualityReport inspectArticle(String title, String summary, String markdown, String thesis,
                                         List<Source> sources, JsonNode provenance, String language) {
        List<String> issues = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        boolean cjkArticle = language != null && language.toLowerCase(Locale.ROOT).startsWith("zh");
        String proseMarkdown = markdown.replaceAll("(?s)```.*?```", " ");
        String plainText = stripMarkdown(proseMarkdown);
        int cjkCharacters = countMatches(CJK, plainText);
        int words = countMatches(WORD, plainText);
        int sections = countMatches(H2, proseMarkdown);
        int paragraphs = substantiveParagraphs(markdown);
        int overlongParagraphs = overlongParagraphs(markdown, cjkArticle);
        int tableCount = validateTables(markdown, issues);

        if (cjkArticle ? cjkCharacters < minCjkCharacters : words < minWords) {
            issues.add(cjkArticle
                    ? "正文有效中文字符不足 " + minCjkCharacters + "，当前为 " + cjkCharacters
                    : "正文有效词数不足 " + minWords + "，当前为 " + words);
        }
        if (summary.isBlank() || (cjkArticle ? summary.length() < 40 : countMatches(WORD, summary) < 12)) {
            issues.add("摘要过短，必须交代文章结论、范围和读者价值");
        }
        if (H1.matcher(proseMarkdown).find()) {
            issues.add("正文不应重复页面标题为一级标题，请从二级标题开始");
        }
        if (sections < 3) issues.add("正文至少需要 3 个有信息量的二级章节");
        if (paragraphs < 5) issues.add("正文缺少充分展开的论证段落，至少需要 5 个实质段落");
        if (overlongParagraphs > 0) {
            issues.add("正文包含 " + overlongParagraphs + " 个过长段落；请拆成围绕单一观点的短段落");
        }
        if (sources.size() < minArticleSources) {
            issues.add("来源不足，至少需要 " + minArticleSources + " 个相互独立的公开来源");
        }
        if (!hasSearchEvidence(provenance)) {
            issues.add("本次写作没有可审计的网页搜索证据");
        } else {
            validateSearchEvidence(provenance);
        }

        int citedSources = 0;
        for (Source source : sources) {
            if (proseMarkdown.contains(source.url())) citedSources++;
        }
        if (citedSources < Math.min(minArticleSources, sources.size())) {
            issues.add("至少 " + minArticleSources + " 个来源必须在正文论据或参考资料中以 Markdown 链接出现");
        }
        if (!REFERENCE_HEADING.matcher(proseMarkdown).find()) {
            issues.add("正文缺少独立的参考资料章节");
        }

        String normalizedMarkdown = normalizeWhitespace(plainText);
        String normalizedThesis = normalizeWhitespace(stripMarkdown(thesis));
        if (normalizedThesis.length() < 20 || !normalizedMarkdown.contains(normalizedThesis)) {
            issues.add("editorialThesis 必须是一句至少 20 字且原样出现在正文中的明确判断");
        }
        String lower = proseMarkdown.toLowerCase(Locale.ROOT);
        List<String> clichés = GENERIC_AI_PHRASES.stream().filter(lower::contains).toList();
        if (!clichés.isEmpty()) {
            issues.add("正文包含模板化 AI 套话：" + String.join("、", clichés));
        }
        if (title.contains("：") || title.contains(":")) {
            warnings.add("标题使用冒号式模板，请确认它不是机械的双段标题");
        }

        int score = Math.max(0, 100 - issues.size() * 12 - warnings.size() * 3);
        Map<String, Integer> metrics = new LinkedHashMap<>();
        metrics.put("cjkCharacters", cjkCharacters);
        metrics.put("words", words);
        metrics.put("sections", sections);
        metrics.put("substantiveParagraphs", paragraphs);
        metrics.put("overlongParagraphs", overlongParagraphs);
        metrics.put("sources", sources.size());
        metrics.put("citedSources", citedSources);
        metrics.put("tables", tableCount);
        return new QualityReport(issues.isEmpty(), score, Map.copyOf(metrics),
                List.copyOf(issues), List.copyOf(warnings));
    }

    private int validateTables(String markdown, List<String> issues) {
        String[] lines = markdown.split("\\R", -1);
        boolean fenced = false;
        int tables = 0;
        for (int index = 0; index < lines.length; index++) {
            String trimmed = lines[index].trim();
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                fenced = !fenced;
                continue;
            }
            if (fenced || !isTableSeparator(trimmed)) continue;
            tables++;
            if (index == 0 || !lines[index - 1].contains("|")) {
                issues.add("Markdown 表格第 " + (index + 1) + " 行缺少表头");
                continue;
            }
            if (index > 1 && !lines[index - 2].isBlank()) {
                issues.add("Markdown 表格第 " + index + " 行前缺少空行");
            }
            int expected = tableCells(lines[index - 1]).size();
            int separatorColumns = tableCells(lines[index]).size();
            if (expected < 2 || separatorColumns != expected) {
                issues.add("Markdown 表格第 " + (index + 1) + " 行分隔列数与表头不一致");
            }
            int lastRow = index;
            for (int row = index + 1; row < lines.length && lines[row].contains("|"); row++) {
                if (lines[row].isBlank()) break;
                lastRow = row;
                int actual = tableCells(lines[row]).size();
                if (actual != expected) {
                    issues.add("Markdown 表格第 " + (row + 1) + " 行应有 " + expected + " 列，实际为 " + actual + " 列");
                }
            }
            if (lastRow == index) issues.add("Markdown 表格第 " + (index + 1) + " 行后没有数据行");
            if (lastRow + 1 < lines.length && !lines[lastRow + 1].isBlank()) {
                issues.add("Markdown 表格第 " + (lastRow + 1) + " 行后缺少空行");
            } else if (lastRow + 2 < lines.length && lines[lastRow + 2].trim().startsWith("|")) {
                issues.add("Markdown 表格在第 " + (lastRow + 2) + " 行包含空白断行");
            }
        }
        return tables;
    }

    private boolean isTableSeparator(String line) {
        List<String> cells = tableCells(line);
        return cells.size() >= 2 && cells.stream().allMatch(cell -> cell.trim().matches(":?-{3,}:?"));
    }

    private List<String> tableCells(String line) {
        String value = line == null ? "" : line.trim();
        if (value.startsWith("|")) value = value.substring(1);
        if (endsWithUnescapedPipe(value)) value = value.substring(0, value.length() - 1);
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean escaped = false;
        boolean code = false;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (escaped) {
                cell.append(current);
                escaped = false;
            } else if (current == '\\') {
                cell.append(current);
                escaped = true;
            } else if (current == '`') {
                code = !code;
                cell.append(current);
            } else if (current == '|' && !code) {
                cells.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(current);
            }
        }
        cells.add(cell.toString());
        return cells;
    }

    private boolean endsWithUnescapedPipe(String value) {
        if (!value.endsWith("|")) return false;
        int slashes = 0;
        for (int index = value.length() - 2; index >= 0 && value.charAt(index) == '\\'; index--) slashes++;
        return slashes % 2 == 0;
    }

    private int substantiveParagraphs(String markdown) {
        int count = 0;
        for (String block : markdown.split("(?:\\R\\s*){2,}")) {
            String trimmed = stripMarkdown(block).trim();
            if (!block.stripLeading().startsWith("#") && trimmed.length() >= 40) count++;
        }
        return count;
    }

    private int overlongParagraphs(String markdown, boolean cjkArticle) {
        int count = 0;
        for (String block : markdown.split("(?:\\R\\s*){2,}")) {
            String leading = block.stripLeading();
            if (leading.startsWith("#") || leading.startsWith("!") || leading.startsWith("|")
                    || leading.startsWith("-") || leading.startsWith("*") || leading.startsWith("```")) {
                continue;
            }
            String plain = stripMarkdown(block).trim();
            if (plain.isBlank()) continue;
            int length = cjkArticle ? countMatches(CJK, plain) : countMatches(WORD, plain);
            if (length > (cjkArticle ? 220 : 125)) count++;
        }
        return count;
    }

    private String stripMarkdown(String markdown) {
        return markdown.replaceAll("(?s)```.*?```", " ")
                .replaceAll("(?m)^#{1,6}\\s+", "")
                .replaceAll("!?\\[([^]]*)]\\([^)]*\\)", "$1")
                .replaceAll("[`*_>#|~-]", " ");
    }

    private int countMatches(Pattern pattern, String value) {
        int count = 0;
        Matcher matcher = pattern.matcher(value == null ? "" : value);
        while (matcher.find()) count++;
        return count;
    }

    private String normalizeWhitespace(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
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

    public record TopicCandidate(Long seedId, String title, String rationale, String recommendation,
                                 List<String> keywords, List<Source> sources) {
    }

    public record ArticleDraft(String title, String summary, String contentMarkdown, String editorialThesis,
                               List<Source> sources, Long categoryId, QualityReport qualityReport) {
    }

    public record QualityReport(boolean passed, int score, Map<String, Integer> metrics,
                                List<String> issues, List<String> warnings) {
    }

    public static final class ArticleQualityException extends IllegalArgumentException {
        private final QualityReport report;

        public ArticleQualityException(QualityReport report) {
            super("article quality gate rejected the draft: " + String.join("; ", report.issues()));
            this.report = report;
        }

        public QualityReport report() {
            return report;
        }
    }

    public record Source(String url, String title) {
    }
}
