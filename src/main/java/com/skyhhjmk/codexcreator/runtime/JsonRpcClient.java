package com.skyhhjmk.codexcreator.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

/** Minimal bidirectional JSON-RPC-over-JSONL transport. */
public final class JsonRpcClient implements AutoCloseable {
    private final ObjectMapper mapper;
    private final AtomicLong ids = new AtomicLong(1);
    private final ConcurrentMap<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<ObjectNode>> notifications = new CopyOnWriteArrayList<>();
    private final Function<ObjectNode, JsonNode> serverRequestHandler;
    private final Object writeLock = new Object();
    private volatile Process process;
    private volatile BufferedWriter writer;
    private volatile Thread readerThread;

    public JsonRpcClient(ObjectMapper mapper, Function<ObjectNode, JsonNode> serverRequestHandler) {
        this.mapper = mapper;
        this.serverRequestHandler = serverRequestHandler == null ? ignored -> null : serverRequestHandler;
    }

    public synchronized void attach(Process process) throws IOException {
        if (this.process != null) {
            throw new IllegalStateException("JSON-RPC client is already attached");
        }
        this.process = process;
        this.writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        this.readerThread = Thread.ofVirtual().name("codex-jsonl-reader").start(this::readLoop);
    }

    public boolean isAlive() {
        Process current = process;
        return current != null && current.isAlive();
    }

    public CompletableFuture<JsonNode> request(String method, JsonNode params, Duration timeout) {
        if (!isAlive()) {
            return CompletableFuture.failedFuture(new IllegalStateException("JSON-RPC process is not alive"));
        }
        long id = ids.getAndIncrement();
        ObjectNode message = mapper.createObjectNode();
        message.put("id", id);
        message.put("method", method);
        if (params != null) {
            message.set("params", params);
        }
        CompletableFuture<JsonNode> result = new CompletableFuture<>();
        pending.put(id, result);
        try {
            send(message);
        } catch (IOException exception) {
            pending.remove(id);
            result.completeExceptionally(exception);
        }
        Duration effectiveTimeout = timeout == null || timeout.isNegative() || timeout.isZero()
                ? Duration.ofSeconds(120) : timeout;
        result.orTimeout(effectiveTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .whenComplete((ignored, error) -> pending.remove(id));
        return result;
    }

    public void notify(String method, JsonNode params) throws IOException {
        ObjectNode message = mapper.createObjectNode();
        message.put("method", method);
        if (params != null) {
            message.set("params", params);
        }
        send(message);
    }

    public void addNotificationListener(Consumer<ObjectNode> listener) {
        notifications.add(listener);
    }

    public void removeNotificationListener(Consumer<ObjectNode> listener) {
        notifications.remove(listener);
    }

    private void send(ObjectNode message) throws IOException {
        synchronized (writeLock) {
            if (writer == null) {
                throw new IOException("JSON-RPC writer is not ready");
            }
            writer.write(mapper.writeValueAsString(message));
            writer.newLine();
            writer.flush();
        }
    }

    private void readLoop() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                dispatch(mapper.readTree(line));
            }
            failPending(new EOFException("JSON-RPC process closed stdout"));
        } catch (Exception exception) {
            failPending(exception);
        }
    }

    private void dispatch(JsonNode message) throws IOException {
        JsonNode idNode = message.get("id");
        JsonNode methodNode = message.get("method");
        if (idNode != null && methodNode != null) {
            ObjectNode request = (ObjectNode) message;
            JsonNode result = serverRequestHandler.apply(request);
            ObjectNode response = mapper.createObjectNode();
            response.set("id", idNode);
            if (result == null) {
                response.set("result", mapper.createObjectNode());
            } else {
                response.set("result", result);
            }
            send(response);
            return;
        }
        if (idNode != null && idNode.canConvertToLong()) {
            CompletableFuture<JsonNode> future = pending.get(idNode.longValue());
            if (future != null) {
                if (message.has("error")) {
                    future.completeExceptionally(new JsonRpcException(message.get("error")));
                } else {
                    future.complete(message.get("result"));
                }
                return;
            }
        }
        if (methodNode != null && methodNode.isTextual()) {
            for (Consumer<ObjectNode> listener : notifications) {
                listener.accept((ObjectNode) message);
            }
        }
    }

    private void failPending(Throwable error) {
        pending.forEach((id, future) -> future.completeExceptionally(error));
        pending.clear();
    }

    @Override
    public synchronized void close() {
        failPending(new CancellationException("JSON-RPC client closed"));
        Process current = process;
        process = null;
        writer = null;
        if (current != null) {
            current.destroy();
            if (current.isAlive()) {
                current.destroyForcibly();
            }
        }
    }

    public static final class JsonRpcException extends RuntimeException {
        private final JsonNode error;

        public JsonRpcException(JsonNode error) {
            super(error == null ? "JSON-RPC request failed" : error.toString());
            this.error = error;
        }

        public JsonNode error() {
            return error;
        }
    }
}
