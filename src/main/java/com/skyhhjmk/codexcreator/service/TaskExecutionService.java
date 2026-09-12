package com.skyhhjmk.codexcreator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skyhhjmk.codexcreator.domain.*;
import com.skyhhjmk.codexcreator.provider.ProviderAdapter;
import com.skyhhjmk.codexcreator.provider.ProviderRegistry;
import com.skyhhjmk.codexcreator.provider.ProviderRequest;
import com.skyhhjmk.codexcreator.provider.ProviderResponse;
import com.skyhhjmk.codexcreator.provider.CodexAppServerProviderAdapter;
import com.skyhhjmk.codexcreator.api.RuntimeInferenceRequest;
import com.skyhhjmk.codexcreator.api.RuntimeInferenceResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.OffsetDateTime;
import java.time.Instant;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class TaskExecutionService {
    private static final Pattern USAGE_LIMIT = Pattern.compile(
            "(?i)(usage\\s*limit|quota\\s*(?:is\\s*)?(?:exceeded|exhausted)|billing\\s*limit)");
    private static final Pattern RETRY_AT = Pattern.compile(
            "(?i)try again at\\s+(\\d{1,2}):(\\d{2})\\s*(am|pm)?");
    @Inject
    ObjectMapper mapper;

    @Inject
    ProviderRegistry providerRegistry;

    @Inject
    AuditLogService auditLogService;

    @Inject
    RuntimeInferenceConfig runtimeConfig;

    @Inject
    Instance<TaskExecutionService> self;

    @Inject
    TaskCacheService taskCache;

    @Inject
    CodexQuotaService quota;

    @ConfigProperty(name = "codex.creator.quota.pause-poll-interval", defaultValue = "60S")
    Duration quotaPausePollInterval;

    /** In-process guard; Redis below makes recovery single-owner across instances. */
    private final Set<Long> activeTaskIds = ConcurrentHashMap.newKeySet();

    public CompletableFuture<RuntimeInferenceResponse> infer(RuntimeInferenceRequest request) {
        if (request == null || request.operation() == null || request.operation().isBlank()
                || request.profileId() == null || request.profileId().isBlank()
                || request.idempotencyKey() == null || request.idempotencyKey().isBlank()
                || request.traceId() == null || request.traceId().isBlank() || request.input() == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "operation, profileId, input, idempotencyKey and traceId are required"));
        }
        String cacheKey = CacheKey.forTask(mapper, request.operation(), request.input(),
                request.profileId(), request.promptVersion());
        TaskContext context;
        AutomationTask existing = self.get().findByIdempotencyInTransaction(request.idempotencyKey());
        if (existing != null) {
            if (!matches(existing, request)) {
                return CompletableFuture.failedFuture(new IllegalArgumentException(
                        "idempotency key is already bound to a different task"));
            }
            TaskContext retryContext = "FAILED".equals(existing.status)
                    ? self.get().requeueFailedTask(existing.id) : null;
            if (retryContext == null) {
                return CompletableFuture.completedFuture(self.get().snapshot(existing.id));
            }
            // Continue through the normal provider/lock/retry path below. The
            // durable task row is reused, so a manual retry cannot create a
            // second task for the same request.
            context = retryContext;
        } else {
            context = null;
        }

        if (context == null) {
            try {
                context = self.get().createTask(request);
            } catch (RuntimeException exception) {
                // A concurrent request may have won the unique idempotency key race.
                AutomationTask winner = self.get().findByIdempotencyInTransaction(request.idempotencyKey());
                if (winner != null) {
                    if (!matches(winner, request)) {
                        return CompletableFuture.failedFuture(new IllegalArgumentException(
                                "idempotency key is already bound to a different task"));
                    }
                    TaskContext concurrentRetry = "FAILED".equals(winner.status)
                            ? self.get().requeueFailedTask(winner.id) : null;
                    if (concurrentRetry == null) {
                        return CompletableFuture.completedFuture(self.get().snapshot(winner.id));
                    }
                    context = concurrentRetry;
                } else {
                    return CompletableFuture.failedFuture(exception);
                }
            }
        }

        ProviderAdapter adapter;
        try {
            adapter = providerRegistry.resolve(context.profile());
        } catch (RuntimeException exception) {
            self.get().fail(context.taskId(), "PROVIDER_UNAVAILABLE", exception.getMessage());
            return CompletableFuture.completedFuture(self.get().snapshot(context.taskId()));
        }
        return admitAndExecute(context, request, adapter, cacheKey);
    }

    private CompletableFuture<RuntimeInferenceResponse> admitAndExecute(TaskContext context,
                                                                          RuntimeInferenceRequest request,
                                                                          ProviderAdapter adapter,
                                                                          String cacheKey) {
        if (!(adapter instanceof CodexAppServerProviderAdapter)) {
            return executeAdmitted(context, request, adapter, cacheKey);
        }
        return quota.admission(request.bypassQuota()).thenCompose(decision -> {
            if (!decision.allowed()) {
                self.get().recordQuotaPause(context.taskId(), decision.status(), decision.retryAt());
                return CompletableFuture.completedFuture(self.get().snapshot(context.taskId()));
            }
            return executeAdmitted(context, request, adapter, cacheKey);
        });
    }

    private CompletableFuture<RuntimeInferenceResponse> executeAdmitted(TaskContext context,
                                                                          RuntimeInferenceRequest request,
                                                                          ProviderAdapter adapter,
                                                                          String cacheKey) {
        // Topic discovery and article writing must retain the app-server search
        // provenance captured for that invocation. Replaying only the cached
        // model JSON would make a fresh task look as if no web search occurred.
        boolean cacheOutput = isOutputCacheable(request.operation());
        TaskContext executionContext = context;
        activeTaskIds.add(executionContext.taskId());
        try {
            return executeWithCacheLock(executionContext, request, adapter, cacheKey,
                    Math.max(0, runtimeConfig.lockWaitSeconds() * 4), cacheOutput)
                    .whenComplete((ignored, error) -> activeTaskIds.remove(executionContext.taskId()));
        } catch (RuntimeException exception) {
            activeTaskIds.remove(executionContext.taskId());
            throw exception;
        }
    }

    /** Replays durable non-terminal tasks after an application restart. */
    @io.quarkus.scheduler.Scheduled(every = "30s", identity = "codex-task-recovery")
    void recoverOrphanedTasks() {
        for (RecoveryCandidate candidate : self.get().recoverableTasks()) {
            if (!activeTaskIds.add(candidate.taskId())) continue;
            String lockKey = "task-recovery-" + candidate.taskId();
            TaskCacheService.LockAttempt lock = taskCache.acquire(lockKey,
                    Duration.ofSeconds(Math.max(30, runtimeConfig.lockTtlSeconds())));
            if (lock.status() != TaskCacheService.LockStatus.ACQUIRED) {
                activeTaskIds.remove(candidate.taskId());
                continue;
            }
            try {
                RecoveryCandidate claimed = self.get().claimRecovery(candidate.taskId());
                if (claimed == null) {
                    activeTaskIds.remove(candidate.taskId());
                    taskCache.release(lockKey, lock.token());
                    continue;
                }
                ProviderAdapter adapter = providerRegistry.resolve(claimed.profile());
                RuntimeInferenceRequest request = new RuntimeInferenceRequest(
                        claimed.operation(), claimed.profile().profileId, claimed.input(),
                        claimed.idempotencyKey(), claimed.traceId(), claimed.promptVersion(), false);
                String cacheKey = CacheKey.forTask(mapper, request.operation(), request.input(),
                        request.profileId(), request.promptVersion());
                admitAndExecute(new TaskContext(claimed.taskId(), claimed.profile()), request,
                        adapter, cacheKey)
                        .whenComplete((ignored, error) -> {
                            activeTaskIds.remove(claimed.taskId());
                            taskCache.release(lockKey, lock.token());
                        });
            } catch (RuntimeException exception) {
                self.get().fail(candidate.taskId(), "RECOVERY_FAILED", rootMessage(exception));
                activeTaskIds.remove(candidate.taskId());
                taskCache.release(lockKey, lock.token());
            }
        }
    }

    @Transactional
    List<RecoveryCandidate> recoverableTasks() {
        OffsetDateTime now = OffsetDateTime.now();
        return AutomationTask.<AutomationTask>find(
                        "status = ?1 or status = ?2 or status = ?3", "QUEUED", "RUNNING", "RETRYING")
                .page(0, 50).list().stream()
                .filter(task -> !"RETRYING".equals(task.status)
                        || task.nextAttemptAt == null || !task.nextAttemptAt.isAfter(now))
                .map(this::recoveryCandidate)
                .toList();
    }

    @Transactional
    RecoveryCandidate claimRecovery(Long taskId) {
        AutomationTask task = AutomationTask.find("id", taskId)
                .withLock(LockModeType.PESSIMISTIC_WRITE).firstResult();
        if (task == null || "SUCCEEDED".equals(task.status) || "FAILED".equals(task.status)) return null;
        if ("RETRYING".equals(task.status) && task.nextAttemptAt != null
                && task.nextAttemptAt.isAfter(OffsetDateTime.now())) return null;
        if (task.profile == null || parse(task.inputJson) == null) {
            task.status = "FAILED";
            task.errorCode = "INVALID_TASK";
            task.errorMessage = "durable task input or profile is invalid";
            task.completedAt = OffsetDateTime.now();
            return null;
        }
        // A provider-advertised usage reset is not a failed generation attempt:
        // it is a durable wait state and may recur across several reset windows.
        if (!"USAGE_LIMIT".equals(task.errorCode)
                && task.attemptCount >= Math.max(1, runtimeConfig.maxAttempts())) {
            task.status = "FAILED";
            task.errorCode = "PROVIDER_ERROR";
            task.errorMessage = "maximum task attempts exhausted during recovery";
            task.completedAt = OffsetDateTime.now();
            return null;
        }
        if ("RUNNING".equals(task.status)) {
            TaskAttempt attempt = latestAttempt(task);
            if (attempt != null && "RUNNING".equals(attempt.status)) {
                attempt.status = "FAILED";
                attempt.completedAt = OffsetDateTime.now();
                attempt.errorCode = "TASK_RECOVERED";
                attempt.errorMessage = "previous process ended while the task was running";
            }
        }
        task.status = "QUEUED";
        task.nextAttemptAt = null;
        return recoveryCandidate(task);
    }

    private CompletableFuture<RuntimeInferenceResponse> executeWithCacheLock(TaskContext context,
                                                                                RuntimeInferenceRequest request,
                                                                                ProviderAdapter adapter,
                                                                                String cacheKey,
                                                                                int remainingPolls,
                                                                                boolean cacheOutput) {
        if (cacheOutput) {
            var cached = taskCache.get(cacheKey);
            if (cached.isPresent()) return completeFromCache(context.taskId(), cached.get(), cacheKey);
        }

        TaskCacheService.LockAttempt lock = taskCache.acquire(cacheKey,
                Duration.ofSeconds(Math.max(1, runtimeConfig.lockTtlSeconds())));
        if (lock.status() == TaskCacheService.LockStatus.UNAVAILABLE) {
            // Cache reads may continue without Redis, but the provider slot is
            // fail-closed so quota/concurrency guarantees are not lost.
            return invokeWithProviderSlot(context, request, adapter, cacheKey, 0, cacheOutput);
        }
        if (lock.status() == TaskCacheService.LockStatus.ACQUIRED) {
            return invokeWithProviderSlot(context, request, adapter, cacheKey, 0, cacheOutput)
                    .whenComplete((ignored, error) -> taskCache.release(cacheKey, lock.token()));
        }
        if (remainingPolls <= 0) {
            self.get().fail(context.taskId(), "TASK_LOCK_BUSY",
                    "another task with the same input is still running");
            return CompletableFuture.completedFuture(self.get().snapshot(context.taskId()));
        }
        return delayed(Duration.ofMillis(250))
                .thenCompose(ignored -> executeWithCacheLock(context, request, adapter, cacheKey,
                remainingPolls - 1, cacheOutput));
    }

    private CompletableFuture<RuntimeInferenceResponse> invokeWithProviderSlot(TaskContext context,
                                                                                 RuntimeInferenceRequest request,
                                                                                 ProviderAdapter adapter,
                                                                                 String cacheKey,
                                                                                 int attemptIndex,
                                                                                 boolean cacheOutput) {
        if (!(adapter instanceof CodexAppServerProviderAdapter)) {
            return invokeWithRetries(context, request, adapter, cacheKey, attemptIndex, cacheOutput);
        }
        TaskCacheService.LockAttempt slot = taskCache.acquire("codex-provider-inflight", Duration.ofMinutes(30));
        if (slot.status() != TaskCacheService.LockStatus.ACQUIRED) {
            String code = slot.status() == TaskCacheService.LockStatus.BUSY
                    ? "CODEX_CONCURRENCY_WAIT" : "CODEX_CONCURRENCY_UNAVAILABLE";
            self.get().recordQuotaPause(context.taskId(), code, Instant.now().plusSeconds(60));
            return CompletableFuture.completedFuture(self.get().snapshot(context.taskId()));
        }
        return invokeWithRetries(context, request, adapter, cacheKey, attemptIndex, cacheOutput)
                .whenComplete((ignored, error) -> taskCache.release("codex-provider-inflight", slot.token()));
    }

    private CompletableFuture<RuntimeInferenceResponse> completeFromCache(Long taskId, JsonNode output, String cacheKey) {
        self.get().complete(taskId, new ProviderResponse(output, ProviderResponse.Usage.empty(),
                Map.of("cache", "redis", "cacheKey", cacheKey), null, null, null));
        return CompletableFuture.completedFuture(self.get().snapshot(taskId));
    }

    private CompletableFuture<RuntimeInferenceResponse> invokeWithRetries(TaskContext context,
                                                                            RuntimeInferenceRequest request,
                                                                            ProviderAdapter adapter,
                                                                            String cacheKey,
                                                                            int attemptIndex,
                                                                            boolean cacheOutput) {
        self.get().markStarted(context.taskId(), adapter.getClass().getSimpleName());
        ProviderRequest providerRequest = new ProviderRequest(request.operation(), context.profile(), request.input(),
                request.traceId(), context.taskId());
        CompletableFuture<ProviderResponse> providerFuture;
        try {
            providerFuture = adapter.infer(providerRequest);
        } catch (RuntimeException exception) {
            providerFuture = CompletableFuture.failedFuture(exception);
        }
        return providerFuture.handle((response, error) -> {
            if (error == null && response != null) {
                if (cacheOutput) {
                    taskCache.put(cacheKey, response.output(), Duration.ofMinutes(30));
                }
                self.get().complete(context.taskId(), response);
                return CompletableFuture.completedFuture(self.get().snapshot(context.taskId()));
            }

            String message = error == null ? "provider returned no response" : rootMessage(error);
            if (isUsageLimit(message)) {
                Duration delay = usageLimitDelay(message, OffsetDateTime.now(), runtimeConfig.usageLimitFallbackDelay(),
                        runtimeConfig.usageLimitRetryZone());
                self.get().recordAttemptFailure(context.taskId(), "USAGE_LIMIT", message, delay);
                return CompletableFuture.completedFuture(self.get().snapshot(context.taskId()));
            }
            int maxAttempts = Math.max(1, runtimeConfig.maxAttempts());
            if (attemptIndex + 1 < maxAttempts) {
                Duration delay = retryDelay(attemptIndex);
                self.get().recordAttemptFailure(context.taskId(), "PROVIDER_ERROR", message, delay);
                return delayed(delay).thenCompose(ignored ->
                        invokeWithRetries(context, request, adapter, cacheKey, attemptIndex + 1, cacheOutput));
            }
            self.get().fail(context.taskId(), "PROVIDER_ERROR", message);
            return CompletableFuture.completedFuture(self.get().snapshot(context.taskId()));
        }).thenCompose(stage -> stage);
    }

    private static boolean isOutputCacheable(String operation) {
        return !"topic".equalsIgnoreCase(operation) && !"article".equalsIgnoreCase(operation);
    }

    private boolean allowsOperation(ModelProfile profile, String operation) {
        try {
            JsonNode allowed = mapper.readTree(profile.allowedOperations == null ? "[]" : profile.allowedOperations);
            if (!allowed.isArray()) return false;
            for (JsonNode value : allowed) {
                if (value.isTextual() && value.asText().equalsIgnoreCase(operation)) return true;
            }
            return false;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean matches(AutomationTask task, RuntimeInferenceRequest request) {
        if (!Objects.equals(task.operation, request.operation())
                || task.profile == null || !Objects.equals(task.profile.profileId, request.profileId())) {
            return false;
        }
        try {
            return mapper.readTree(task.inputJson).equals(request.input())
                    && Objects.equals(normalizePromptVersion(task.promptVersion),
                    normalizePromptVersion(request.promptVersion()));
        } catch (Exception ignored) {
            return false;
        }
    }

    private Duration retryDelay(int attemptIndex) {
        long base = Math.min(30_000L, Math.max(0, runtimeConfig.retryDelayMillis()));
        long multiplier = 1L << Math.min(attemptIndex, 5);
        return Duration.ofMillis(Math.min(30_000L, base * multiplier));
    }

    static boolean isUsageLimit(String message) {
        return message != null && USAGE_LIMIT.matcher(message).find();
    }

    static Duration usageLimitDelay(String message, OffsetDateTime now, Duration fallback, String retryZone) {
        Duration safeFallback = fallback == null || fallback.isNegative() || fallback.isZero()
                ? Duration.ofMinutes(30) : fallback;
        if (message == null || now == null) return safeFallback;
        Matcher matcher = RETRY_AT.matcher(message);
        if (!matcher.find()) return safeFallback;
        try {
            int hour = Integer.parseInt(matcher.group(1));
            int minute = Integer.parseInt(matcher.group(2));
            String suffix = matcher.group(3);
            if (suffix != null) {
                if (hour < 1 || hour > 12) return safeFallback;
                hour = hour % 12 + ("pm".equalsIgnoreCase(suffix) ? 12 : 0);
            } else if (hour > 23) {
                return safeFallback;
            }
            ZoneId zone = ZoneId.of(retryZone == null || retryZone.isBlank() ? "Asia/Shanghai" : retryZone);
            LocalDateTime target = LocalDateTime.of(now.atZoneSameInstant(zone).toLocalDate(),
                    LocalTime.of(hour, minute));
            OffsetDateTime retryAt = target.atZone(zone).toOffsetDateTime();
            if (!retryAt.isAfter(now)) retryAt = retryAt.plusDays(1);
            // Leave a small margin after the provider's advertised reset time.
            return Duration.between(now, retryAt).plusMinutes(1);
        } catch (RuntimeException ignored) {
            return safeFallback;
        }
    }

    private static CompletableFuture<Void> delayed(Duration delay) {
        return CompletableFuture.runAsync(() -> { },
                CompletableFuture.delayedExecutor(Math.max(0, delay.toMillis()), TimeUnit.MILLISECONDS));
    }

    @Transactional
    TaskContext createTask(RuntimeInferenceRequest request) {
        ModelProfile profile = ModelProfile.findById(request.profileId());
        if (profile == null || !profile.enabled) {
            throw new IllegalArgumentException("model profile is not available: " + request.profileId());
        }
        if (!allowsOperation(profile, request.operation())) {
            throw new IllegalArgumentException("operation is not allowed for model profile: " + request.operation());
        }
        AutomationTask task = new AutomationTask();
        task.operation = request.operation();
        task.profile = profile;
        task.idempotencyKey = request.idempotencyKey();
        task.traceId = request.traceId();
        task.promptVersion = normalizePromptVersion(request.promptVersion());
        task.status = "QUEUED";
        task.inputJson = json(request.input());
        task.createdAt = OffsetDateTime.now();
        task.attemptCount = 0;
        task.persist();
        return new TaskContext(task.id, profile);
    }

    @Transactional
    void markStarted(Long taskId, String provider) {
        AutomationTask task = findById(taskId);
        if (task == null) return;
        TaskAttempt previousAttempt = latestAttempt(task);
        int nextAttemptNumber = previousAttempt == null ? 1 : previousAttempt.attemptNumber + 1;
        task.status = "RUNNING";
        task.startedAt = OffsetDateTime.now();
        task.completedAt = null;
        task.nextAttemptAt = null;
        task.errorCode = null;
        task.errorMessage = null;
        task.attemptCount++;
        TaskAttempt attempt = new TaskAttempt();
        attempt.task = task;
        attempt.attemptNumber = nextAttemptNumber;
        attempt.provider = provider;
        attempt.status = "RUNNING";
        attempt.startedAt = task.startedAt;
        attempt.persist();
    }

    @Transactional
    void recordAttemptFailure(Long taskId, String code, String message, Duration retryDelay) {
        AutomationTask task = findById(taskId);
        if (task == null) return;
        OffsetDateTime now = OffsetDateTime.now();
        task.status = "RETRYING";
        task.errorCode = code;
        task.errorMessage = message == null ? "provider request failed" : message;
        task.nextAttemptAt = now.plus(retryDelay == null ? Duration.ZERO : retryDelay);
        TaskAttempt attempt = latestAttempt(task);
        if (attempt != null) {
            attempt.status = "FAILED";
            attempt.completedAt = now;
            attempt.errorCode = code;
            attempt.errorMessage = task.errorMessage;
        }
    }

    @Transactional
    void recordQuotaPause(Long taskId, String code, Instant retryAt) {
        AutomationTask task = findById(taskId);
        if (task == null) return;
        OffsetDateTime now = OffsetDateTime.now();
        Duration pollInterval = quotaPausePollInterval == null || quotaPausePollInterval.isNegative()
                || quotaPausePollInterval.isZero() ? Duration.ofMinutes(1) : quotaPausePollInterval;
        OffsetDateTime pollAt = now.plus(pollInterval);
        OffsetDateTime resetAt = retryAt == null ? pollAt : OffsetDateTime.ofInstant(retryAt.plusSeconds(60), now.getOffset());
        task.status = "RETRYING";
        task.errorCode = code;
        task.errorMessage = code;
        task.nextAttemptAt = resetAt.isBefore(pollAt) ? resetAt : pollAt;
    }

    @Transactional
    void complete(Long taskId, ProviderResponse response) {
        AutomationTask task = findById(taskId);
        if (task == null) return;
        task.status = "SUCCEEDED";
        task.outputJson = json(response.output());
        task.usageJson = usageJson(response.usage());
        task.provenanceJson = json(response.provenance());
        task.completedAt = OffsetDateTime.now();
        task.nextAttemptAt = null;
        TaskAttempt attempt = latestAttempt(task);
        if (attempt != null) {
            attempt.status = "SUCCEEDED";
            attempt.completedAt = task.completedAt;
            attempt.usageJson = task.usageJson;
        }
        auditLogService.log("SYSTEM", "provider", "task.succeeded", "automation_task", String.valueOf(task.id), task.traceId,
                Map.of("operation", task.operation));
    }

    @Transactional
    void fail(Long taskId, String code, String message) {
        AutomationTask task = findById(taskId);
        if (task == null) return;
        task.status = "FAILED";
        task.errorCode = code;
        task.errorMessage = message == null ? "provider request failed" : message;
        task.completedAt = OffsetDateTime.now();
        task.nextAttemptAt = null;
        TaskAttempt attempt = latestAttempt(task);
        if (attempt != null) {
            attempt.status = "FAILED";
            attempt.completedAt = task.completedAt;
            attempt.errorCode = code;
            attempt.errorMessage = task.errorMessage;
        }
        auditLogService.log("SYSTEM", "provider", "task.failed", "automation_task", String.valueOf(task.id), task.traceId,
                Map.of("code", code));
    }

    @Transactional
    public RuntimeInferenceResponse get(Long id) {
        return snapshot(id);
    }

    @Transactional
    public RuntimeInferenceResponse snapshot(Long id) {
        return toResponse(findById(id));
    }

    @Transactional
    public Map<String, Object> executionView(Long taskId) {
        AutomationTask task = findById(taskId);
        if (task == null) return Map.of();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("status", task.status);
        view.put("attemptCount", task.attemptCount);
        view.put("startedAt", task.startedAt);
        view.put("nextAttemptAt", task.nextAttemptAt);
        view.put("error", task.errorMessage == null ? "" : task.errorMessage);
        List<Map<String, Object>> attempts = TaskAttempt.<TaskAttempt>find(
                        "task.id = ?1 order by attemptNumber desc", taskId)
                .page(0, 10).list().stream().map(this::attemptView).toList();
        view.put("attempts", attempts);
        CodexTurn turn = CodexTurn.find("task.id = ?1 order by startedAt desc", taskId).firstResult();
        view.put("turnStatus", turn == null ? null : turn.status);
        view.put("events", turn == null ? List.of() : parse(turn.progressJson));
        return view;
    }

    private Map<String, Object> attemptView(TaskAttempt attempt) {
        // A running attempt has no completedAt yet. Map.of rejects null values
        // and used to turn a harmless in-progress task into an API NPE.
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("number", attempt.attemptNumber);
        view.put("status", attempt.status);
        view.put("provider", attempt.provider == null ? "" : attempt.provider);
        view.put("startedAt", attempt.startedAt);
        view.put("completedAt", attempt.completedAt);
        view.put("error", attempt.errorMessage == null ? "" : attempt.errorMessage);
        return view;
    }

    @Transactional
    public AutomationTask findByIdempotencyInTransaction(String key) {
        return AutomationTask.find("idempotencyKey", key).firstResult();
    }

    /** Requeue an explicitly retried terminal task without changing its idempotency key. */
    @Transactional
    TaskContext requeueFailedTask(Long taskId) {
        AutomationTask task = AutomationTask.find("id = ?1", taskId)
                .withLock(LockModeType.PESSIMISTIC_WRITE).firstResult();
        if (task == null || !"FAILED".equals(task.status)) return null;
        task.status = "QUEUED";
        task.errorCode = null;
        task.errorMessage = null;
        task.completedAt = null;
        task.nextAttemptAt = null;
        task.outputJson = null;
        task.usageJson = null;
        task.provenanceJson = null;
        task.attemptCount = 0;
        return new TaskContext(task.id, task.profile);
    }

    private AutomationTask findById(Long id) {
        return id == null ? null : AutomationTask.findById(id);
    }

    private TaskAttempt latestAttempt(AutomationTask task) {
        return TaskAttempt.find("task.id = ?1 order by attemptNumber desc", task.id).firstResult();
    }

    private RuntimeInferenceResponse toResponse(AutomationTask task) {
        if (task == null) return null;
        return new RuntimeInferenceResponse(task.id, task.status, task.operation,
                task.profile == null ? null : task.profile.profileId,
                parse(task.outputJson), parse(task.usageJson), parse(task.provenanceJson),
                task.errorCode, task.errorMessage, task.traceId,
                task.createdAt, task.completedAt);
    }

    private JsonNode parse(String value) {
        if (value == null || value.isBlank()) return null;
        try { return mapper.readTree(value); }
        catch (Exception ignored) { return mapper.getNodeFactory().textNode(value); }
    }

    private String json(Object value) {
        try { return value == null ? null : mapper.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException("cannot serialize task JSON", exception); }
    }

    private String usageJson(ProviderResponse.Usage usage) {
        if (usage == null) return null;
        ObjectNode value = mapper.createObjectNode();
        value.put("inputTokens", usage.inputTokens());
        value.put("outputTokens", usage.outputTokens());
        value.put("totalTokens", usage.totalTokens());
        return value.toString();
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static String normalizePromptVersion(String value) {
        return value == null || value.isBlank() ? "1" : value.trim();
    }

    private record TaskContext(Long taskId, ModelProfile profile) {}

    private RecoveryCandidate recoveryCandidate(AutomationTask task) {
        return new RecoveryCandidate(task.id, task.operation, task.profile, parse(task.inputJson),
                task.idempotencyKey, task.traceId, normalizePromptVersion(task.promptVersion), task.attemptCount);
    }

    private record RecoveryCandidate(Long taskId, String operation, ModelProfile profile,
                                     JsonNode input, String idempotencyKey, String traceId, String promptVersion,
                                     int attemptCount) {}
}
