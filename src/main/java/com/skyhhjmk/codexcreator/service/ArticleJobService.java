package com.skyhhjmk.codexcreator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skyhhjmk.codexcreator.api.RuntimeInferenceRequest;
import com.skyhhjmk.codexcreator.api.RuntimeInferenceResponse;
import com.skyhhjmk.codexcreator.domain.AiArticleJob;
import com.skyhhjmk.codexcreator.domain.AiTopic;
import com.skyhhjmk.codexcreator.domain.AutomationTask;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@ApplicationScoped
public class ArticleJobService {
    @Inject
    ObjectMapper mapper;

    @Inject
    TaskExecutionService tasks;

    @Inject
    AutomationPayloadValidator validator;

    @Inject
    TopicAutomationService topicService;

    @Inject
    AuditLogService auditLogService;

    @ConfigProperty(name = "codex.creator.default-profile-id", defaultValue = "codex-default")
    String defaultProfileId;

    @ConfigProperty(name = "codex.creator.prompt-version", defaultValue = "1")
    String promptVersion;

    public Map<String, Object> start(JsonNode payload, String actorId, String traceId) {
        long topicId = requiredLong(payload, "topicId");
        Long categoryId = optionalLong(payload, "categoryId");
        String language = text(payload, "language", "zh-CN");
        String instructions = text(payload, "instructions", "");
        String requestKey = text(payload, "requestKey", "");
        if (requestKey.isBlank()) requestKey = "article-" + UUID.randomUUID();
        final String resolvedRequestKey = requestKey;
        validateLanguage(language);
        if (instructions.length() > 4_000) throw new IllegalArgumentException("instructions are too long");

        AiArticleJob existing = AiArticleJob.find("requestKey", requestKey).firstResult();
        if (existing != null) {
            if (!matches(existing, topicId, categoryId, language, instructions)) {
                throw new IllegalArgumentException("requestKey is already bound to a different article job");
            }
            if ("FAILED".equals(existing.status)) {
                JobContext retryContext = inTransaction(() -> retryJob(
                        existing.id, topicId, categoryId, language, instructions, actorId, traceId));
                if (retryContext != null) {
                    launch(retryContext);
                    AiArticleJob retried = inTransaction(() -> AiArticleJob.findById(retryContext.jobId()));
                    return retried == null
                            ? Map.of("status", "FAILED", "error", "article job disappeared")
                            : jobMap(retried);
                }
                AiArticleJob current = inTransaction(() -> AiArticleJob.findById(existing.id));
                return current == null ? jobMap(existing) : jobMap(current);
            }
            return jobMap(existing);
        }
        AiTopic topic = AiTopic.findById(topicId);
        if (topic == null) throw new NotFoundException("topic not found");
        if ("DISMISSED".equals(topic.status)) throw new IllegalStateException("dismissed topic cannot be written");
        if ("WRITING".equals(topic.status)) throw new IllegalStateException("topic already has a writing job");
        if ("DRAFT_CREATED".equals(topic.status)) {
            AiArticleJob completed = AiArticleJob.find(
                    "topic.id = ?1 and status = ?2 order by id desc", topicId, "DRAFT_CREATED").firstResult();
            if (completed != null) return jobMap(completed);
            throw new IllegalStateException("topic already has a draft assignment");
        }

        JobContext context;
        try {
            context = inTransaction(() -> createJob(
                    topicId, categoryId, language, instructions, resolvedRequestKey, actorId, traceId));
        } catch (RuntimeException exception) {
            AiArticleJob winner = AiArticleJob.find("requestKey", requestKey).firstResult();
            if (winner == null) throw exception;
            if (!matches(winner, topicId, categoryId, language, instructions)) {
                throw new IllegalArgumentException("requestKey is already bound to a different article job");
            }
            return jobMap(winner);
        }
        launch(context);
        Long jobId = context.jobId();
        AiArticleJob job = inTransaction(() -> AiArticleJob.findById(jobId));
        return job == null ? Map.of("status", "FAILED", "error", "article job disappeared") : jobMap(job);
    }

    @Transactional
    JobContext createJob(Long topicId, Long categoryId, String language, String instructions,
                         String requestKey, String actorId, String traceId) {
        AiTopic topic = AiTopic.find("id", topicId)
                .withLock(LockModeType.PESSIMISTIC_WRITE).firstResult();
        if (topic == null) throw new NotFoundException("topic not found");
        if ("DISMISSED".equals(topic.status)) throw new IllegalStateException("dismissed topic cannot be written");
        if ("WRITING".equals(topic.status)) throw new IllegalStateException("topic already has a writing job");
        topic.status = "WRITING";
        topic.reviewedAt = topic.reviewedAt == null ? OffsetDateTime.now() : topic.reviewedAt;
        topic.updatedAt = OffsetDateTime.now();

        AiArticleJob job = new AiArticleJob();
        job.topic = topic;
        job.requestKey = requestKey;
        job.targetCategoryId = categoryId;
        job.language = language;
        job.instructions = instructions;
        job.status = "QUEUED";
        job.autoPublishEligible = false;
        job.policySnapshot = json(Map.of("autoPublishEligible", false, "reason", "manual_admin_assignment"));
        job.createdAt = OffsetDateTime.now();
        job.updatedAt = job.createdAt;
        job.persist();
        auditLogService.log("WINDBLOG_ADMIN", actorId, "article.job.created", "ai_article_job",
                String.valueOf(job.id), traceId, Map.of("topicId", topicId, "language", language));
        return new JobContext(job.id, topic.id, topic.title, topic.rationale, topic.source,
                language, instructions, requestKey, traceId);
    }

    @Transactional
    JobContext retryJob(Long jobId, long topicId, Long categoryId, String language,
                        String instructions, String actorId, String traceId) {
        AiArticleJob job = AiArticleJob.find("id = ?1", jobId)
                .withLock(LockModeType.PESSIMISTIC_WRITE).firstResult();
        if (job == null || !"FAILED".equals(job.status)) return null;
        AiTopic topic = AiTopic.find("id = ?1", topicId)
                .withLock(LockModeType.PESSIMISTIC_WRITE).firstResult();
        if (topic == null) throw new NotFoundException("topic not found");
        if ("DISMISSED".equals(topic.status)) throw new IllegalStateException("dismissed topic cannot be written");
        if ("DRAFT_CREATED".equals(topic.status)) {
            throw new IllegalStateException("topic already has a draft assignment");
        }
        if ("WRITING".equals(topic.status)) return null;

        topic.status = "WRITING";
        topic.updatedAt = OffsetDateTime.now();
        job.targetCategoryId = categoryId;
        job.language = language;
        job.instructions = instructions;
        job.status = "QUEUED";
        job.task = null;
        job.content = null;
        job.provenance = null;
        job.errorMessage = null;
        job.completedAt = null;
        job.updatedAt = OffsetDateTime.now();
        auditLogService.log("WINDBLOG_ADMIN", actorId, "article.job.retried", "ai_article_job",
                String.valueOf(job.id), traceId, Map.of("topicId", topicId));
        return new JobContext(job.id, topic.id, topic.title, topic.rationale, topic.source,
                language, instructions, job.requestKey, traceId);
    }

    private void launch(JobContext context) {
        ObjectNode input = mapper.createObjectNode();
        input.put("prompt", articlePrompt(context));
        input.put("topicTitle", context.topicTitle());
        input.put("rationale", context.rationale() == null ? "" : context.rationale());
        input.put("language", context.language());
        input.put("instructions", context.instructions());
        input.set("topicSources", parse(context.topicSource()));
        RuntimeInferenceRequest request = new RuntimeInferenceRequest(
                "article", defaultProfileId, input, context.requestKey(), context.traceId(), promptVersion);
        CompletableFuture<RuntimeInferenceResponse> future;
        try {
            future = tasks.infer(request);
            inTransaction(() -> attachTask(context.jobId(), context.requestKey()));
        } catch (RuntimeException exception) {
            inTransaction(() -> finish(context.jobId(), null, exception));
            return;
        }
        future.whenComplete((response, error) -> inTransaction(
                () -> finish(context.jobId(), response, error)));
    }

    @Transactional
    void attachTask(Long jobId, String requestKey) {
        AiArticleJob job = AiArticleJob.findById(jobId);
        if (job == null) return;
        AutomationTask task = AutomationTask.find("idempotencyKey", requestKey).firstResult();
        job.task = task;
        job.status = "RUNNING";
        job.updatedAt = OffsetDateTime.now();
    }

    private void finish(Long jobId, RuntimeInferenceResponse response, Throwable error) {
        if (error != null) {
            fail(jobId, rootMessage(error));
            return;
        }
        if (response == null || !"SUCCEEDED".equals(response.status())) {
            fail(jobId, response == null || response.errorMessage() == null
                    ? "article task failed" : response.errorMessage());
            return;
        }
        try {
            AutomationPayloadValidator.ArticleDraft draft = validator.article(response.output(), response.provenance());
            complete(jobId, response, draft);
        } catch (RuntimeException exception) {
            fail(jobId, rootMessage(exception));
        }
    }

    @Transactional
    void complete(Long jobId, RuntimeInferenceResponse response,
                  AutomationPayloadValidator.ArticleDraft draft) {
        AiArticleJob job = AiArticleJob.find("id", jobId)
                .withLock(LockModeType.PESSIMISTIC_WRITE).firstResult();
        if (job == null) return;
        if ("SUCCEEDED".equals(job.status) || "DRAFT_CREATED".equals(job.status)) return;
        ObjectNode content = mapper.createObjectNode();
        content.put("title", draft.title());
        content.put("summary", draft.summary());
        content.put("contentMarkdown", draft.contentMarkdown());
        if (draft.categoryId() != null) {
            content.put("categoryId", draft.categoryId());
            job.targetCategoryId = draft.categoryId();
        }
        ArrayNode sources = content.putArray("sources");
        draft.sources().forEach(source -> {
            ObjectNode item = sources.addObject();
            item.put("url", source.url());
            if (!source.title().isBlank()) item.put("title", source.title());
        });
        job.content = json(content);
        job.provenance = json(response.provenance());
        job.status = "SUCCEEDED";
        job.completedAt = OffsetDateTime.now();
        job.updatedAt = job.completedAt;
        auditLogService.log("SYSTEM", "codex-creator", "article.job.succeeded", "ai_article_job",
                String.valueOf(jobId), response.traceId(), Map.of("taskId", response.taskId()));
    }

    @Transactional
    void fail(Long jobId, String message) {
        AiArticleJob job = AiArticleJob.findById(jobId);
        if (job == null) return;
        job.status = "FAILED";
        job.errorMessage = message == null ? "article task failed" : message;
        job.completedAt = OffsetDateTime.now();
        job.updatedAt = job.completedAt;
        if (job.topic != null && "WRITING".equals(job.topic.status)) {
            job.topic.status = "APPROVED";
            job.topic.updatedAt = job.completedAt;
        }
        auditLogService.log("SYSTEM", "codex-creator", "article.job.failed", "ai_article_job",
                String.valueOf(jobId), null, Map.of("error", job.errorMessage));
    }

    @Scheduled(every = "30s", identity = "codex-article-job-recovery")
    @Transactional
    void recover() {
        List<AiArticleJob> jobs = AiArticleJob.<AiArticleJob>find(
                "status = ?1 or status = ?2", "QUEUED", "RUNNING").page(0, 50).list();
        for (AiArticleJob job : jobs) {
            if (job.task == null) {
                AutomationTask task = AutomationTask.find("idempotencyKey", job.requestKey).firstResult();
                if (task == null) {
                    fail(job.id, "article task has no persisted automation task");
                    continue;
                }
                attachTask(job.id, job.requestKey);
                job.task = task;
            }
            RuntimeInferenceResponse snapshot = tasks.snapshot(job.task.id);
            if (snapshot == null) {
                fail(job.id, "article automation task disappeared");
            } else if ("SUCCEEDED".equals(snapshot.status())) {
                finish(job.id, snapshot, null);
            } else if ("FAILED".equals(snapshot.status())) {
                fail(job.id, snapshot.errorMessage());
            }
        }
    }

    private <T> T inTransaction(java.util.concurrent.Callable<T> action) {
        try {
            return QuarkusTransaction.requiringNew().call(action);
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("article transaction failed", exception);
        }
    }

    private void inTransaction(Runnable action) {
        QuarkusTransaction.requiringNew().run(action);
    }

    @Transactional
    public Map<String, Object> read(Long id) {
        AiArticleJob job = AiArticleJob.findById(id);
        if (job == null) throw new NotFoundException("article job not found");
        return jobMap(job);
    }

    @Transactional
    public Map<String, Object> acknowledgeDraft(Long id, Long postId, String actorId, String traceId) {
        if (postId == null || postId <= 0) throw new IllegalArgumentException("postId is required");
        AiArticleJob job = AiArticleJob.findById(id);
        if (job == null) throw new NotFoundException("article job not found");
        if (!"SUCCEEDED".equals(job.status) && !"DRAFT_CREATED".equals(job.status)) {
            throw new IllegalStateException("article job is not ready for draft acknowledgement");
        }
        if (job.windblogPostId != null && !job.windblogPostId.equals(postId)) {
            throw new IllegalStateException("article job is already linked to another post");
        }
        job.windblogPostId = postId;
        job.status = "DRAFT_CREATED";
        job.updatedAt = OffsetDateTime.now();
        if (job.topic != null) {
            job.topic.status = "DRAFT_CREATED";
            job.topic.updatedAt = job.updatedAt;
        }
        auditLogService.log("WINDBLOG_ADMIN", actorId, "article.job.draft_acknowledged",
                "ai_article_job", String.valueOf(id), traceId, Map.of("postId", postId));
        return jobMap(job);
    }

    private Map<String, Object> jobMap(AiArticleJob job) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", job.id);
        view.put("topicId", job.topic == null ? null : job.topic.id);
        view.put("taskId", job.task == null ? null : job.task.id);
        view.put("requestKey", job.requestKey);
        view.put("targetCategoryId", job.targetCategoryId);
        view.put("language", job.language);
        view.put("instructions", job.instructions == null ? "" : job.instructions);
        view.put("status", job.status);
        view.put("autoPublishEligible", job.autoPublishEligible);
        view.put("content", parse(job.content));
        view.put("provenance", parse(job.provenance));
        view.put("postId", job.windblogPostId);
        view.put("error", job.errorMessage == null ? "" : job.errorMessage);
        view.put("createdAt", job.createdAt);
        view.put("updatedAt", job.updatedAt);
        view.put("completedAt", job.completedAt);
        return view;
    }

    private boolean matches(AiArticleJob job, long topicId, Long categoryId,
                            String language, String instructions) {
        return job.topic != null && Objects.equals(job.topic.id, topicId)
                && (categoryId == null || Objects.equals(job.targetCategoryId, categoryId))
                && Objects.equals(job.language, language)
                && Objects.equals(job.instructions == null ? "" : job.instructions, instructions);
    }

    private String articlePrompt(JobContext context) {
        return "You are WindBlog's article writer. Write an original, accurate draft in " + context.language()
                + " about the supplied topic. Use the supplied public sources and use built-in web search to verify current facts when useful. "
                + "The administrator does not provide a category. Before writing, use windblog.list_categories to inspect the current WindBlog categories. "
                + "Choose the most suitable existing category, or use windblog.create_category to create a concise category when no suitable category exists, "
                + "then return that category's numeric id as categoryId. If no category can be selected or created, omit categoryId and WindBlog will use the default category named 未分类. "
                + "You may also use the allowlisted WindBlog MCP tools to read or create tags and to upload image bytes you generated; "
                + "never send an arbitrary URL, shell command, SQL statement, local file path, or source page as an upload. "
                + "Do not copy source pages, do not invent citations, do not use shell or files, and return only JSON with title, summary, optional categoryId, contentMarkdown, and sources. "
                + "Include a concise References section in Markdown. Additional editor instructions: " + context.instructions();
    }

    private JsonNode parse(String value) {
        try {
            return mapper.readTree(value == null || value.isBlank() ? "{}" : value);
        } catch (Exception ignored) {
            return mapper.createObjectNode();
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception exception) {
            throw new IllegalStateException("cannot serialize article job JSON", exception);
        }
    }

    private static long requiredLong(JsonNode payload, String field) {
        Long value = optionalLong(payload, field);
        if (value == null || value <= 0) throw new IllegalArgumentException(field + " is required");
        return value;
    }

    private static Long optionalLong(JsonNode payload, String field) {
        JsonNode value = payload == null ? null : payload.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.canConvertToLong()) throw new IllegalArgumentException(field + " must be an integer");
        return value.asLong();
    }

    private static String text(JsonNode payload, String field, String fallback) {
        JsonNode value = payload == null ? null : payload.get(field);
        if (value == null || value.isNull()) return fallback;
        if (!value.isTextual()) throw new IllegalArgumentException(field + " must be text");
        return value.asText().trim();
    }

    private static void validateLanguage(String language) {
        if (language == null || !language.matches("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})?")) {
            throw new IllegalArgumentException("language must be a BCP-47-like language tag");
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current != null && current.getCause() != null) current = current.getCause();
        return current == null || current.getMessage() == null ? "article job failed" : current.getMessage();
    }

    record JobContext(Long jobId, Long topicId, String topicTitle, String rationale, String topicSource,
                      String language, String instructions, String requestKey, String traceId) {
    }
}
