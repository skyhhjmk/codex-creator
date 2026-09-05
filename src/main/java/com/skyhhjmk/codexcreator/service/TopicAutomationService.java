package com.skyhhjmk.codexcreator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skyhhjmk.codexcreator.api.RuntimeInferenceRequest;
import com.skyhhjmk.codexcreator.api.RuntimeInferenceResponse;
import com.skyhhjmk.codexcreator.domain.AiTopic;
import com.skyhhjmk.codexcreator.domain.AiArticleJob;
import com.skyhhjmk.codexcreator.domain.AutomationTask;
import com.skyhhjmk.codexcreator.domain.ModelProfile;
import com.skyhhjmk.codexcreator.domain.TopicAutomationSettings;
import com.skyhhjmk.codexcreator.domain.TopicDiscoveryRun;
import com.skyhhjmk.codexcreator.domain.TopicQuerySeed;
import io.quarkus.panache.common.Page;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@ApplicationScoped
public class TopicAutomationService {
    private static final Set<String> REVIEWABLE_STATUSES = Set.of("SUGGESTED", "APPROVED");
    private static final Set<Integer> INTERVALS = Set.of(60, 360, 720, 1440);

    @Inject
    ObjectMapper mapper;

    @Inject
    TaskExecutionService tasks;

    @Inject
    TaskCacheService cache;

    @Inject
    AutomationPayloadValidator validator;

    @Inject
    AuditLogService auditLogService;

    @Inject
    ModelCatalogService modelCatalog;

    @Inject
    Instance<TopicAutomationService> self;

    @ConfigProperty(name = "codex.creator.default-profile-id", defaultValue = "codex-default")
    String defaultProfileId;

    @ConfigProperty(name = "codex.creator.prompt-version", defaultValue = "2")
    String promptVersion;

    @Scheduled(every = "1m", identity = "codex-topic-discovery-scheduler")
    void scheduledTick() {
        TaskCacheService.LockAttempt lock = cache.acquire("topic-discovery-scheduler", Duration.ofSeconds(90));
        if (lock.status() == TaskCacheService.LockStatus.BUSY) return;
        try {
            StartContext context = self.get().createScheduledRun();
            if (context != null) launch(context);
        } finally {
            if (lock.status() == TaskCacheService.LockStatus.ACQUIRED) {
                cache.release("topic-discovery-scheduler", lock.token());
            }
        }
    }

    @Transactional
    public Map<String, Object> settingsView() {
        return settingsMap(settings());
    }

    @Transactional
    public Map<String, Object> updateSettings(JsonNode payload, String actorId, String traceId) {
        TopicAutomationSettings settings = settings();
        boolean enabled = booleanValue(payload, "enabled", settings.enabled);
        int interval = intValue(payload, "intervalMinutes", settings.intervalMinutes);
        int maxSeeds = intValue(payload, "maxSeedsPerRun", settings.maxSeedsPerRun);
        int maxTopics = intValue(payload, "maxTopicsPerRun", settings.maxTopicsPerRun);
        validateSettings(interval, maxSeeds, maxTopics);
        settings.enabled = enabled;
        settings.intervalMinutes = interval;
        settings.maxSeedsPerRun = maxSeeds;
        settings.maxTopicsPerRun = maxTopics;
        settings.nextRunAt = enabled ? OffsetDateTime.now().plusMinutes(interval) : null;
        settings.lastError = null;
        settings.updatedAt = OffsetDateTime.now();
        settings.persist();
        auditLogService.log("WINDBLOG_ADMIN", actorId, "topic.settings.updated",
                "topic_automation_settings", "1", traceId, Map.of(
                        "enabled", enabled, "intervalMinutes", interval,
                        "maxSeedsPerRun", maxSeeds, "maxTopicsPerRun", maxTopics));
        return settingsMap(settings);
    }

    @Transactional
    public List<Map<String, Object>> seedsView() {
        return TopicQuerySeed.<TopicQuerySeed>find("order by sortOrder asc, id asc")
                .list().stream().map(this::seedMap).toList();
    }

    @Transactional
    public Map<String, Object> upsertSeed(Long id, JsonNode payload, String actorId, String traceId) {
        TopicQuerySeed seed = id == null ? null : TopicQuerySeed.findById(id);
        if (id != null && seed == null) throw new NotFoundException("topic query seed not found");
        String name = text(payload, "name", seed == null ? null : seed.name);
        String query = text(payload, "query", seed == null ? null : seed.query);
        String language = text(payload, "language", seed == null ? "zh-CN" : seed.language);
        String region = nullableText(payload, "region", seed == null ? null : seed.region);
        if (name == null || name.isBlank() || name.length() > 120) {
            throw new IllegalArgumentException("seed name is required and must be at most 120 characters");
        }
        if (query == null || query.isBlank() || query.length() > 1_000) {
            throw new IllegalArgumentException("seed query is required and must be at most 1000 characters");
        }
        validateLanguage(language);
        if (region != null && region.length() > 64) throw new IllegalArgumentException("seed region is too long");
        if (seed == null) {
            seed = new TopicQuerySeed();
            seed.createdAt = OffsetDateTime.now();
        }
        seed.name = name.trim();
        seed.query = query.trim();
        seed.language = language.trim();
        seed.region = region == null || region.isBlank() ? null : region.trim();
        seed.enabled = booleanValue(payload, "enabled", seed.enabled || id == null);
        seed.sortOrder = intValue(payload, "sortOrder", seed.sortOrder);
        if (seed.sortOrder < 0 || seed.sortOrder > 1_000_000) throw new IllegalArgumentException("invalid seed sortOrder");
        seed.updatedAt = OffsetDateTime.now();
        seed.persist();
        auditLogService.log("WINDBLOG_ADMIN", actorId, id == null ? "topic.seed.created" : "topic.seed.updated",
                "topic_query_seed", String.valueOf(seed.id), traceId, Map.of("name", seed.name));
        return seedMap(seed);
    }

    @Transactional
    public void deleteSeed(Long id, String actorId, String traceId) {
        TopicQuerySeed seed = TopicQuerySeed.findById(id);
        if (seed == null) throw new NotFoundException("topic query seed not found");
        seed.delete();
        auditLogService.log("WINDBLOG_ADMIN", actorId, "topic.seed.deleted",
                "topic_query_seed", String.valueOf(id), traceId, Map.of());
    }

    public Map<String, Object> startManual(String idempotencyKey, String traceId, String actorId,
                                           String requestedProfileId) {
        String profileId = modelCatalog.resolve(requestedProfileId, defaultProfileId, "topic");
        String requestKey = idempotencyKey == null || idempotencyKey.isBlank()
                ? "manual-topic-" + UUID.randomUUID() : idempotencyKey.trim();
        TaskCacheService.LockAttempt lock = cache.acquire("topic-discovery-start", Duration.ofSeconds(90));
        if (lock.status() == TaskCacheService.LockStatus.BUSY) {
            TopicDiscoveryRun active = activeRun();
            return active == null ? Map.of("status", "BUSY") : runMap(active);
        }
        try {
            StartContext context;
            try {
                context = self.get().createManualRun(requestKey, traceId, actorId, profileId);
            } catch (RuntimeException exception) {
                TopicDiscoveryRun winner = self.get().findRunByKey(requestKey);
                if (winner != null) return runMap(winner);
                throw exception;
            }
            if (context != null) launch(context);
            TopicDiscoveryRun run = self.get().findRunByKey(requestKey);
            return run == null ? Map.of("status", "BUSY") : runMap(run);
        } finally {
            if (lock.status() == TaskCacheService.LockStatus.ACQUIRED) {
                cache.release("topic-discovery-start", lock.token());
            }
        }
    }

    @Transactional
    public Map<String, Object> runView(Long id) {
        TopicDiscoveryRun run = TopicDiscoveryRun.findById(id);
        if (run == null) throw new NotFoundException("topic discovery run not found");
        return runMap(run);
    }

    @Transactional
    public Map<String, Object> listRuns(int page, int pageSize) {
        int safePage = Math.max(1, page);
        int safePageSize = Math.max(1, Math.min(100, pageSize));
        var query = TopicDiscoveryRun.<TopicDiscoveryRun>find("order by createdAt desc, id desc");
        long total = query.count();
        List<Map<String, Object>> items = query.page(Page.of(safePage - 1, safePageSize)).list()
                .stream().map(this::runMap).toList();
        return Map.of("items", items, "total", total, "page", safePage, "pageSize", safePageSize);
    }

    @Transactional
    public Map<String, Object> listTopics(String status, int page, int pageSize) {
        int safePage = Math.max(1, page);
        int safePageSize = Math.max(1, Math.min(100, pageSize));
        String normalizedStatus = status == null ? "" : status.trim();
        var query = normalizedStatus.isBlank()
                ? AiTopic.<AiTopic>find("order by lastSeenAt desc, id desc")
                : "ASSIGNED".equalsIgnoreCase(normalizedStatus)
                        ? AiTopic.<AiTopic>find(
                                "status = ?1 or status = ?2 or status = ?3 order by lastSeenAt desc, id desc",
                                "WRITING", "DRAFT_CREATED", "FAILED")
                        : AiTopic.<AiTopic>find(
                                "status = ?1 order by lastSeenAt desc, id desc", normalizedStatus);
        long total = query.count();
        List<Map<String, Object>> items = query.page(Page.of(safePage - 1, safePageSize)).list()
                .stream().map(this::topicMap).toList();
        return Map.of("items", items, "total", total, "page", safePage, "pageSize", safePageSize);
    }

    @Transactional
    public Map<String, Object> topicView(Long id) {
        AiTopic topic = AiTopic.findById(id);
        if (topic == null) throw new jakarta.ws.rs.NotFoundException("topic not found");
        return topicMap(topic);
    }

    @Scheduled(every = "30s", identity = "codex-topic-discovery-recovery")
    @Transactional
    void recoverRuns() {
        List<TopicDiscoveryRun> runs = TopicDiscoveryRun.<TopicDiscoveryRun>find(
                "status = ?1 or status = ?2", "QUEUED", "RUNNING").page(0, 50).list();
        for (TopicDiscoveryRun run : runs) {
            AutomationTask task = run.task;
            if (task == null) {
                task = AutomationTask.find("idempotencyKey", run.idempotencyKey).firstResult();
                if (task != null) self.get().attachTask(run.id, run.idempotencyKey);
            }
            if (task == null) {
                self.get().markRunFailed(run.id, "topic automation task disappeared");
                continue;
            }
            RuntimeInferenceResponse snapshot = tasks.snapshot(task.id);
            if (snapshot == null) {
                self.get().markRunFailed(run.id, "topic automation task disappeared");
            } else if ("SUCCEEDED".equals(snapshot.status()) || "FAILED".equals(snapshot.status())) {
                finishRun(run.id, snapshot, null);
            }
        }
    }

    @Transactional
    public Map<String, Object> reviewTopic(Long id, String decision, String note,
                                            String actorId, String traceId) {
        AiTopic topic = AiTopic.findById(id);
        if (topic == null) throw new NotFoundException("topic not found");
        String normalized = decision == null ? "" : decision.trim().toUpperCase();
        if (!REVIEWABLE_STATUSES.contains(topic.status)) {
            throw new IllegalStateException("topic is no longer reviewable");
        }
        if (!Set.of("APPROVE", "DISMISS").contains(normalized)) {
            throw new IllegalArgumentException("decision must be APPROVE or DISMISS");
        }
        topic.status = "APPROVE".equals(normalized) ? "APPROVED" : "DISMISSED";
        topic.reviewNote = note == null ? null : note.trim();
        topic.reviewedAt = OffsetDateTime.now();
        topic.updatedAt = topic.reviewedAt;
        topic.persist();
        auditLogService.log("WINDBLOG_ADMIN", actorId, "topic.reviewed", "ai_topic",
                String.valueOf(id), traceId, Map.of("decision", normalized));
        return topicMap(topic);
    }

    @Transactional
    public void markTopicWriting(Long id) {
        AiTopic topic = AiTopic.findById(id);
        if (topic == null) throw new NotFoundException("topic not found");
        if ("DISMISSED".equals(topic.status) || "DRAFT_CREATED".equals(topic.status)) {
            throw new IllegalStateException("topic cannot be assigned in its current state");
        }
        topic.status = "WRITING";
        topic.updatedAt = OffsetDateTime.now();
        topic.persist();
    }

    @Transactional
    public void markTopicDraftCreated(Long id) {
        AiTopic topic = AiTopic.findById(id);
        if (topic == null) return;
        topic.status = "DRAFT_CREATED";
        topic.updatedAt = OffsetDateTime.now();
        topic.persist();
    }

    @Transactional
    StartContext createScheduledRun() {
        TopicAutomationSettings settings = settings();
        OffsetDateTime now = OffsetDateTime.now();
        if (!settings.enabled || (settings.nextRunAt != null && settings.nextRunAt.isAfter(now))) return null;
        String key = "scheduled-topic-" + (now.toEpochSecond() / (settings.intervalMinutes * 60L));
        TopicDiscoveryRun existing = TopicDiscoveryRun.find("idempotencyKey", key).firstResult();
        if (existing != null) {
            settings.nextRunAt = now.plusMinutes(settings.intervalMinutes);
            settings.updatedAt = now;
            settings.persist();
            return null;
        }
        if (activeRun() != null) return null;
        String profileId = modelCatalog.resolve(null, defaultProfileId, "topic");
        StartContext context = createRun(settings, "SCHEDULED", key, "topic-schedule-" + now.toEpochSecond(), "SYSTEM", profileId);
        settings.lastRunAt = now;
        settings.nextRunAt = now.plusMinutes(settings.intervalMinutes);
        settings.lastError = null;
        settings.updatedAt = now;
        settings.persist();
        return context;
    }

    @Transactional
    StartContext createManualRun(String key, String traceId, String actorId, String profileId) {
        TopicDiscoveryRun existing = TopicDiscoveryRun.find("idempotencyKey", key).firstResult();
        if (existing != null) return null;
        TopicDiscoveryRun active = activeRun();
        if (active != null) return null;
        TopicAutomationSettings settings = settings();
        return createRun(settings, "MANUAL", key,
                traceId == null || traceId.isBlank() ? "topic-manual-" + UUID.randomUUID() : traceId,
                actorId == null ? "ADMIN" : actorId, profileId);
    }

    @Transactional
    TopicDiscoveryRun findRunByKey(String key) {
        return TopicDiscoveryRun.find("idempotencyKey", key).firstResult();
    }

    private StartContext createRun(TopicAutomationSettings settings, String trigger,
                                   String key, String traceId, String actorId, String profileId) {
        List<TopicQuerySeed> selected = TopicQuerySeed.<TopicQuerySeed>find(
                "enabled = true order by sortOrder asc, lastUsedAt asc, id asc")
                .page(0, settings.maxSeedsPerRun).list();
        List<SeedSnapshot> seeds = selected.stream().map(seed -> new SeedSnapshot(
                seed.id, seed.name, seed.query, seed.language, seed.region)).toList();
        TopicDiscoveryRun run = new TopicDiscoveryRun();
        run.triggerType = trigger;
        run.status = "QUEUED";
        run.idempotencyKey = key;
        run.traceId = traceId;
        run.modelProfile = ModelProfile.findById(profileId);
        run.seedCount = seeds.size();
        run.seedSnapshot = seedSnapshotJson(seeds);
        run.createdAt = OffsetDateTime.now();
        run.persist();
        OffsetDateTime usedAt = run.createdAt;
        selected.forEach(seed -> {
            seed.lastUsedAt = usedAt;
            seed.updatedAt = usedAt;
        });
        auditLogService.log("SYSTEM", actorId, "topic.discovery.started", "topic_discovery_run",
                String.valueOf(run.id), traceId, Map.of("trigger", trigger, "seedCount", seeds.size()));
        return new StartContext(run.id, key, traceId, seeds, settings.maxTopicsPerRun, profileId);
    }

    private void launch(StartContext context) {
        if (context.seeds().isEmpty()) {
            finishRun(context.runId(), null, new IllegalStateException("no enabled topic query seeds"));
            return;
        }
        ObjectNode input = mapper.createObjectNode();
        input.put("prompt", topicPrompt(context.seeds(), context.maxTopics()));
        input.put("maxTopics", context.maxTopics());
        ArrayNode seeds = input.putArray("seeds");
        context.seeds().forEach(seed -> {
            ObjectNode value = seeds.addObject();
            value.put("id", seed.id());
            value.put("name", seed.name());
            value.put("query", seed.query());
            value.put("language", seed.language());
            if (seed.region() != null) value.put("region", seed.region());
        });
        RuntimeInferenceRequest request = new RuntimeInferenceRequest(
                "topic", context.profileId(), input, context.idempotencyKey(), context.traceId(), promptVersion);
        CompletableFuture<RuntimeInferenceResponse> future;
        try {
            future = tasks.infer(request);
            self.get().attachTask(context.runId(), context.idempotencyKey());
        } catch (RuntimeException exception) {
            finishRun(context.runId(), null, exception);
            return;
        }
        future.whenComplete((response, error) -> finishRun(context.runId(), response, error));
    }

    @Transactional
    void attachTask(Long runId, String key) {
        TopicDiscoveryRun run = TopicDiscoveryRun.findById(runId);
        if (run == null) return;
        AutomationTask task = AutomationTask.find("idempotencyKey", key).firstResult();
        run.task = task;
        run.status = "RUNNING";
        run.startedAt = OffsetDateTime.now();
    }

    private void finishRun(Long runId, RuntimeInferenceResponse response, Throwable error) {
        if (error != null) {
            self.get().markRunFailed(runId, rootMessage(error));
            return;
        }
        if (response != null && "RETRYING".equals(response.status())) {
            return;
        }
        if (response == null || !"SUCCEEDED".equals(response.status())) {
            self.get().markRunFailed(runId, response == null ? "topic task returned no response" :
                    (response.errorMessage() == null ? "topic task failed" : response.errorMessage()));
            return;
        }
        try {
            List<AutomationPayloadValidator.TopicCandidate> candidates = validator.topics(
                    response.output(), response.provenance());
            self.get().persistTopics(runId, response, candidates);
        } catch (RuntimeException exception) {
            self.get().markRunFailed(runId, rootMessage(exception));
        }
    }

    @Transactional
    int persistTopics(Long runId, RuntimeInferenceResponse response,
                      List<AutomationPayloadValidator.TopicCandidate> candidates) {
        TopicDiscoveryRun run = TopicDiscoveryRun.find("id", runId)
                .withLock(LockModeType.PESSIMISTIC_WRITE).firstResult();
        if (run == null) throw new NotFoundException("topic discovery run not found");
        if ("SUCCEEDED".equals(run.status) || "FAILED".equals(run.status)) return -1;
        List<SeedSnapshot> seeds = readSeeds(run.seedSnapshot);
        OffsetDateTime now = OffsetDateTime.now();
        for (AutomationPayloadValidator.TopicCandidate candidate : candidates) {
            SeedSnapshot candidateSeed = seeds.stream()
                    .filter(seed -> seed.id().equals(candidate.seedId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "topic seedId is not part of this discovery run: " + candidate.seedId()));
            String key = TopicFingerprint.key(candidate.title());
            AiTopic topic = AiTopic.find("dedupeKey", key).firstResult();
            if (topic == null) topic = findLegacyFingerprintMatch(key);
            if (topic == null) {
                topic = new AiTopic();
                topic.title = candidate.title();
                topic.rationale = candidate.rationale();
                topic.recommendation = candidate.recommendation();
                topic.dedupeKey = key;
                topic.status = "SUGGESTED";
                topic.createdAt = now;
                topic.lastSeenAt = now;
                topic.occurrenceCount = 1;
            } else {
                topic.dedupeKey = key;
                topic.lastSeenAt = now;
                topic.occurrenceCount++;
            }
            topic.discoveryRun = run;
            topic.updatedAt = now;
            topic.source = mergeSource(topic.source, candidate, candidateSeed, response, now);
            topic.persist();
        }
        run.status = "SUCCEEDED";
        run.topicCount = candidates.size();
        run.completedAt = OffsetDateTime.now();
        TopicAutomationSettings settings = settings();
        settings.lastSuccessAt = run.completedAt;
        settings.lastError = null;
        settings.lastRunAt = settings.lastRunAt == null ? run.completedAt : settings.lastRunAt;
        settings.updatedAt = run.completedAt;
        settings.persist();
        auditLogService.log("SYSTEM", "codex-creator", "topic.discovery.succeeded",
                "topic_discovery_run", String.valueOf(runId), run.traceId,
                Map.of("topicCount", candidates.size()));
        return candidates.size();
    }

    private AiTopic findLegacyFingerprintMatch(String fingerprint) {
        return AiTopic.<AiTopic>listAll().stream()
                .filter(candidate -> fingerprint.equals(TopicFingerprint.key(candidate.title)))
                .findFirst().orElse(null);
    }

    @Transactional
    void markRunFailed(Long runId, String message) {
        TopicDiscoveryRun run = TopicDiscoveryRun.find("id", runId)
                .withLock(LockModeType.PESSIMISTIC_WRITE).firstResult();
        if (run == null) return;
        if ("SUCCEEDED".equals(run.status) || "FAILED".equals(run.status)) return;
        run.status = "FAILED";
        run.errorMessage = message == null ? "topic discovery failed" : message;
        run.completedAt = OffsetDateTime.now();
        TopicAutomationSettings settings = settings();
        settings.lastError = run.errorMessage;
        settings.updatedAt = run.completedAt;
        settings.persist();
        auditLogService.log("SYSTEM", "codex-creator", "topic.discovery.failed",
                "topic_discovery_run", String.valueOf(runId), run.traceId,
                Map.of("error", run.errorMessage));
    }

    private TopicDiscoveryRun activeRun() {
        return TopicDiscoveryRun.find("status = ?1 or status = ?2", "QUEUED", "RUNNING").firstResult();
    }

    private TopicAutomationSettings settings() {
        TopicAutomationSettings settings = TopicAutomationSettings.findById(1L);
        if (settings == null) {
            settings = new TopicAutomationSettings();
            settings.id = 1L;
            settings.enabled = false;
            settings.intervalMinutes = 360;
            settings.maxSeedsPerRun = 5;
            settings.maxTopicsPerRun = 20;
            settings.updatedAt = OffsetDateTime.now();
            settings.persist();
        }
        return settings;
    }

    private Map<String, Object> settingsMap(TopicAutomationSettings settings) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", settings.id);
        view.put("enabled", settings.enabled);
        view.put("intervalMinutes", settings.intervalMinutes);
        view.put("maxSeedsPerRun", settings.maxSeedsPerRun);
        view.put("maxTopicsPerRun", settings.maxTopicsPerRun);
        view.put("nextRunAt", settings.nextRunAt);
        view.put("lastRunAt", settings.lastRunAt);
        view.put("lastSuccessAt", settings.lastSuccessAt);
        view.put("lastError", settings.lastError == null ? "" : settings.lastError);
        view.put("updatedAt", settings.updatedAt);
        return view;
    }

    private Map<String, Object> seedMap(TopicQuerySeed seed) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", seed.id);
        view.put("name", seed.name);
        view.put("query", seed.query);
        view.put("language", seed.language);
        view.put("region", seed.region);
        view.put("enabled", seed.enabled);
        view.put("sortOrder", seed.sortOrder);
        view.put("lastUsedAt", seed.lastUsedAt);
        view.put("createdAt", seed.createdAt);
        view.put("updatedAt", seed.updatedAt);
        return view;
    }

    private Map<String, Object> runMap(TopicDiscoveryRun run) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", run.id);
        view.put("trigger", run.triggerType);
        view.put("status", run.status);
        view.put("taskId", run.task == null ? null : run.task.id);
        view.put("taskStatus", run.task == null ? null : run.task.status);
        view.put("nextAttemptAt", run.task == null ? null : run.task.nextAttemptAt);
        view.put("profileId", run.modelProfile == null ? defaultProfileId : run.modelProfile.profileId);
        view.put("modelId", run.modelProfile == null ? "auto" : run.modelProfile.modelId);
        view.put("traceId", run.traceId);
        view.put("seedCount", run.seedCount);
        view.put("topicCount", run.topicCount);
        view.put("error", run.errorMessage == null ? "" : run.errorMessage);
        view.put("createdAt", run.createdAt);
        view.put("startedAt", run.startedAt);
        view.put("completedAt", run.completedAt);
        return view;
    }

    private Map<String, Object> topicMap(AiTopic topic) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", topic.id);
        view.put("title", topic.title);
        view.put("rationale", topic.rationale == null ? "" : topic.rationale);
        view.put("recommendation", topic.recommendation);
        view.put("status", topic.status);
        view.put("source", parse(topic.source));
        view.put("occurrenceCount", topic.occurrenceCount);
        view.put("createdAt", topic.createdAt);
        view.put("lastSeenAt", topic.lastSeenAt);
        view.put("reviewedAt", topic.reviewedAt);
        view.put("reviewNote", topic.reviewNote == null ? "" : topic.reviewNote);
        view.put("updatedAt", topic.updatedAt);
        AiArticleJob job = AiArticleJob.find("topic.id = ?1 order by updatedAt desc, id desc", topic.id).firstResult();
        if (job != null) {
            view.put("articleJobId", job.id);
            view.put("articleJobStatus", job.status);
            view.put("articleError", job.errorMessage == null ? "" : job.errorMessage);
            view.put("articleNextAttemptAt", job.task == null ? null : job.task.nextAttemptAt);
        }
        return view;
    }

    private String mergeSource(String existing, AutomationPayloadValidator.TopicCandidate candidate,
                               SeedSnapshot seed, RuntimeInferenceResponse response,
                               OffsetDateTime now) {
        ObjectNode source = parse(existing).isObject()
                ? (ObjectNode) parse(existing) : mapper.createObjectNode();
        ArrayNode sources = source.withArray("sources");
        LinkedHashSet<String> existingUrls = new LinkedHashSet<>();
        sources.forEach(value -> {
            if (value.path("url").isTextual()) existingUrls.add(value.path("url").asText());
        });
        candidate.sources().forEach(value -> {
            if (!existingUrls.add(value.url())) return;
            ObjectNode item = mapper.createObjectNode();
            item.put("url", value.url());
            if (!value.title().isBlank()) item.put("title", value.title());
            sources.add(item);
        });
        while (sources.size() > AutomationPayloadValidator.MAX_SOURCES) sources.remove(0);
        ArrayNode queries = source.withArray("queries");
        Set<String> existingQueries = new LinkedHashSet<>();
        queries.forEach(value -> existingQueries.add(value.asText()));
        if (existingQueries.add(seed.query())) queries.add(seed.query());
        ObjectNode primarySeed = source.putObject("primarySeed");
        writeSeed(primarySeed, seed);
        ArrayNode associatedSeeds = source.withArray("seeds");
        boolean knownSeed = false;
        for (JsonNode value : associatedSeeds) {
            if (value.path("id").asLong(-1) == seed.id()) {
                knownSeed = true;
                break;
            }
        }
        if (!knownSeed) writeSeed(associatedSeeds.addObject(), seed);
        if (response.taskId() != null) source.put("taskId", response.taskId());
        if (response.traceId() != null) source.put("traceId", response.traceId());
        source.put("discoveredAt", now.toString());
        ArrayNode keywords = source.withArray("keywords");
        Set<String> existingKeywords = new LinkedHashSet<>();
        keywords.forEach(value -> existingKeywords.add(value.asText()));
        candidate.keywords().forEach(keyword -> {
            if (existingKeywords.add(keyword)) keywords.add(keyword);
        });
        if (response.provenance() != null && response.provenance().has("webSearchItems")) {
            JsonNode searchItems = response.provenance().get("webSearchItems");
            source.set("webSearchItems", searchItems.deepCopy());
            searchItems.forEach(item -> {
                String query = item.path("query").asText("").trim();
                if (!query.isBlank() && existingQueries.add(query)) queries.add(query);
            });
        }
        JsonNode provenance = response.provenance();
        if (provenance != null && provenance.isObject()) {
            copyText(provenance, source, "provider");
            copyText(provenance, source, "threadId");
            copyText(provenance, source, "turnId");
        }
        return json(source);
    }

    private static void copyText(JsonNode source, ObjectNode target, String field) {
        JsonNode value = source.get(field);
        if (value != null && value.isTextual() && !value.asText().isBlank()) {
            target.put(field, value.asText());
        }
    }

    private String topicPrompt(List<SeedSnapshot> seeds, int maxTopics) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are WindBlog's evidence-led topic editor. Use built-in web search to investigate each seed, open primary or authoritative pages, and cross-check current claims. ");
        prompt.append("Propose at most ").append(maxTopics).append(" non-duplicative article topics with a specific tension, question, or decision—not a generic trend summary. ");
        prompt.append("A WRITE topic must support an original editorial angle, identify who benefits, explain why it matters now, and have enough evidence for a substantive article. Use MONITOR when evidence or timeliness is weak and IGNORE for promotional, duplicated, or low-value ideas. ");
        prompt.append("Each rationale must state the proposed thesis direction, reader value, strongest uncertainty or counterpoint, and why the cited sources are sufficient. Every topic needs at least two independent public sources; prefer primary sources and direct reporting. ");
        prompt.append("Assign every topic to exactly one supplied seed by returning its numeric seedId. The seedId must be copied from the matching seed and must never be invented. ");
        prompt.append("Do not use shell, files, arbitrary tools, reproduce source pages, or invent facts or URLs. Return only JSON matching the schema with seedId, title, rationale, keywords, recommendation, and sources. Seeds: ");
        seeds.forEach(seed -> prompt.append('[').append(seed.id()).append(" | ").append(seed.name()).append(" | ").append(seed.query()).append(" | ")
                .append(seed.language()).append(seed.region() == null ? "" : " | " + seed.region()).append("] "));
        return prompt.toString();
    }

    String seedSnapshotJson(List<SeedSnapshot> seeds) {
        ArrayNode values = mapper.createArrayNode();
        seeds.forEach(seed -> writeSeed(values.addObject(), seed));
        return values.toString();
    }

    private static void writeSeed(ObjectNode value, SeedSnapshot seed) {
        value.put("id", seed.id());
        value.put("name", seed.name());
        value.put("query", seed.query());
        value.put("language", seed.language());
        if (seed.region() != null) value.put("region", seed.region());
    }

    private List<SeedSnapshot> readSeeds(String value) {
        try {
            JsonNode node = mapper.readTree(value == null ? "[]" : value);
            List<SeedSnapshot> result = new ArrayList<>();
            node.forEach(item -> result.add(new SeedSnapshot(item.path("id").asLong(), item.path("name").asText(),
                    item.path("query").asText(), item.path("language").asText("zh-CN"),
                    item.path("region").isMissingNode() ? null : item.path("region").asText(null))));
            return result;
        } catch (Exception exception) {
            return List.of();
        }
    }

    private void validateSettings(int interval, int maxSeeds, int maxTopics) {
        if (!INTERVALS.contains(interval)) throw new IllegalArgumentException("intervalMinutes must be 60, 360, 720 or 1440");
        if (maxSeeds < 1 || maxSeeds > 20) throw new IllegalArgumentException("maxSeedsPerRun must be 1..20");
        if (maxTopics < 1 || maxTopics > 50) throw new IllegalArgumentException("maxTopicsPerRun must be 1..50");
    }

    private static void validateLanguage(String language) {
        if (language == null || !language.matches("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})?")) {
            throw new IllegalArgumentException("language must be a BCP-47-like language tag");
        }
    }

    private static int intValue(JsonNode payload, String field, int fallback) {
        JsonNode value = payload == null ? null : payload.get(field);
        if (value == null || value.isNull()) return fallback;
        if (!value.canConvertToInt()) throw new IllegalArgumentException(field + " must be an integer");
        return value.asInt();
    }

    private static boolean booleanValue(JsonNode payload, String field, boolean fallback) {
        JsonNode value = payload == null ? null : payload.get(field);
        if (value == null || value.isNull()) return fallback;
        if (!value.isBoolean()) throw new IllegalArgumentException(field + " must be boolean");
        return value.asBoolean();
    }

    private static String text(JsonNode payload, String field, String fallback) {
        JsonNode value = payload == null ? null : payload.get(field);
        if (value == null || value.isNull()) return fallback;
        if (!value.isTextual()) throw new IllegalArgumentException(field + " must be text");
        return value.asText();
    }

    private static String nullableText(JsonNode payload, String field, String fallback) {
        JsonNode value = payload == null ? null : payload.get(field);
        if (value == null) return fallback;
        if (value.isNull()) return null;
        if (!value.isTextual()) throw new IllegalArgumentException(field + " must be text");
        return value.asText();
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
            throw new IllegalStateException("cannot serialize topic automation JSON", exception);
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current != null && current.getCause() != null) current = current.getCause();
        return current == null || current.getMessage() == null
                ? "topic discovery failed" : current.getMessage();
    }

    record StartContext(Long runId, String idempotencyKey, String traceId,
                        List<SeedSnapshot> seeds, int maxTopics, String profileId) {
    }

    record SeedSnapshot(Long id, String name, String query, String language, String region) {
    }
}
