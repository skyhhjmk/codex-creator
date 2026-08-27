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
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.NoResultException;
import jakarta.transaction.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

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

    public CompletableFuture<RuntimeInferenceResponse> infer(RuntimeInferenceRequest request) {
        if (request == null || request.operation() == null || request.operation().isBlank()
                || request.profileId() == null || request.profileId().isBlank()
                || request.idempotencyKey() == null || request.idempotencyKey().isBlank()
                || request.traceId() == null || request.traceId().isBlank() || request.input() == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "operation, profileId, input, idempotencyKey and traceId are required"));
        }
        AutomationTask existing = findByIdempotency(request.idempotencyKey());
        if (existing != null) return CompletableFuture.completedFuture(toResponse(existing));

        TaskContext context;
        try {
            context = createTask(request);
        } catch (RuntimeException exception) {
            // A concurrent request may have won the unique idempotency key race.
            AutomationTask winner = findByIdempotency(request.idempotencyKey());
            if (winner != null) return CompletableFuture.completedFuture(toResponse(winner));
            return CompletableFuture.failedFuture(exception);
        }

        ProviderAdapter adapter;
        try {
            adapter = providerRegistry.resolve(context.profile());
        } catch (RuntimeException exception) {
            fail(context.taskId(), "PROVIDER_UNAVAILABLE", exception.getMessage());
            return CompletableFuture.completedFuture(toResponse(findById(context.taskId())));
        }
        markStarted(context.taskId(), adapter.getClass().getSimpleName());
        return adapter.infer(new ProviderRequest(request.operation(), context.profile(), request.input(), request.traceId()))
                .handle((response, error) -> {
                    if (error != null) {
                        fail(context.taskId(), "PROVIDER_ERROR", rootMessage(error));
                    } else {
                        complete(context.taskId(), response);
                    }
                    return toResponse(findById(context.taskId()));
                });
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
    void complete(Long taskId, ProviderResponse response) {
        AutomationTask task = findById(taskId);
        if (task == null) return;
        task.status = "SUCCEEDED";
        task.outputJson = json(response.output());
        task.usageJson = json(response.usage());
        task.provenanceJson = json(response.provenance());
        task.completedAt = OffsetDateTime.now();
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
        return toResponse(findById(id));
    }

    private AutomationTask findByIdempotency(String key) {
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
