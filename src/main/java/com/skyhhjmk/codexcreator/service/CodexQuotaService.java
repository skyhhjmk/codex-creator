package com.skyhhjmk.codexcreator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skyhhjmk.codexcreator.config.QuotaConfig;
import com.skyhhjmk.codexcreator.runtime.CodexAppServerSupervisor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Reads and applies the ChatGPT Codex quota exposed by the app-server. */
@ApplicationScoped
public class CodexQuotaService {
    @Inject ObjectMapper mapper;
    @Inject CodexAppServerSupervisor supervisor;
    @Inject QuotaConfig config;

    private volatile Snapshot cached;
    private volatile Instant fetchedAt;
    private volatile String lastError = "";

    public CompletableFuture<Decision> admission(boolean bypass) {
        if (bypass) return CompletableFuture.completedFuture(new Decision(true, false, "OVERRIDDEN", snapshotView(), null));
        return refreshIfStale().thenApply(snapshot -> decide(snapshot, false));
    }

    public CompletableFuture<Map<String, Object>> read() {
        return refreshIfStale().thenApply(snapshot -> snapshotView());
    }

    public int effectiveIntervalMinutes(int configured, boolean accelerated) {
        return acceleratedIntervalMinutes(configured, accelerated);
    }

    static int acceleratedIntervalMinutes(int configured, boolean accelerated) {
        if (!accelerated) return configured;
        return Math.max(1, (int) Math.ceil(configured / 1.5d));
    }

    static boolean pausesForRemaining(int fiveHourRemaining, int weeklyRemaining,
                                      int fiveHourThreshold, int weeklyThreshold) {
        return fiveHourRemaining < fiveHourThreshold || weeklyRemaining < weeklyThreshold;
    }

    private CompletableFuture<Snapshot> refreshIfStale() {
        Instant now = Instant.now();
        Snapshot current = cached;
        Duration refresh = config.refreshInterval();
        if (current != null && fetchedAt != null && refresh != null
                && fetchedAt.plus(refresh).isAfter(now)) {
            return CompletableFuture.completedFuture(current);
        }
        JsonNode empty = mapper.createObjectNode();
        return supervisor.request("account/read", empty)
                .thenCompose(account -> supervisor.request("account/rateLimits/read", empty)
                        .thenApply(rate -> update(account, rate)))
                .exceptionally(error -> {
                    lastError = rootMessage(error);
                    Snapshot unavailable = Snapshot.unavailable(lastError);
                    cached = unavailable;
                    fetchedAt = Instant.now();
                    return unavailable;
                });
    }

    private Snapshot update(JsonNode accountResponse, JsonNode rateResponse) {
        String authMode = accountResponse.path("account").path("type").asText("");
        String planType = accountResponse.path("account").path("planType").asText("");
        JsonNode buckets = rateResponse.path("rateLimitsByLimitId");
        if (!buckets.isObject()) {
            buckets = mapper.createObjectNode();
            JsonNode single = rateResponse.path("rateLimits");
            if (single.isObject()) ((com.fasterxml.jackson.databind.node.ObjectNode) buckets).set(
                    single.path("limitId").asText("codex"), single);
        }
        Bucket fiveHour = null;
        Bucket weekly = null;
        List<Bucket> all = new ArrayList<>();
        buckets.fields().forEachRemaining(entry -> {
            Bucket bucket = bucket(entry.getKey(), entry.getValue());
            if (bucket != null) all.add(bucket);
        });
        for (Bucket bucket : all) {
            if (fiveHour == null && isFiveHour(bucket)) fiveHour = bucket;
            if (weekly == null && isWeekly(bucket)) weekly = bucket;
        }
        Snapshot result = new Snapshot(authMode, planType, fiveHour, weekly, all, "", Instant.now());
        cached = result;
        fetchedAt = result.fetchedAt();
        lastError = "";
        return result;
    }

    private Bucket bucket(String key, JsonNode node) {
        JsonNode primary = node.path("primary");
        if (!primary.isObject() || !primary.has("usedPercent")) return null;
        int used = Math.max(0, Math.min(100, primary.path("usedPercent").asInt(-1)));
        if (used < 0) return null;
        long reset = primary.path("resetsAt").asLong(0);
        int window = primary.path("windowDurationMins").asInt(0);
        return new Bucket(key, node.path("limitName").asText(""), used,
                Math.max(0, 100 - used), window, reset);
    }

    private boolean isFiveHour(Bucket bucket) {
        String text = (bucket.id() + " " + bucket.name()).toLowerCase();
        return text.contains("5h") || text.contains("five") || bucket.windowMinutes() >= 240 && bucket.windowMinutes() <= 360;
    }

    private boolean isWeekly(Bucket bucket) {
        String text = (bucket.id() + " " + bucket.name()).toLowerCase();
        return text.contains("week") || text.contains("7d") || bucket.windowMinutes() >= 10080;
    }

    private Decision decide(Snapshot snapshot, boolean bypass) {
        if (bypass) return new Decision(true, false, "OVERRIDDEN", snapshotView(), null);
        if (snapshot == null || snapshot.unavailable() || snapshot.fiveHour() == null || snapshot.weekly() == null) {
            return new Decision(false, false, "LIMIT_UNAVAILABLE", snapshotView(), null);
        }
        if (!"chatgpt".equalsIgnoreCase(snapshot.authMode())) {
            return new Decision(false, false, "CHATGPT_AUTH_REQUIRED", snapshotView(), null);
        }
        if (pausesForRemaining(snapshot.fiveHour().remainingPercent(), snapshot.weekly().remainingPercent(),
                config.fiveHourPauseRemainingPercent(), config.weeklyPauseRemainingPercent())) {
            long reset = earliestReset(snapshot);
            return new Decision(false, false, "QUOTA_PAUSED", snapshotView(), reset == 0 ? null : Instant.ofEpochSecond(reset));
        }
        boolean accelerated = nearReset(snapshot.fiveHour()) || nearReset(snapshot.weekly());
        return new Decision(true, accelerated, accelerated ? "ACCELERATED" : "NORMAL", snapshotView(), null);
    }

    private boolean nearReset(Bucket bucket) {
        if (bucket == null || bucket.resetAt() <= 0 || bucket.windowMinutes() <= 0) return false;
        long seconds = bucket.resetAt() - Instant.now().getEpochSecond();
        return seconds > 0 && seconds <= bucket.windowMinutes() * 60L * config.preResetWindowPercent() / 100L;
    }

    private long earliestReset(Snapshot snapshot) {
        long five = snapshot.fiveHour() == null ? Long.MAX_VALUE : snapshot.fiveHour().resetAt();
        long week = snapshot.weekly() == null ? Long.MAX_VALUE : snapshot.weekly().resetAt();
        return Math.min(five == 0 ? Long.MAX_VALUE : five, week == 0 ? Long.MAX_VALUE : week);
    }

    public Map<String, Object> snapshotView() {
        Snapshot snapshot = cached;
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("status", statusOf(snapshot));
        view.put("fetchedAt", snapshot == null ? null : snapshot.fetchedAt());
        view.put("authMode", snapshot == null ? "" : snapshot.authMode());
        view.put("planType", snapshot == null ? "" : snapshot.planType());
        view.put("lastError", lastError);
        view.put("fiveHour", bucketView(snapshot == null ? null : snapshot.fiveHour()));
        view.put("weekly", bucketView(snapshot == null ? null : snapshot.weekly()));
        view.put("policy", Map.of("fiveHourPauseRemainingPercent", config.fiveHourPauseRemainingPercent(),
                "weeklyPauseRemainingPercent", config.weeklyPauseRemainingPercent(),
                "preResetWindowPercent", config.preResetWindowPercent(), "accelerationFactor", 1.5));
        return view;
    }

    private String statusOf(Snapshot snapshot) {
        if (snapshot == null || snapshot.unavailable()) return "LIMIT_UNAVAILABLE";
        if (!"chatgpt".equalsIgnoreCase(snapshot.authMode())) return "CHATGPT_AUTH_REQUIRED";
        if (pausesForRemaining(snapshot.fiveHour().remainingPercent(), snapshot.weekly().remainingPercent(),
                config.fiveHourPauseRemainingPercent(), config.weeklyPauseRemainingPercent())) return "QUOTA_PAUSED";
        return nearReset(snapshot.fiveHour()) || nearReset(snapshot.weekly()) ? "ACCELERATED" : "NORMAL";
    }

    private Map<String, Object> bucketView(Bucket bucket) {
        if (bucket == null) return Map.of();
        return Map.of("limitId", bucket.id(), "limitName", bucket.name(), "usedPercent", bucket.usedPercent(),
                "remainingPercent", bucket.remainingPercent(), "windowDurationMins", bucket.windowMinutes(),
                "resetsAt", bucket.resetAt());
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current != null && current.getCause() != null) current = current.getCause();
        return current == null || current.getMessage() == null ? "Codex quota read failed" : current.getMessage();
    }

    public record Decision(boolean allowed, boolean accelerated, String status,
                           Map<String, Object> snapshot, Instant retryAt) {}
    private record Snapshot(String authMode, String planType, Bucket fiveHour, Bucket weekly,
                            List<Bucket> buckets, String error, Instant fetchedAt) {
        static Snapshot unavailable(String error) { return new Snapshot("", "", null, null, List.of(), error, Instant.now()); }
        boolean unavailable() { return fiveHour == null || weekly == null; }
    }
    private record Bucket(String id, String name, int usedPercent, int remainingPercent,
                          int windowMinutes, long resetAt) {}
}
