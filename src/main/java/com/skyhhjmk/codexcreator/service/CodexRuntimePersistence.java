package com.skyhhjmk.codexcreator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skyhhjmk.codexcreator.domain.AutomationTask;
import com.skyhhjmk.codexcreator.domain.CodexThread;
import com.skyhhjmk.codexcreator.domain.CodexTurn;
import com.skyhhjmk.codexcreator.domain.ModelProfile;
import com.skyhhjmk.codexcreator.config.AppServerConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.OffsetDateTime;

/** Stores the external app-server identifiers without coupling provider code to WindBlog. */
@ApplicationScoped
public class CodexRuntimePersistence {
    @Inject
    ObjectMapper mapper;

    @Inject
    AppServerConfig appServerConfig;

    @Transactional
    public Long threadStarted(Long taskId, ModelProfile profile, String externalThreadId) {
        AutomationTask task = AutomationTask.findById(taskId);
        if (task == null) return null;
        CodexThread thread = new CodexThread();
        thread.externalThreadId = externalThreadId;
        thread.profile = profile;
        thread.schemaVersion = appServerConfig.schemaVersion();
        thread.status = "ACTIVE";
        thread.createdAt = OffsetDateTime.now();
        thread.updatedAt = thread.createdAt;
        thread.persist();
        task.thread = thread;
        return thread.id;
    }

    @Transactional
    public void turnStarted(Long taskId, Long threadId, String externalTurnId, JsonNode input) {
        AutomationTask task = AutomationTask.findById(taskId);
        CodexThread thread = threadId == null ? null : CodexThread.findById(threadId);
        if (task == null) return;
        CodexTurn turn = new CodexTurn();
        turn.task = task;
        turn.thread = thread;
        turn.externalTurnId = externalTurnId;
        turn.status = "RUNNING";
        turn.inputJson = json(input);
        turn.progressJson = progress("TURN_STARTED", "已向 Codex 提交生成请求", null).toString();
        turn.startedAt = OffsetDateTime.now();
        turn.persist();
    }

    @Transactional
    public void turnCompleted(String externalTurnId, JsonNode output) {
        CodexTurn turn = CodexTurn.find("externalTurnId", externalTurnId).firstResult();
        if (turn == null) return;
        appendProgress(turn, "TURN_COMPLETED", "Codex 已返回生成结果", null);
        turn.status = "SUCCEEDED";
        turn.outputJson = json(output);
        turn.completedAt = OffsetDateTime.now();
        if (turn.thread != null) {
            turn.thread.updatedAt = turn.completedAt;
            turn.thread.status = "ACTIVE";
        }
    }

    @Transactional
    public void turnFailed(String externalTurnId, Throwable error) {
        CodexTurn turn = CodexTurn.find("externalTurnId", externalTurnId).firstResult();
        if (turn == null) return;
        turn.status = "FAILED";
        appendProgress(turn, "TURN_FAILED", message(error), null);
        turn.errorJson = json(mapper.getNodeFactory().textNode(message(error)));
        turn.completedAt = OffsetDateTime.now();
    }

    @Transactional
    public void turnProgress(String externalTurnId, String stage, String message, String query) {
        CodexTurn turn = CodexTurn.find("externalTurnId", externalTurnId).firstResult();
        if (turn == null || !"RUNNING".equals(turn.status)) return;
        appendProgress(turn, stage, message, query);
    }

    private void appendProgress(CodexTurn turn, String stage, String message, String query) {
        ArrayNode events = readProgress(turn.progressJson);
        ObjectNode event = progress(stage, message, query);
        events.add(event);
        while (events.size() > 30) events.remove(0);
        turn.progressJson = events.toString();
    }

    private ArrayNode readProgress(String value) {
        try {
            JsonNode node = mapper.readTree(value == null ? "[]" : value);
            if (node.isArray()) return (ArrayNode) node;
        } catch (Exception ignored) {
        }
        return mapper.createArrayNode();
    }

    private ObjectNode progress(String stage, String message, String query) {
        ObjectNode event = mapper.createObjectNode();
        event.put("stage", stage);
        event.put("message", message == null || message.isBlank() ? "正在执行" : message);
        if (query != null && !query.isBlank()) event.put("query", query);
        event.put("at", OffsetDateTime.now().toString());
        return event;
    }

    private String json(JsonNode value) {
        try {
            return value == null ? null : mapper.writeValueAsString(value);
        } catch (Exception exception) {
            return "{}";
        }
    }

    private static String message(Throwable error) {
        if (error == null) return "unknown app-server error";
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
