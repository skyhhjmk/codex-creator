package com.skyhhjmk.codexcreator.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skyhhjmk.codexcreator.config.AppServerConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@ApplicationScoped
public class CodexAppServerSupervisor {
    private static final Logger LOG = Logger.getLogger(CodexAppServerSupervisor.class);

    @Inject
    ObjectMapper mapper;

    @Inject
    AppServerConfig config;

    private final AtomicBoolean starting = new AtomicBoolean();
    private final CopyOnWriteArrayList<Consumer<ObjectNode>> listeners = new CopyOnWriteArrayList<>();
    private final Object lifecycleLock = new Object();
    private volatile JsonRpcClient client;
    private volatile CompletableFuture<Void> ready = CompletableFuture.failedFuture(
            new IllegalStateException("Codex app-server is disabled"));
    private volatile String lastError;
    private volatile long startedAt;

    @PostConstruct
    void initialize() {
        if (config.enabled()) {
            start();
        }
    }

    public void start() {
        if (!config.enabled() || !starting.compareAndSet(false, true)) {
            return;
        }
        synchronized (lifecycleLock) {
            try {
                Process process = new ProcessBuilder(command())
                        .directory(Path.of(config.workingDirectory()).toFile())
                        .redirectError(ProcessBuilder.Redirect.INHERIT)
                        .start();
                JsonRpcClient next = new JsonRpcClient(mapper, this::handleServerRequest);
                next.attach(process);
                listeners.forEach(next::addNotificationListener);
                client = next;
                startedAt = System.currentTimeMillis();
                ObjectNode params = mapper.createObjectNode();
                ObjectNode clientInfo = params.putObject("clientInfo");
                clientInfo.put("name", "codex-creator");
                clientInfo.put("title", "Codex Creator");
                clientInfo.put("version", "0.1.0");
                ObjectNode capabilities = params.putObject("capabilities");
                capabilities.put("experimentalApi", config.experimentalApi());
                ready = next.request("initialize", params, config.requestTimeout())
                        .thenCompose(ignored -> {
                            try {
                                next.notify("initialized", null);
                                return CompletableFuture.completedFuture(null);
                            } catch (IOException exception) {
                                return CompletableFuture.failedFuture(exception);
                            }
                        });
                ready.exceptionally(error -> {
                    lastError = message(error);
                    stop();
                    return null;
                });
                LOG.infof("Codex app-server started with %s", command());
            } catch (Exception exception) {
                lastError = message(exception);
                ready = CompletableFuture.failedFuture(exception);
                LOG.warn("Unable to start Codex app-server", exception);
            } finally {
                starting.set(false);
            }
        }
    }

    public CompletableFuture<JsonNode> request(String method, JsonNode params) {
        CompletableFuture<JsonNode> result = new CompletableFuture<>();
        ready.whenComplete((ignored, error) -> {
            if (error != null) {
                result.completeExceptionally(error);
                return;
            }
            JsonRpcClient current = client;
            if (current == null || !current.isAlive()) {
                result.completeExceptionally(new IllegalStateException("Codex app-server is not alive"));
                return;
            }
            current.request(method, params, config.requestTimeout()).whenComplete((value, requestError) -> {
                if (requestError != null && !current.isAlive()) {
                    lastError = message(requestError);
                    scheduleRestart();
                }
                if (requestError == null) result.complete(value);
                else result.completeExceptionally(requestError);
            });
        });
        return result;
    }

    public CompletableFuture<JsonNode> listModels() {
        ObjectNode params = mapper.createObjectNode();
        params.put("limit", 100);
        params.put("includeHidden", false);
        return request("model/list", params);
    }

    public CompletableFuture<JsonNode> awaitTurnCompletion(String threadId, String turnId) {
        CompletableFuture<JsonNode> result = new CompletableFuture<>();
        Consumer<ObjectNode> listener = message -> {
            if (!"turn/completed".equals(message.path("method").asText())) return;
            JsonNode params = message.path("params");
            String eventThreadId = params.path("threadId").asText(
                    params.path("turn").path("threadId").asText(""));
            String eventTurnId = params.path("turn").path("id").asText(
                    params.path("turnId").asText(""));
            boolean threadMatches = threadId == null || threadId.isBlank() || threadId.equals(eventThreadId)
                    || params.path("turn").path("threadId").asText("").equals(threadId);
            boolean turnMatches = turnId == null || turnId.isBlank() || turnId.equals(eventTurnId);
            if (threadMatches && turnMatches) {
                result.complete(params);
            }
        };
        addNotificationListener(listener);
        result.orTimeout(config.requestTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .whenComplete((ignored, error) -> removeNotificationListener(listener));
        return result;
    }

    public void removeNotificationListener(Consumer<ObjectNode> listener) {
        listeners.remove(listener);
        JsonRpcClient current = client;
        if (current != null) current.removeNotificationListener(listener);
    }

    public Map<String, Object> status() {
        JsonRpcClient current = client;
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", config.enabled());
        status.put("running", current != null && current.isAlive());
        status.put("ready", !ready.isCompletedExceptionally() && ready.isDone());
        status.put("experimentalApi", config.experimentalApi());
        status.put("schemaVersion", config.schemaVersion());
        status.put("startedAt", startedAt == 0 ? null : startedAt);
        status.put("lastError", lastError == null ? "" : lastError);
        return status;
    }

    public void addNotificationListener(Consumer<ObjectNode> listener) {
        listeners.add(listener);
        JsonRpcClient current = client;
        if (current != null) current.addNotificationListener(listener);
    }

    public void restart() {
        stop();
        if (config.enabled()) {
            CompletableFuture.delayedExecutor(config.restartBackoff().toMillis(), TimeUnit.MILLISECONDS)
                    .execute(this::start);
        }
    }

    @PreDestroy
    public void stop() {
        synchronized (lifecycleLock) {
            JsonRpcClient current = client;
            client = null;
            ready = CompletableFuture.failedFuture(new IllegalStateException("Codex app-server stopped"));
            if (current != null) current.close();
        }
    }

    private List<String> command() {
        String raw = config.command();
        List<String> command = new ArrayList<>(List.of(raw.split("\\s+")));
        command.add("app-server");
        command.add("--listen");
        command.add("stdio://");
        return command;
    }

    private JsonNode handleServerRequest(ObjectNode request) {
        String method = request.path("method").asText("");
        // Workflows are denied by default. An operator can add a policy-backed handler later.
        if (method.contains("approval") || method.startsWith("exec/")) {
            ObjectNode decline = mapper.createObjectNode();
            decline.put("decision", "decline");
            return decline;
        }
        return mapper.createObjectNode();
    }

    private void scheduleRestart() {
        if (!config.enabled()) return;
        CompletableFuture.delayedExecutor(config.restartBackoff().toMillis(), TimeUnit.MILLISECONDS)
                .execute(() -> {
                    if (client == null || !client.isAlive()) start();
                });
    }

    private static String message(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
