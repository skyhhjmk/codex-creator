package com.skyhhjmk.codexcreator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skyhhjmk.codexcreator.domain.*;
import com.skyhhjmk.codexcreator.provider.ProviderAdapter;
import com.skyhhjmk.codexcreator.provider.ProviderRegistry;
import com.skyhhjmk.codexcreator.provider.ProviderRequest;
import com.skyhhjmk.codexcreator.provider.ProviderResponse;
import com.skyhhjmk.codexcreator.api.RuntimeInferenceRequest;
import com.skyhhjmk.codexcreator.api.RuntimeInferenceResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.OffsetDateTime;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class TaskExecutionService {
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
        AutomationTask existing = self.get().findByIdempotencyInTransaction(request.idempotencyKey());
        if (existing != null) return CompletableFuture.completedFuture(self.get().snapshot(existing.id));

        TaskContext context;
        try {
            context = self.get().createTask(request);
        } catch (RuntimeException exception) {
            // A concurrent request may have won the unique idempotency key race.
            AutomationTask winner = self.get().findByIdempotencyInTransaction(request.idempotencyKey());
            if (winner != null) return CompletableFuture.completedFuture(self.get().snapshot(winner.id));
            return CompletableFuture.failedFuture(exception);
        }

        ProviderAdapter adapter;
        try {
            adapter = providerRegistry.resolve(context.profile());
        } catch (RuntimeException exception) {
            self.get().fail(context.taskId(), "PROVIDER_UNAVAILABLE", exception.getMessage());
            return CompletableFuture.completedFuture(self.get().snapshot(context.taskId()));
        }
        return executeWithCacheLock(context, request, adapter, cacheKey,
                Math.max(0, runtimeConfig.lockWaitSeconds() * 4));
    }

    private CompletableFuture<RuntimeInferenceResponse> executeWithCacheLock(TaskContext context,
                                                                                RuntimeInferenceRequest request,
                                                                                ProviderAdapter adapter,
                                                                                String cacheKey,
                                                                                int remainingPolls) {
        var cached = taskCache.get(cacheKey);
        if (cached.isPresent()) return completeFromCache(context.taskId(), cached.get(), cacheKey);

        TaskCacheService.LockAttempt lock = taskCache.acquire(cacheKey,
                Duration.ofSeconds(Math.max(1, runtimeConfig.lockTtlSeconds())));
        if (lock.status() == TaskCacheService.LockStatus.UNAVAILABLE) {
            // Redis is deliberately best-effort. Continue without a lock when it
            // is down, while retaining durable PostgreSQL task state.
            return invokeWithRetries(context, request, adapter, cacheKey, 0);
        }
        if (lock.status() == TaskCacheService.LockStatus.ACQUIRED) {
            return invokeWithRetries(context, request, adapter, cacheKey, 0)
                    .whenComplete((ignored, error) -> taskCache.release(cacheKey, lock.token()));
        }
        if (remainingPolls <= 0) {
            self.get().fail(context.taskId(), "TASK_LOCK_BUSY",
                    "another task with the same input is still running");
            return CompletableFuture.completedFuture(self.get().snapshot(context.taskId()));
        }
        return delayed(Duration.ofMillis(250))
                .thenCompose(ignored -> executeWithCacheLock(context, request, adapter, cacheKey, remainingPolls - 1));
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
                                                                            int attemptIndex) {
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
                taskCache.put(cacheKey, response.output(), Duration.ofMinutes(30));
                self.get().complete(context.taskId(), response);
                return CompletableFuture.completedFuture(self.get().snapshot(context.taskId()));
            }

            String message = error == null ? "provider returned no response" : rootMessage(error);
            int maxAttempts = Math.max(1, runtimeConfig.maxAttempts());
            if (attemptIndex + 1 < maxAttempts) {
                Duration delay = retryDelay(attemptIndex);
                self.get().recordAttemptFailure(context.taskId(), "PROVIDER_ERROR", message, delay);
                return delayed(delay).thenCompose(ignored ->
                        invokeWithRetries(context, request, adapter, cacheKey, attemptIndex + 1));
            }
            self.get().fail(context.taskId(), "PROVIDER_ERROR", message);
            return CompletableFuture.completedFuture(self.get().snapshot(context.taskId()));
        }).thenCompose(stage -> stage);
    }

    private Duration retryDelay(int attemptIndex) {
        long base = Math.min(30_000L, Math.max(0, runtimeConfig.retryDelayMillis()));
        long multiplier = 1L << Math.min(attemptIndex, 5);
        return Duration.ofMillis(Math.min(30_000L, base * multiplier));
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
        AutomationTask task = new AutomationTask();
        task.operation = request.operation();
        task.profile = profile;
        task.idempotencyKey = request.idempotencyKey();
        task.traceId = request.traceId();
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
        task.status = "RUNNING";
        task.startedAt = OffsetDateTime.now();
        task.completedAt = null;
        task.nextAttemptAt = null;
        task.errorCode = null;
        task.errorMessage = null;
        task.attemptCount++;
        TaskAttempt attempt = new TaskAttempt();
        attempt.task = task;
        attempt.attemptNumber = task.attemptCount;
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
    void complete(Long taskId, ProviderResponse response) {
        AutomationTask task = findById(taskId);
        if (task == null) return;
        task.status = "SUCCEEDED";
        task.outputJson = json(response.output());
        task.usageJson = json(response.usage());
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
    public AutomationTask findByIdempotencyInTransaction(String key) {
        return AutomationTask.find("idempotencyKey", key).firstResult();
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

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private record TaskContext(Long taskId, ModelProfile profile) {}
}
